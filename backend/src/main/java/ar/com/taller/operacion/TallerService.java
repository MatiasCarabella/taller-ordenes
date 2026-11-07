package ar.com.taller.operacion;

import static ar.com.taller.operacion.Modelos.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import ar.com.taller.acceso.CuentaPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TallerService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private static final String VEHICULO_SELECT="""
            SELECT v.id,v.cliente_id,c.nombre AS cliente_nombre,c.activo AS cliente_activo,
                v.dominio,v.marca,v.modelo FROM vehiculo v JOIN usuario c ON c.id=v.cliente_id
            """;
    private static final String ORDEN_FROM="""
            FROM orden o JOIN vehiculo v ON v.id=o.vehiculo_id
            JOIN usuario c ON c.id=o.cliente_id JOIN usuario p ON p.id=o.responsable_id
            """;
    private static final String ORDEN_SELECT="""
            SELECT o.id,o.vehiculo_id,v.dominio,v.marca,v.modelo,o.cliente_id,
                c.nombre AS cliente_nombre,o.responsable_id,p.nombre AS responsable_nombre,
                o.motivo_ingreso,o.estado,o.recibido_en,o.actualizado_en
            """+ORDEN_FROM;
    private static final Set<String> ESTADOS=Set.of("RECIBIDO","DIAGNOSTICADO","PENDIENTE_APROBACION",
            "APROBADO","EN_REPARACION","FINALIZADO","ENTREGADO","RECHAZADO","CANCELADO");
    private static final RowMapper<VehiculoView> VEHICULO=(r,n)->new VehiculoView(r.getLong("id"),
            r.getLong("cliente_id"),r.getString("cliente_nombre"),r.getBoolean("cliente_activo"),
            r.getString("dominio"),r.getString("marca"),r.getString("modelo"));
    private static final RowMapper<OrdenView> ORDEN=(r,n)->new OrdenView(r.getLong("id"),r.getLong("vehiculo_id"),
            r.getString("dominio"),r.getString("marca"),r.getString("modelo"),r.getLong("cliente_id"),
            r.getString("cliente_nombre"),r.getLong("responsable_id"),r.getString("responsable_nombre"),
            r.getString("motivo_ingreso"),r.getString("estado"),utc(r,"recibido_en"),utc(r,"actualizado_en"));

    public TallerService(JdbcClient jdbc,TransactionTemplate tx,Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.clock=clock;
    }
    private static String utc(ResultSet result,String field) throws SQLException {
        return result.getObject(field,LocalDateTime.class).toInstant(ZoneOffset.UTC).toString();
    }
    private static NegocioException invalid(String code,String message) {
        return new NegocioException(HttpStatus.BAD_REQUEST,code,message);
    }
    private void personal(CuentaPrincipal actor) {
        if(!actor.perfil().equals("PERSONAL")) throw new NegocioException(HttpStatus.FORBIDDEN,
                "ACCESO_DENEGADO","Esta acción corresponde al personal del taller.");
    }
    private void activeUser(long id,String profile) {
        var valid=jdbc.sql("SELECT id FROM usuario WHERE id=:id AND perfil=:perfil AND activo=TRUE FOR SHARE")
                .param("id",id).param("perfil",profile).query(Long.class).optional();
        if(valid.isEmpty()) throw invalid(profile.equals("CLIENTE")?"CLIENTE_INVALIDO":"RESPONSABLE_INVALIDO",
                profile.equals("CLIENTE")?"Seleccioná un cliente activo.":"Seleccioná una persona activa del taller.");
    }
    private long owner(CuentaPrincipal actor,Long requested) {
        if(requested!=null && requested<1) throw invalid("FILTRO_INVALIDO","Revisá el cliente del filtro.");
        if(actor.perfil().equals("CLIENTE")) {
            if(requested!=null && requested!=actor.id()) throw new NegocioException(HttpStatus.FORBIDDEN,
                    "ACCESO_DENEGADO","Solo podés consultar tus propios registros.");
            return actor.id();
        }
        return requested==null?0:requested;
    }
    private static String search(String q,int page,int size) {
        if(page<0||size<1||size>50||q==null||q.length()>100) throw invalid("FILTRO_INVALIDO","Revisá los filtros de búsqueda.");
        return q.strip();
    }
    private static String like(String q) { return "%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }

    @Transactional(readOnly=true)
    public List<CuentaOpcion> catalogo(String profile) {
        return jdbc.sql("SELECT id,nombre,correo FROM usuario WHERE perfil=:perfil AND activo=TRUE ORDER BY nombre,id")
                .param("perfil",profile).query(CuentaOpcion.class).list();
    }

    // The failed INSERT transaction is rolled back before reading the winning row.
    // The second transaction can therefore see a concurrent registration under REPEATABLE READ.
    public RegistroVehiculo registrar(CuentaPrincipal actor,PedidoVehiculo request) {
        personal(actor);
        try {
            return tx.execute(status->{
                activeUser(request.clienteId(),"CLIENTE");
                var keys=new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO vehiculo(cliente_id,dominio,marca,modelo) VALUES (:cliente,:dominio,:marca,:modelo)")
                        .param("cliente",request.clienteId()).param("dominio",request.dominio())
                        .param("marca",request.marca()).param("modelo",request.modelo()).update(keys,"id");
                return new RegistroVehiculo(vehiculo(actor,Objects.requireNonNull(keys.getKey()).longValue()),true);
            });
        } catch(DuplicateKeyException ex) {
            return tx.execute(status->new RegistroVehiculo(jdbc.sql(VEHICULO_SELECT+" WHERE v.dominio=:dominio")
                    .param("dominio",request.dominio()).query(VEHICULO).optional().orElseThrow(()->ex),false));
        }
    }

    @Transactional(readOnly=true)
    public VehiculoView vehiculo(CuentaPrincipal actor,long id) {
        if(id<1) throw NegocioException.notFound();
        var result=jdbc.sql(VEHICULO_SELECT+" WHERE v.id=:id")
                .param("id",id).query(VEHICULO).optional().orElseThrow(NegocioException::notFound);
        if(actor.perfil().equals("CLIENTE")&&result.clienteId()!=actor.id()) throw NegocioException.notFound();
        return result;
    }

    @Transactional(readOnly=true)
    public Pagina<VehiculoView> vehiculos(CuentaPrincipal actor,String q,Long clienteId,int page,int size) {
        q=search(q,page,size);
        long owner=owner(actor,clienteId);
        var params=new HashMap<String,Object>();
        params.put("q",like(q));params.put("dominio",like(normalizarDominio(q)));
        String where=" WHERE (v.dominio LIKE :dominio ESCAPE '!' OR v.marca LIKE :q ESCAPE '!' OR v.modelo LIKE :q ESCAPE '!' OR c.nombre LIKE :q ESCAPE '!')";
        if(owner!=0) {where+=" AND v.cliente_id=:cliente";params.put("cliente",owner);}
        long total=jdbc.sql("SELECT COUNT(*) FROM vehiculo v JOIN usuario c ON c.id=v.cliente_id"+where).params(params).query(Long.class).single();
        params.put("limit",size);params.put("offset",(long)page*size);
        var rows=jdbc.sql(VEHICULO_SELECT+where+" ORDER BY v.dominio,v.id LIMIT :limit OFFSET :offset")
                .params(params).query(VEHICULO).list();
        return new Pagina<>(rows,page,size,total);
    }

    public OrdenView abrir(CuentaPrincipal actor,PedidoOrden request) {
        personal(actor);
        return tx.execute(status->{
            long cliente=jdbc.sql("SELECT cliente_id FROM vehiculo WHERE id=:id FOR SHARE")
                    .param("id",request.vehiculoId()).query(Long.class).optional().orElseThrow(NegocioException::notFound);
            if(request.clienteId()!=null&&request.clienteId()!=cliente)
                throw new NegocioException(HttpStatus.CONFLICT,"TITULAR_NO_COINCIDE","El cliente debe ser el titular del vehículo.");
            activeUser(cliente,"CLIENTE");
            activeUser(request.responsableId(),"PERSONAL");
            var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
            var keys=new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO orden(cliente_id,vehiculo_id,responsable_id,motivo_ingreso,estado,recibido_en,actualizado_en)
                    VALUES (:cliente,:vehiculo,:responsable,:motivo,'RECIBIDO',:fecha,:fecha)
                    """).param("cliente",cliente).param("vehiculo",request.vehiculoId()).param("responsable",request.responsableId())
                    .param("motivo",request.motivoIngreso()).param("fecha",now).update(keys,"id");
            return orden(actor,Objects.requireNonNull(keys.getKey()).longValue());
        });
    }

    @Transactional(readOnly=true)
    public OrdenView orden(CuentaPrincipal actor,long id) {
        if(id<1) throw NegocioException.notFound();
        var result=jdbc.sql(ORDEN_SELECT+" WHERE o.id=:id").param("id",id).query(ORDEN).optional().orElseThrow(NegocioException::notFound);
        if(actor.perfil().equals("CLIENTE")&&result.clienteId()!=actor.id()) throw NegocioException.notFound();
        return result;
    }

    @Transactional(readOnly=true)
    public Pagina<OrdenView> ordenes(CuentaPrincipal actor,String q,String estado,Long clienteId,Long vehiculoId,int page,int size) {
        q=search(q,page,size);
        if(!estado.isEmpty()&&!ESTADOS.contains(estado)) throw invalid("ESTADO_INVALIDO","Seleccioná un estado válido.");
        long owner=owner(actor,clienteId);
        if(vehiculoId!=null) vehiculo(actor,vehiculoId);
        var params=new HashMap<String,Object>();
        params.put("q",like(q));params.put("dominio",like(normalizarDominio(q)));
        String where=" WHERE (v.dominio LIKE :dominio ESCAPE '!' OR c.nombre LIKE :q ESCAPE '!' OR o.motivo_ingreso LIKE :q ESCAPE '!')";
        if(owner!=0){where+=" AND o.cliente_id=:cliente";params.put("cliente",owner);}
        if(vehiculoId!=null){where+=" AND o.vehiculo_id=:vehiculo";params.put("vehiculo",vehiculoId);}
        if(!estado.isEmpty()){where+=" AND o.estado=:estado";params.put("estado",estado);}
        long total=jdbc.sql("SELECT COUNT(*) "+ORDEN_FROM+where).params(params).query(Long.class).single();
        params.put("limit",size);params.put("offset",(long)page*size);
        var rows=jdbc.sql(ORDEN_SELECT+where+" ORDER BY o.recibido_en DESC,o.id DESC LIMIT :limit OFFSET :offset")
                .params(params).query(ORDEN).list();
        return new Pagina<>(rows,page,size,total);
    }
}
