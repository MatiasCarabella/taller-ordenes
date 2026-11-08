package ar.com.taller.presupuestos;

import static ar.com.taller.presupuestos.ModelosPresupuesto.*;
import static ar.com.taller.canales.ModelosCanales.*;
import ar.com.taller.acceso.CuentaPrincipal;
import ar.com.taller.operacion.NegocioException;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PresupuestosService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private static final Set<String> PUBLICABLES=Set.of("DIAGNOSTICADO","PENDIENTE_APROBACION","APROBADO","EN_REPARACION");
    private static final Set<String> DECIDIBLES=Set.of("PENDIENTE_APROBACION","APROBADO","EN_REPARACION");
    private static final BigDecimal MAXIMO=new BigDecimal("9999999999.99");

    public PresupuestosService(JdbcClient jdbc,TransactionTemplate tx,Clock clock) {this.jdbc=jdbc;this.tx=tx;this.clock=clock;}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static NegocioException conflict(String code,String message){return new NegocioException(HttpStatus.CONFLICT,code,message);}
    private static NegocioException invalid(String code,String message){return new NegocioException(HttpStatus.BAD_REQUEST,code,message);}
    private static void role(CuentaPrincipal actor,String profile) {
        if(!actor.perfil().equals(profile))throw new NegocioException(HttpStatus.FORBIDDEN,"ACCESO_DENEGADO","No tenés permiso para esta acción.");
    }
    private record Orden(long id,long clienteId,String estado,String diagnostico,Long diagnosticoAutorId,Instant diagnosticadoEn,Long autorizadoId) {}
    private Orden order(CuentaPrincipal actor,long id,boolean lock) {
        if(id<1)throw NegocioException.notFound();
        String scope=actor.perfil().equals("CLIENTE")?" AND cliente_id=:cliente":"";
        var query=jdbc.sql("SELECT id,cliente_id,estado,diagnostico,diagnostico_autor_id,diagnosticado_en,presupuesto_autorizado_id FROM orden WHERE id=:id"+scope+(lock?" FOR UPDATE":""))
                .param("id",id);
        if(!scope.isEmpty())query=query.param("cliente",actor.id());
        return query.query((r,n)->new Orden(r.getLong("id"),r.getLong("cliente_id"),r.getString("estado"),r.getString("diagnostico"),
                r.getObject("diagnostico_autor_id",Long.class),instant(r,"diagnosticado_en"),r.getObject("presupuesto_autorizado_id",Long.class)))
                .optional().orElseThrow(NegocioException::notFound);
    }
    private static Instant instant(ResultSet r,String name)throws SQLException {
        var value=r.getObject(name,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);
    }
    private static String iso(Instant value){return value==null?null:value.toString();}
    private int lastVersion(long id) {return jdbc.sql("SELECT COALESCE(MAX(version),0) FROM presupuesto WHERE orden_id=:id").param("id",id).query(Integer.class).single();}
    private static boolean editable(Orden order,int version){return version==0&&Set.of("RECIBIDO","DIAGNOSTICADO").contains(order.estado());}

    private record Budget(long id,int version,String autorNombre,Instant publicadoEn,Instant venceEn,BigDecimal total,
            String decision,String decisorNombre,Instant decididoEn) {}
    private record Line(long presupuestoId,RenglonView value) {}

    @Transactional(readOnly=true)
    public Contexto contexto(CuentaPrincipal actor,long id) {
        var o=order(actor,id,false);Instant current=now();
        var budgets=jdbc.sql("""
                SELECT p.id,p.version,a.nombre AS autor_nombre,p.publicado_en,p.vence_en,p.total,p.decision,
                    d.nombre AS decisor_nombre,p.decidido_en FROM presupuesto p
                JOIN usuario a ON a.id=p.autor_id LEFT JOIN usuario d ON d.id=p.decisor_id
                WHERE p.orden_id=:id ORDER BY p.version DESC
                """).param("id",id).query((r,n)->new Budget(r.getLong("id"),r.getInt("version"),r.getString("autor_nombre"),
                instant(r,"publicado_en"),instant(r,"vence_en"),r.getBigDecimal("total"),r.getString("decision"),
                r.getString("decisor_nombre"),instant(r,"decidido_en"))).list();
        var lines=jdbc.sql("""
                SELECT r.presupuesto_id,r.numero,r.tipo,r.descripcion,r.cantidad,r.precio_unitario
                FROM renglon r JOIN presupuesto p ON p.id=r.presupuesto_id
                WHERE p.orden_id=:id ORDER BY p.version DESC,r.numero
                """).param("id",id).query((r,n)->new Line(r.getLong("presupuesto_id"),line(r.getInt("numero"),
                r.getString("tipo"),r.getString("descripcion"),r.getBigDecimal("cantidad"),r.getBigDecimal("precio_unitario")))).list();
        int last=budgets.isEmpty()?0:budgets.getFirst().version();
        var views=budgets.stream().map(b->new PresupuestoView(b.id(),b.version(),b.autorNombre(),iso(b.publicadoEn()),
                iso(b.venceEn()),b.total().toPlainString(),b.decision(),b.decisorNombre(),iso(b.decididoEn()),b.version()==last,
                Objects.equals(o.autorizadoId(),b.id()),b.decision().equals("PENDIENTE")&&!current.isBefore(b.venceEn()),
                actor.perfil().equals("CLIENTE")&&DECIDIBLES.contains(o.estado())&&b.version()==last&&b.decision().equals("PENDIENTE")&&current.isBefore(b.venceEn()),
                lines.stream().filter(l->l.presupuestoId()==b.id()).map(Line::value).toList())).toList();
        String author=o.diagnosticoAutorId()==null?null:jdbc.sql("SELECT nombre FROM usuario WHERE id=:id").param("id",o.diagnosticoAutorId()).query(String.class).single();
        return new Contexto(id,o.estado(),o.diagnostico(),author,iso(o.diagnosticadoEn()),last,o.autorizadoId(),
                actor.perfil().equals("PERSONAL")&&editable(o,last),
                actor.perfil().equals("PERSONAL")&&PUBLICABLES.contains(o.estado())&&o.diagnostico()!=null,current.toString(),views);
    }

    @Transactional(readOnly=true)
    public PresupuestoView presupuesto(CuentaPrincipal actor,long ordenId,long id) {
        return contexto(actor,ordenId).presupuestos().stream().filter(p->p.id()==id).findFirst().orElseThrow(NegocioException::notFound);
    }

    public Contexto diagnosticar(CuentaPrincipal actor,long id,DiagnosticoRequest request) {
        role(actor,"PERSONAL");
        return tx.execute(status->{
            var o=order(actor,id,true);
            if(!editable(o,lastVersion(id)))throw conflict("DIAGNOSTICO_FIJO","El diagnóstico ya no admite cambios.");
            var current=LocalDateTime.ofInstant(now(),ZoneOffset.UTC);
            jdbc.sql("""
                    UPDATE orden SET diagnostico=:diagnostico,diagnostico_autor_id=:autor,diagnosticado_en=:fecha,
                    actualizado_en=:fecha,estado='DIAGNOSTICADO' WHERE id=:id
                    """).param("diagnostico",request.diagnostico()).param("autor",actor.id()).param("fecha",current).param("id",id).update();
            return contexto(actor,id);
        });
    }

    private static RenglonView line(int number,String type,String description,BigDecimal quantity,BigDecimal unitPrice) {
        BigDecimal subtotal=quantity.multiply(unitPrice).setScale(2,RoundingMode.HALF_UP);
        if(subtotal.compareTo(MAXIMO)>0)throw invalid("MONTO_FUERA_DE_RANGO","El importe supera el máximo admitido.");
        return new RenglonView(number,type,description,quantity.setScale(2).toPlainString(),
                unitPrice.setScale(2).toPlainString(),subtotal.toPlainString());
    }
    private Previsualizacion calculate(PublicacionRequest request,Instant current) {
        Instant deadline=request.venceEn().truncatedTo(ChronoUnit.MILLIS);
        if(!deadline.isAfter(current)||deadline.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid("VENCIMIENTO_INVALIDO","El vencimiento debe ser posterior al momento de publicación.");
        var rows=new ArrayList<RenglonView>();BigDecimal total=new BigDecimal("0.00");
        for(var r:request.renglones()) {
            var value=line(rows.size()+1,r.tipo(),r.descripcion(),r.cantidad(),r.precioUnitario());
            total=total.add(new BigDecimal(value.subtotal()));
            if(total.compareTo(MAXIMO)>0)throw invalid("MONTO_FUERA_DE_RANGO","El total supera el máximo admitido.");
            rows.add(value);
        }
        return new Previsualizacion(deadline.toString(),total.toPlainString(),List.copyOf(rows));
    }
    private int publicationContext(Orden o,PublicacionRequest request) {
        if(!PUBLICABLES.contains(o.estado())||o.diagnostico()==null)
            throw conflict("PUBLICACION_NO_PERMITIDA","La orden no admite una publicación en su estado actual.");
        int version=lastVersion(o.id());
        if(request.ultimaVersionEsperada()!=version||!Objects.equals(request.presupuestoAutorizadoEsperadoId(),o.autorizadoId()))
            throw conflict("CONTEXTO_DESACTUALIZADO","La orden cambió. Actualizá sus datos y revisá la propuesta antes de publicar.");
        return version;
    }

    @Transactional(readOnly=true)
    public Previsualizacion previsualizar(CuentaPrincipal actor,long id,PublicacionRequest request) {
        role(actor,"PERSONAL");var o=order(actor,id,false);publicationContext(o,request);return calculate(request,now());
    }

    public Operacion<Contexto> publicar(CuentaPrincipal actor,long id,PublicacionRequest request) {
        role(actor,"PERSONAL");
        return tx.execute(status->{
            var o=order(actor,id,true);
            int version=publicationContext(o,request)+1;
            // The time check is performed only after acquiring the order lock.
            Instant current=now();var preview=calculate(request,current);
            var keys=new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO presupuesto(orden_id,version,autor_id,publicado_en,vence_en,total,decision)
                    VALUES (:orden,:version,:autor,:fecha,:vence,:total,'PENDIENTE')
                    """).param("orden",id).param("version",version).param("autor",actor.id())
                    .param("fecha",LocalDateTime.ofInstant(current,ZoneOffset.UTC))
                    .param("vence",LocalDateTime.ofInstant(Instant.parse(preview.venceEn()),ZoneOffset.UTC))
                    .param("total",new BigDecimal(preview.total())).update(keys,"id");
            long presupuestoId=Objects.requireNonNull(keys.getKey()).longValue();
            for(var r:preview.renglones())
                jdbc.sql("""
                        INSERT INTO renglon(presupuesto_id,numero,tipo,descripcion,cantidad,precio_unitario)
                        VALUES (:presupuesto,:numero,:tipo,:descripcion,:cantidad,:precio)
                        """).param("presupuesto",presupuestoId).param("numero",r.numero()).param("tipo",r.tipo())
                        .param("descripcion",r.descripcion()).param("cantidad",new BigDecimal(r.cantidad()))
                        .param("precio",new BigDecimal(r.precioUnitario())).update();
            jdbc.sql("UPDATE orden SET estado=:estado,actualizado_en=:fecha WHERE id=:id")
                    .param("estado",o.estado().equals("DIAGNOSTICADO")?"PENDIENTE_APROBACION":o.estado())
                    .param("fecha",LocalDateTime.ofInstant(current,ZoneOffset.UTC)).param("id",id).update();
            return new Operacion<>(contexto(actor,id),new Evento(id,o.clienteId(),"PRESUPUESTO_PUBLICADO"));
        });
    }

    public Operacion<Contexto> decidir(CuentaPrincipal actor,long ordenId,long presupuestoId,DecisionRequest request) {
        role(actor,"CLIENTE");
        return tx.execute(status->{
            var o=order(actor,ordenId,true);
            var budget=jdbc.sql("SELECT id,version,decision,vence_en FROM presupuesto WHERE id=:id AND orden_id=:orden")
                    .param("id",presupuestoId).param("orden",ordenId)
                    .query((r,n)->new Budget(r.getLong("id"),r.getInt("version"),null,null,instant(r,"vence_en"),null,r.getString("decision"),null,null))
                    .optional().orElseThrow(NegocioException::notFound);
            if(!DECIDIBLES.contains(o.estado()))throw conflict("DECISION_NO_PERMITIDA","La orden ya no admite esta respuesta.");
            if(budget.version()!=lastVersion(ordenId))throw conflict("VERSION_SUSTITUIDA","Hay otra versión publicada. Actualizá la orden antes de responder.");
            if(!budget.decision().equals("PENDIENTE"))throw conflict("PRESUPUESTO_RESPONDIDO","Esta versión ya recibió una respuesta.");
            Instant current=now();
            if(!current.isBefore(budget.venceEn()))throw conflict("PRESUPUESTO_VENCIDO","Terminó el plazo para responder esta versión.");
            int changed=jdbc.sql("""
                    UPDATE presupuesto SET decision=:decision,decisor_id=:decisor,decidido_en=:fecha
                    WHERE id=:id AND decision='PENDIENTE'
                    """).param("decision",request.decision()).param("decisor",actor.id())
                    .param("fecha",LocalDateTime.ofInstant(current,ZoneOffset.UTC)).param("id",presupuestoId).update();
            if(changed!=1)throw conflict("PRESUPUESTO_RESPONDIDO","Esta versión ya recibió una respuesta.");
            Long authorization=o.autorizadoId();String state=o.estado();
            if(request.decision().equals("ACEPTADO")) {
                authorization=presupuestoId;
                if(state.equals("PENDIENTE_APROBACION"))state="APROBADO";
            } else if(authorization==null&&request.decision().equals("RECHAZADO"))state="RECHAZADO";
            jdbc.sql("UPDATE orden SET presupuesto_autorizado_id=:autorizado,estado=:estado,actualizado_en=:fecha WHERE id=:id")
                    .param("autorizado",authorization).param("estado",state)
                    .param("fecha",LocalDateTime.ofInstant(current,ZoneOffset.UTC)).param("id",ordenId).update();
            return new Operacion<>(contexto(actor,ordenId),state.equals(o.estado())?null:new Evento(ordenId,o.clienteId(),state));
        });
    }
}
