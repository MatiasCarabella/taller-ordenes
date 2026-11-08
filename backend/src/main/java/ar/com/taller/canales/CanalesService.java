package ar.com.taller.canales;

import static ar.com.taller.canales.ModelosCanales.*;
import ar.com.taller.acceso.CuentaPrincipal;
import ar.com.taller.operacion.NegocioException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CanalesService {
    private final JdbcClient jdbc;private final TransactionTemplate tx;private final Clock clock;
    private final Transportes.Correo correo;private final Transportes.Telegram telegram;
    private final SecureRandom random=new SecureRandom();
    private final Map<Long,Instant> ultimoCorreo=new ConcurrentHashMap<>();
    private record Usuario(long id,String correo,boolean confirmado,String chat,String hash,String tipo,Instant vence){}
    private record Emision(String codigo,String direccion){}
    public CanalesService(JdbcClient jdbc,TransactionTemplate tx,Clock clock,Transportes.Correo correo,Transportes.Telegram telegram){
        this.jdbc=jdbc;this.tx=tx;this.clock=clock;this.correo=correo;this.telegram=telegram;
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MILLIS);}
    private static void cliente(CuentaPrincipal a){
        if(!a.perfil().equals("CLIENTE"))throw new NegocioException(HttpStatus.FORBIDDEN,"ACCESO_DENEGADO","Los canales se gestionan desde la cuenta del cliente.");
    }
    private static Usuario user(ResultSet r)throws SQLException{
        var expiry=r.getObject("token_canal_vence",LocalDateTime.class);
        return new Usuario(r.getLong("id"),r.getString("correo"),r.getBoolean("correo_confirmado"),r.getString("chat_telegram"),
                r.getString("token_canal_hash"),r.getString("token_canal_tipo"),expiry==null?null:expiry.toInstant(ZoneOffset.UTC));
    }
    private Usuario usuario(long id,boolean lock){
        return jdbc.sql("SELECT id,correo,correo_confirmado,chat_telegram,token_canal_hash,token_canal_tipo,token_canal_vence FROM usuario WHERE id=:id AND perfil='CLIENTE' AND activo=TRUE"+(lock?" FOR UPDATE":""))
                .param("id",id).query((r,n)->user(r)).optional().orElseThrow(NegocioException::notFound);
    }
    private EstadoCanales view(Usuario u){
        boolean pending=u.vence()!=null&&now().isBefore(u.vence());
        return new EstadoCanales(u.correo(),u.confirmado(),u.chat()!=null,pending?u.tipo():null,
                pending?u.vence().toString():null,correo.configurado(),telegram.configurado(),telegram.conexion());
    }
    @Transactional(readOnly=true)
    public EstadoCanales estado(CuentaPrincipal a){cliente(a);return view(usuario(a.id(),false));}
    public EstadoCanales cambiarCorreo(CuentaPrincipal a,CambiarCorreo request){
        cliente(a);
        try{return tx.execute(s->{
            var u=usuario(a.id(),true);
            if(!u.correo().equalsIgnoreCase(request.correo()))
                jdbc.sql("UPDATE usuario SET correo=:correo,correo_confirmado=FALSE,token_canal_hash=NULL,token_canal_tipo=NULL,token_canal_vence=NULL WHERE id=:id")
                        .param("id",a.id()).param("correo",request.correo()).update();
            return view(usuario(a.id(),false));
        });}catch(DuplicateKeyException e){throw new NegocioException(HttpStatus.CONFLICT,"CORREO_NO_DISPONIBLE","No se pudo usar esa dirección de correo.");}
    }
    private String token(int length){byte[] value=new byte[length];random.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Instant guardarToken(long id,String tipo,String value){
        Instant expiry=now().plus(Duration.ofMinutes(10));
        jdbc.sql("UPDATE usuario SET token_canal_hash=:hash,token_canal_tipo=:tipo,token_canal_vence=:vence WHERE id=:id")
                .param("id",id).param("hash",hash(value)).param("tipo",tipo).param("vence",LocalDateTime.ofInstant(expiry,ZoneOffset.UTC)).update();
        return expiry;
    }
    private synchronized void reservarCorreo(long id){
        Instant current=now();ultimoCorreo.entrySet().removeIf(e->!current.isBefore(e.getValue().plusSeconds(60)));
        if(ultimoCorreo.containsKey(id))throw new NegocioException(HttpStatus.TOO_MANY_REQUESTS,"CODIGO_SOLICITADO_RECIENTEMENTE","Esperá un minuto antes de solicitar otro código.");
        if(ultimoCorreo.size()>=4096)throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE,"CANAL_OCUPADO","Volvé a intentarlo en un momento.");
        ultimoCorreo.put(id,current);
    }
    public CodigoSolicitado solicitarCodigo(CuentaPrincipal a){
        cliente(a);
        if(!correo.configurado())throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE,"SMTP_NO_CONFIGURADO","El taller todavía no habilitó el envío de correo.");
        var issued=tx.execute(s->{
            var u=usuario(a.id(),true);
            if(u.confirmado())throw new NegocioException(HttpStatus.CONFLICT,"CORREO_YA_CONFIRMADO","Este correo ya está confirmado.");
            reservarCorreo(a.id());
            String code=token(16);guardarToken(a.id(),"CORREO",code);return new Emision(code,u.correo());
        });
        ResultadoCanal result;
        try{
            correo.enviar(issued.direccion(),"Taller - Código de confirmación","Tu código de confirmación es:\n\n"+issued.codigo()+"\n\nVence en diez minutos y se usa una sola vez. Ingresalo en Mi cuenta del portal. Si generás otro código o enlace de Telegram, este código deja de ser válido.");
            result=new ResultadoCanal("CORREO","ENVIADO",true,"El proveedor aceptó el correo con el código.");
        }catch(RuntimeException e){result=new ResultadoCanal("CORREO","FALLO",true,"No se pudo confirmar el envío del código. Revisá el correo antes de pedir otro.");}
        return new CodigoSolicitado(estado(a),result);
    }
    public EstadoCanales confirmarCorreo(CuentaPrincipal a,ConfirmarCorreo request){
        cliente(a);return tx.execute(s->{
            var u=usuario(a.id(),true);
            if(!"CORREO".equals(u.tipo())||u.vence()==null||!now().isBefore(u.vence())||u.hash()==null||
                    !MessageDigest.isEqual(u.hash().getBytes(StandardCharsets.US_ASCII),hash(request.codigo()).getBytes(StandardCharsets.US_ASCII)))
                throw new NegocioException(HttpStatus.BAD_REQUEST,"CODIGO_INVALIDO","El código no es válido o venció. Solicitá uno nuevo si hace falta.");
            jdbc.sql("UPDATE usuario SET correo_confirmado=TRUE,token_canal_hash=NULL,token_canal_tipo=NULL,token_canal_vence=NULL WHERE id=:id").param("id",a.id()).update();
            return view(usuario(a.id(),false));
        });
    }
    public EnlaceTelegram enlace(CuentaPrincipal a){
        cliente(a);
        if(!telegram.configurado())throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE,"TELEGRAM_NO_CONFIGURADO","El taller todavía no habilitó Telegram.");
        if(!"CONECTADO".equals(telegram.conexion()))throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE,"TELEGRAM_NO_DISPONIBLE","No se pudo comprobar la conexión del bot. Actualizá el estado y volvé a intentarlo.");
        return tx.execute(s->{
            usuario(a.id(),true);String value=token(32);Instant expiry=guardarToken(a.id(),"TELEGRAM",value);
            return new EnlaceTelegram("https://t.me/"+telegram.usuario()+"?start="+value,expiry.toString(),view(usuario(a.id(),false)));
        });
    }
    public EstadoCanales desvincular(CuentaPrincipal a){
        cliente(a);return tx.execute(s->{
            usuario(a.id(),true);
            jdbc.sql("UPDATE usuario SET chat_telegram=NULL,token_canal_hash=NULL,token_canal_tipo=NULL,token_canal_vence=NULL WHERE id=:id").param("id",a.id()).update();
            return view(usuario(a.id(),false));
        });
    }
    public Vinculacion vincular(String value,Transportes.Actualizacion message){
        if(!"private".equals(message.tipoChat())||message.chatId()<1||message.chatId()!=message.emisorId()||message.bot()||!value.matches("[A-Za-z0-9_-]{43}"))
            return new Vinculacion(false,"La vinculación requiere un enlace válido abierto en un chat privado.");
        try{return tx.execute(s->{
            String digest=hash(value);
            var u=jdbc.sql("SELECT id,correo,correo_confirmado,chat_telegram,token_canal_hash,token_canal_tipo,token_canal_vence FROM usuario WHERE token_canal_hash=:hash AND token_canal_tipo='TELEGRAM' AND perfil='CLIENTE' AND activo=TRUE FOR UPDATE")
                    .param("hash",digest).query((r,n)->user(r)).optional();
            if(u.isEmpty()||u.get().vence()==null||!now().isBefore(u.get().vence()))
                return new Vinculacion(false,"El enlace no es válido o venció. Generá uno nuevo desde Mi cuenta.");
            jdbc.sql("UPDATE usuario SET chat_telegram=:chat,token_canal_hash=NULL,token_canal_tipo=NULL,token_canal_vence=NULL WHERE id=:id")
                    .param("id",u.get().id()).param("chat",Long.toString(message.chatId())).update();
            return new Vinculacion(true,"Telegram quedó vinculado. Los presupuestos se responden desde el portal.");
        });}catch(DuplicateKeyException e){return new Vinculacion(false,"Este chat ya está vinculado. Desvinculalo desde su cuenta antes de usarlo en otra.");}
    }
}
