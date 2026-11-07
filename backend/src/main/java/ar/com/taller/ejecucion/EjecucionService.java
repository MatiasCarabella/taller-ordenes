package ar.com.taller.ejecucion;

import static ar.com.taller.ejecucion.ModelosEjecucion.*;
import ar.com.taller.acceso.CuentaPrincipal;
import ar.com.taller.operacion.NegocioException;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EjecucionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final byte[] signingKey=new byte[32];
    private static final Set<String> CANCELABLES=Set.of("RECIBIDO","DIAGNOSTICADO","PENDIENTE_APROBACION","APROBADO");
    private static final ZoneId BUENOS_AIRES=ZoneId.of("America/Argentina/Buenos_Aires");
    private static final String SELECT="""
            SELECT o.*,p.version AS version_autorizada,p.decision AS decision_autorizada,p.total AS total_autorizado
            FROM orden o LEFT JOIN presupuesto p ON p.id=o.presupuesto_autorizado_id AND p.orden_id=o.id
            """;
    private record Orden(long id,String estado,Long autorizado,Integer version,String decision,BigDecimal ingreso,
            String trabajo,Instant actualizado,Instant iniciado,Instant finalizado,Instant entregado,Instant cancelado,
            String motivo,BigDecimal ingresoFinal) {}
    private record Tasa(long id,BigDecimal valor) {}
    private record Cuentas(BigDecimal ingreso,BigDecimal repuestos,BigDecimal horas,BigDecimal tarifa,BigDecimal manoObra) {}
    public EjecucionService(JdbcClient jdbc,TransactionTemplate tx,Clock clock){
        this.jdbc=jdbc;this.tx=tx;this.clock=clock;new SecureRandom().nextBytes(signingKey);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static String iso(Instant i){return i==null?null:i.toString();}
    private static String money(BigDecimal b){return b==null?null:b.setScale(2,RoundingMode.HALF_UP).toPlainString();}
    private static Instant instant(ResultSet r,String f)throws SQLException{
        var v=r.getObject(f,LocalDateTime.class);return v==null?null:v.toInstant(ZoneOffset.UTC);
    }
    private static NegocioException conflict(String code,String message){return new NegocioException(HttpStatus.CONFLICT,code,message);}
    private static NegocioException invalid(String message){return new NegocioException(HttpStatus.BAD_REQUEST,"DATOS_INVALIDOS",message);}
    private static void role(CuentaPrincipal a,String perfil){
        if(!a.perfil().equals(perfil))throw new NegocioException(HttpStatus.FORBIDDEN,"ACCESO_DENEGADO","No tenés permiso para esta acción.");
    }
    private Orden order(CuentaPrincipal actor,long id,boolean lock){
        if(id<1)throw NegocioException.notFound();
        String scope=actor.perfil().equals("CLIENTE")?" AND o.cliente_id=:cliente":"";
        var q=jdbc.sql(SELECT+" WHERE o.id=:id"+scope+(lock?" FOR UPDATE":"")).param("id",id);
        if(!scope.isEmpty())q=q.param("cliente",actor.id());
        return q.query((r,n)->new Orden(r.getLong("id"),r.getString("estado"),r.getObject("presupuesto_autorizado_id",Long.class),
                r.getObject("version_autorizada",Integer.class),r.getString("decision_autorizada"),r.getBigDecimal("total_autorizado"),
                r.getString("trabajo_realizado"),instant(r,"actualizado_en"),instant(r,"iniciado_en"),instant(r,"finalizado_en"),
                instant(r,"entregado_en"),instant(r,"cancelado_en"),r.getString("motivo_cancelacion"),r.getBigDecimal("ingreso_final")))
                .optional().orElseThrow(NegocioException::notFound);
    }
    private Ejecucion view(CuentaPrincipal a,Orden o){
        boolean staff=a.perfil().equals("PERSONAL"),repair=o.estado().equals("EN_REPARACION");
        return new Ejecucion(o.id(),o.estado(),o.autorizado(),o.version(),o.trabajo(),iso(o.iniciado()),iso(o.finalizado()),
                iso(o.entregado()),iso(o.cancelado()),o.motivo(),money(o.ingresoFinal()),
                staff&&o.estado().equals("APROBADO"),staff&&repair,staff&&repair,
                staff&&o.estado().equals("FINALIZADO"),a.perfil().equals("ADMINISTRACION")&&CANCELABLES.contains(o.estado()));
    }
    @Transactional(readOnly=true)
    public Ejecucion contexto(CuentaPrincipal a,long id){return view(a,order(a,id,false));}
    private static void authorization(Orden o,Long expected){
        if(o.autorizado()==null||!"ACEPTADO".equals(o.decision())||o.ingreso()==null)
            throw conflict("AUTORIZACION_REQUERIDA","La orden necesita un presupuesto aceptado de esta misma orden.");
        if(!Objects.equals(o.autorizado(),expected))
            throw conflict("AUTORIZACION_CAMBIADA","Cambió el alcance autorizado. Actualizá la orden y revisá el trabajo.");
    }
    private static void repair(Orden o){
        if(!o.estado().equals("EN_REPARACION"))throw conflict("ESTADO_INCOMPATIBLE","Esta acción requiere una orden en reparación.");
    }
    public Ejecucion iniciar(CuentaPrincipal a,long id,Inicio request){
        role(a,"PERSONAL");return tx.execute(s->{
            var o=order(a,id,true);
            if(!o.estado().equals("APROBADO"))throw conflict("ESTADO_INCOMPATIBLE","Solo una orden aprobada puede iniciar reparación.");
            authorization(o,request.presupuestoAutorizadoId());var f=LocalDateTime.ofInstant(now(),ZoneOffset.UTC);
            jdbc.sql("UPDATE orden SET estado='EN_REPARACION',iniciado_en=:f,actualizado_en=:f WHERE id=:id").param("id",id).param("f",f).update();
            return view(a,order(a,id,false));
        });
    }
    public Ejecucion trabajo(CuentaPrincipal a,long id,Trabajo request){
        role(a,"PERSONAL");return tx.execute(s->{
            var o=order(a,id,true);repair(o);authorization(o,request.presupuestoAutorizadoId());
            jdbc.sql("UPDATE orden SET trabajo_realizado=:t,actualizado_en=:f WHERE id=:id").param("id",id)
                    .param("t",request.trabajoRealizado()).param("f",LocalDateTime.ofInstant(now(),ZoneOffset.UTC)).update();
            return view(a,order(a,id,false));
        });
    }
    private Tasa tasa(boolean lock){
        return jdbc.sql("SELECT id,tarifa_hora_vigente FROM usuario WHERE perfil='ADMINISTRACION'"+(lock?" FOR SHARE":""))
                .query((r,n)->new Tasa(r.getLong("id"),r.getBigDecimal("tarifa_hora_vigente"))).optional()
                .orElseThrow(()->conflict("TARIFA_NO_CONFIGURADA","Administración debe configurar una tarifa positiva antes de finalizar."));
    }
    private Cuentas calculate(Orden o,Tasa t,Cierre request){
        repair(o);authorization(o,request.presupuestoAutorizadoId());
        if(t.valor()==null||t.valor().signum()<=0)throw conflict("TARIFA_NO_CONFIGURADA","Administración debe configurar una tarifa positiva antes de finalizar.");
        return new Cuentas(o.ingreso(),request.costoRepuestosTotal(),request.horasManoObraTotal(),t.valor(),
                request.horasManoObraTotal().multiply(t.valor()).setScale(2,RoundingMode.HALF_UP));
    }
    private void pending(long id,Instant current){
        long count=jdbc.sql("SELECT COUNT(*) FROM presupuesto WHERE orden_id=:id AND decision='PENDIENTE' AND vence_en>:f")
                .param("id",id).param("f",LocalDateTime.ofInstant(current,ZoneOffset.UTC)).query(Long.class).single();
        if(count>0)throw conflict("PROPUESTA_PENDIENTE","Hay propuestas pendientes con plazo vigente. Deben recibir respuesta o vencer antes del cierre.");
    }
    private int lastVersion(long id){return jdbc.sql("SELECT COALESCE(MAX(version),0) FROM presupuesto WHERE orden_id=:id").param("id",id).query(Integer.class).single();}
    // Length-prefixed fields avoid ambiguous serialization; the token binds input and the reviewed snapshot.
    private String signature(long expires,CuentaPrincipal a,Orden o,Tasa t,Cierre request){
        var fields=List.of(Long.toString(expires),Long.toString(a.id()),Long.toString(o.id()),o.estado(),
                String.valueOf(o.autorizado()),Integer.toString(lastVersion(o.id())),String.valueOf(o.actualizado()),
                Objects.toString(o.trabajo(),""),Long.toString(t.id()),money(t.valor()),money(o.ingreso()),
                request.trabajoRealizado(),money(request.costoRepuestosTotal()),money(request.horasManoObraTotal()));
        var value=new StringBuilder();for(String f:fields)value.append(f.length()).append(':').append(f);
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("No se pudo verificar la confirmación",e);}
    }
    public VistaCierre previsualizar(CuentaPrincipal a,long id,Cierre request){
        role(a,"PERSONAL");return tx.execute(s->{
            var o=order(a,id,true);var t=tasa(true);var accounts=calculate(o,t,request);Instant current=now();pending(id,current);
            long expires=current.plus(Duration.ofMinutes(10)).toEpochMilli();
            String token=expires+"."+signature(expires,a,o,t,request);
            return new VistaCierre(id,o.autorizado(),o.version(),request.trabajoRealizado(),money(accounts.ingreso()),
                    money(accounts.repuestos()),money(accounts.horas()),money(accounts.tarifa()),money(accounts.manoObra()),
                    token,Instant.ofEpochMilli(expires).toString());
        });
    }
    public Ejecucion cerrar(CuentaPrincipal a,long id,ConfirmarCierre request){
        role(a,"PERSONAL");return tx.execute(s->{
            var o=order(a,id,true);var t=tasa(true);var accounts=calculate(o,t,request.datos());Instant current=now();pending(id,current);
            boolean valid=false;
            try {
                String[] parts=request.confirmacion().split("\\.",-1);long expires=Long.parseLong(parts[0]);
                valid=parts.length==2&&current.toEpochMilli()<expires&&expires<=current.plus(Duration.ofMinutes(10)).toEpochMilli()
                        &&MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[1]),
                        Base64.getUrlDecoder().decode(signature(expires,a,o,t,request.datos())));
            }catch(IllegalArgumentException e){valid=false;}
            if(!valid)throw conflict("VISTA_PREVIA_DESACTUALIZADA","La vista previa cambió o venció. Revisá los valores y calculala nuevamente antes de cerrar.");
            var f=LocalDateTime.ofInstant(current,ZoneOffset.UTC);
            jdbc.sql("""
                    UPDATE orden SET estado='FINALIZADO',trabajo_realizado=:trabajo,ingreso_final=:ingreso,
                    costo_repuestos_total=:repuestos,horas_mano_obra_total=:horas,tarifa_hora_aplicada=:tarifa,
                    finalizado_en=:f,actualizado_en=:f WHERE id=:id
                    """).param("id",id).param("trabajo",request.datos().trabajoRealizado()).param("ingreso",accounts.ingreso())
                    .param("repuestos",accounts.repuestos()).param("horas",accounts.horas()).param("tarifa",accounts.tarifa()).param("f",f).update();
            return view(a,order(a,id,false));
        });
    }
    public Ejecucion entregar(CuentaPrincipal a,long id){
        role(a,"PERSONAL");return tx.execute(s->{
            var o=order(a,id,true);
            if(o.estado().equals("ENTREGADO"))return view(a,o);
            if(!o.estado().equals("FINALIZADO"))throw conflict("ESTADO_INCOMPATIBLE","Solo un servicio finalizado puede registrarse como entregado.");
            jdbc.sql("UPDATE orden SET estado='ENTREGADO',entregado_en=:f,actualizado_en=:f WHERE id=:id")
                    .param("id",id).param("f",LocalDateTime.ofInstant(now(),ZoneOffset.UTC)).update();
            return view(a,order(a,id,false));
        });
    }
    public Ejecucion cancelar(CuentaPrincipal a,long id,Cancelacion request){
        role(a,"ADMINISTRACION");return tx.execute(s->{
            var o=order(a,id,true);
            if(!CANCELABLES.contains(o.estado()))throw conflict("CANCELACION_NO_PERMITIDA","Solo se puede cancelar una orden antes de iniciar su reparación.");
            jdbc.sql("UPDATE orden SET estado='CANCELADO',cancelado_en=:f,motivo_cancelacion=:motivo,actualizado_en=:f WHERE id=:id")
                    .param("id",id).param("f",LocalDateTime.ofInstant(now(),ZoneOffset.UTC)).param("motivo",request.motivo()).update();
            return view(a,order(a,id,false));
        });
    }
    @Transactional(readOnly=true)
    public Tarifa tarifa(CuentaPrincipal a){role(a,"ADMINISTRACION");return new Tarifa(money(tasa(false).valor()));}
    public Tarifa cambiarTarifa(CuentaPrincipal a,NuevaTarifa request){
        role(a,"ADMINISTRACION");return tx.execute(s->{
            jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=:t WHERE id=:id AND perfil='ADMINISTRACION'")
                    .param("t",request.tarifaHora()).param("id",a.id()).update();return new Tarifa(money(request.tarifaHora()));
        });
    }
    private static String percentage(BigDecimal margin,BigDecimal income){
        return income.signum()>0?margin.multiply(new BigDecimal("100")).divide(income,2,RoundingMode.HALF_UP).toPlainString():null;
    }
    @Transactional(readOnly=true)
    public Reporte resultados(CuentaPrincipal a,LocalDate desde,LocalDate hasta,int page,int size){
        role(a,"ADMINISTRACION");
        if(desde==null||hasta==null||desde.isAfter(hasta)||desde.getYear()<1000||hasta.isAfter(LocalDate.of(9999,12,30))||page<0||size<1||size>50)
            throw invalid("Revisá las fechas y la paginación.");
        Instant start=desde.atStartOfDay(BUENOS_AIRES).toInstant(),end=hasta.plusDays(1).atStartOfDay(BUENOS_AIRES).toInstant();
        var params=new HashMap<String,Object>();params.put("desde",LocalDateTime.ofInstant(start,ZoneOffset.UTC));params.put("hasta",LocalDateTime.ofInstant(end,ZoneOffset.UTC));
        String closed=" estado IN ('FINALIZADO','ENTREGADO') AND finalizado_en>=:desde AND finalizado_en<:hasta";
        var sums=jdbc.sql("""
                SELECT COUNT(*) AS cantidad,COALESCE(SUM(ingreso_final),0) AS ingreso,
                COALESCE(SUM(costo_repuestos_total),0) AS repuestos,
                COALESCE(SUM(ROUND(horas_mano_obra_total*tarifa_hora_aplicada,2)),0) AS mano_obra
                FROM orden WHERE
                """+closed).params(params).query().singleRow();
        BigDecimal income=(BigDecimal)sums.get("ingreso"),parts=(BigDecimal)sums.get("repuestos"),labor=(BigDecimal)sums.get("mano_obra"),margin=income.subtract(parts).subtract(labor);
        long rejected=jdbc.sql("SELECT COUNT(*) FROM orden WHERE estado='RECHAZADO' AND actualizado_en>=:desde AND actualizado_en<:hasta").params(params).query(Long.class).single();
        long cancelled=jdbc.sql("SELECT COUNT(*) FROM orden WHERE estado='CANCELADO' AND cancelado_en>=:desde AND cancelado_en<:hasta").params(params).query(Long.class).single();
        params.put("limit",size);params.put("offset",(long)page*size);
        var rows=jdbc.sql("""
                SELECT o.id,v.dominio,c.nombre AS cliente_nombre,o.estado,o.finalizado_en,o.ingreso_final,
                o.costo_repuestos_total,o.horas_mano_obra_total,o.tarifa_hora_aplicada
                FROM orden o JOIN vehiculo v ON v.id=o.vehiculo_id JOIN usuario c ON c.id=o.cliente_id WHERE
                """+closed+" ORDER BY finalizado_en DESC,o.id DESC LIMIT :limit OFFSET :offset").params(params).query((r,n)->{
            BigDecimal i=r.getBigDecimal("ingreso_final"),cr=r.getBigDecimal("costo_repuestos_total"),h=r.getBigDecimal("horas_mano_obra_total"),t=r.getBigDecimal("tarifa_hora_aplicada");
            BigDecimal cm=h.multiply(t).setScale(2,RoundingMode.HALF_UP),m=i.subtract(cr).subtract(cm);
            return new Resultado(r.getLong("id"),r.getString("dominio"),r.getString("cliente_nombre"),r.getString("estado"),iso(instant(r,"finalizado_en")),
                    money(i),money(cr),money(h),money(t),money(cm),money(m),percentage(m,i));
        }).list();
        return new Reporte(desde.toString(),hasta.toString(),start.toString(),end.toString(),
                new Totales(((Number)sums.get("cantidad")).longValue(),rejected,cancelled,money(income),money(parts),money(labor),money(margin),percentage(margin,income)),
                rows,page,size);
    }
}
