package ar.com.taller.documentos;

import ar.com.taller.acceso.CuentaPrincipal;
import ar.com.taller.operacion.NegocioException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentosService {
    private final JdbcClient jdbc;
    private final Clock clock;
    // Projection restricted to information shared with the client.
    public record Orden(long id,String cliente,String dominio,String marca,String modelo,String responsable,String estado,
            String motivo,String diagnostico,String trabajo,Instant recibido,Instant iniciado,Instant finalizado,Instant entregado,
            Long autorizadoId,Integer versionAutorizada,BigDecimal ingresoFinal) {}
    public record Renglon(int numero,String tipo,String descripcion,BigDecimal cantidad,BigDecimal precio,BigDecimal subtotal) {}
    public record Version(long id,int numero,String autor,Instant publicado,Instant vence,BigDecimal total,String decision,
            String decisor,Instant decidido,List<Renglon> renglones) {}
    public record Documento(Orden orden,Version version,Instant generado,boolean resumen) {}
    public DocumentosService(JdbcClient jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    private static Instant instant(ResultSet r,String f)throws SQLException {
        var v=r.getObject(f,LocalDateTime.class);return v==null?null:v.toInstant(ZoneOffset.UTC);
    }
    private Orden order(CuentaPrincipal actor,long id){
        if(id<1)throw NegocioException.notFound();
        String scope=actor.perfil().equals("CLIENTE")?" AND o.cliente_id=:cliente":"";
        var q=jdbc.sql("""
                SELECT o.id,c.nombre AS cliente,v.dominio,v.marca,v.modelo,u.nombre AS responsable,o.estado,
                o.motivo_ingreso,o.diagnostico,o.trabajo_realizado,o.recibido_en,o.iniciado_en,o.finalizado_en,
                o.entregado_en,o.presupuesto_autorizado_id,p.version AS version_autorizada,o.ingreso_final
                FROM orden o JOIN usuario c ON c.id=o.cliente_id JOIN vehiculo v ON v.id=o.vehiculo_id
                JOIN usuario u ON u.id=o.responsable_id LEFT JOIN presupuesto p
                ON p.id=o.presupuesto_autorizado_id AND p.orden_id=o.id WHERE o.id=:id
                """+scope).param("id",id);
        if(!scope.isEmpty())q=q.param("cliente",actor.id());
        return q.query((r,n)->new Orden(r.getLong("id"),r.getString("cliente"),r.getString("dominio"),r.getString("marca"),
                r.getString("modelo"),r.getString("responsable"),r.getString("estado"),r.getString("motivo_ingreso"),
                r.getString("diagnostico"),r.getString("trabajo_realizado"),instant(r,"recibido_en"),instant(r,"iniciado_en"),
                instant(r,"finalizado_en"),instant(r,"entregado_en"),r.getObject("presupuesto_autorizado_id",Long.class),
                r.getObject("version_autorizada",Integer.class),r.getBigDecimal("ingreso_final")))
                .optional().orElseThrow(NegocioException::notFound);
    }
    private Version version(long ordenId,long id){
        var rows=jdbc.sql("""
                SELECT p.id,p.version,u.nombre AS autor,p.publicado_en,p.vence_en,p.total,p.decision,
                c.nombre AS decisor,p.decidido_en FROM presupuesto p JOIN usuario u ON u.id=p.autor_id
                LEFT JOIN usuario c ON c.id=p.decisor_id WHERE p.id=:id AND p.orden_id=:orden
                """).param("id",id).param("orden",ordenId).query((r,n)->new Version(r.getLong("id"),r.getInt("version"),
                r.getString("autor"),instant(r,"publicado_en"),instant(r,"vence_en"),r.getBigDecimal("total"),
                r.getString("decision"),r.getString("decisor"),instant(r,"decidido_en"),List.of()))
                .optional().orElseThrow(NegocioException::notFound);
        var lines=jdbc.sql("""
                SELECT numero,tipo,descripcion,cantidad,precio_unitario FROM renglon
                WHERE presupuesto_id=:id ORDER BY numero
                """).param("id",id).query((r,n)->{
            var quantity=r.getBigDecimal("cantidad");var price=r.getBigDecimal("precio_unitario");
            return new Renglon(r.getInt("numero"),r.getString("tipo"),r.getString("descripcion"),quantity,price,
                    quantity.multiply(price).setScale(2,java.math.RoundingMode.HALF_UP));
        }).list();
        return new Version(rows.id(),rows.numero(),rows.autor(),rows.publicado(),rows.vence(),rows.total(),
                rows.decision(),rows.decisor(),rows.decidido(),lines);
    }
    @Transactional(readOnly=true)
    public Documento presupuesto(CuentaPrincipal actor,long ordenId,long presupuestoId){
        var order=order(actor,ordenId);return new Documento(order,version(ordenId,presupuestoId),clock.instant(),false);
    }
    @Transactional(readOnly=true)
    public Documento resumen(CuentaPrincipal actor,long ordenId){
        var order=order(actor,ordenId);
        if(!List.of("FINALIZADO","ENTREGADO").contains(order.estado())||order.autorizadoId()==null||order.ingresoFinal()==null)
            throw new NegocioException(HttpStatus.CONFLICT,"RESUMEN_NO_DISPONIBLE","El resumen está disponible desde la finalización del servicio.");
        return new Documento(order,version(ordenId,order.autorizadoId()),clock.instant(),true);
    }
}
