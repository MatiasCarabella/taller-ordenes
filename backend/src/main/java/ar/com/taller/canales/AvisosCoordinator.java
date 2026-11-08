package ar.com.taller.canales;

import static ar.com.taller.canales.ModelosCanales.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class AvisosCoordinator {
    private final JdbcClient jdbc;private final Transportes.Correo correo;private final Transportes.Telegram telegram;private final CanalesConfig config;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(16);
    private final Duration espera;
    private record Destino(String correo,boolean confirmado,String chat){}
    @Autowired public AvisosCoordinator(JdbcClient jdbc,Transportes.Correo correo,Transportes.Telegram telegram,CanalesConfig config){this(jdbc,correo,telegram,config,Duration.ofSeconds(8));}
    AvisosCoordinator(JdbcClient jdbc,Transportes.Correo correo,Transportes.Telegram telegram,CanalesConfig config,Duration espera){
        this.jdbc=jdbc;this.correo=correo;this.telegram=telegram;this.config=config;this.espera=espera;
    }
    private static ResultadoCanal result(String canal,String estado,boolean intento,String mensaje){return new ResultadoCanal(canal,estado,intento,mensaje);}
    private Future<ResultadoCanal> enviar(String canal,boolean vinculado,boolean configurado,Runnable send){
        if(!vinculado)return CompletableFuture.completedFuture(result(canal,"SIN_CANAL",false,"El cliente no tiene este canal confirmado."));
        if(!configurado)return CompletableFuture.completedFuture(result(canal,"NO_CONFIGURADO",false,"El taller no habilitó este proveedor."));
        if(!slots.tryAcquire())return CompletableFuture.completedFuture(result(canal,"OCUPADO",false,"No se inició el aviso porque el servicio está ocupado."));
        try{return executor.submit(()->{
            try{send.run();return result(canal,"ENVIADO",true,"El proveedor aceptó el aviso.");}
            catch(RuntimeException e){return result(canal,"FALLO",true,"No se pudo confirmar el envío. La operación quedó guardada.");}
            finally{slots.release();}
        });}catch(RejectedExecutionException e){slots.release();return CompletableFuture.completedFuture(result(canal,"OCUPADO",false,"No se pudo iniciar el aviso."));}
    }
    private ResultadoCanal await(Future<ResultadoCanal> pending,String canal,long deadline){
        try{
            if(pending.isDone())return pending.get();
            return pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return result(canal,"FALLO",true,"Terminó el tiempo de espera sin confirmación del proveedor. La operación quedó guardada.");}
    }
    public Informe enviar(Evento event){
        if(event==null)return null;
        try{
            var destination=jdbc.sql("SELECT correo,correo_confirmado,chat_telegram FROM usuario WHERE id=:id AND perfil='CLIENTE' AND activo=TRUE")
                    .param("id",event.clienteId()).query((r,n)->new Destino(r.getString("correo"),r.getBoolean("correo_confirmado"),r.getString("chat_telegram"))).optional();
            if(destination.isEmpty())return new Informe(List.of(result("CORREO","SIN_CANAL",false,"No hay un destinatario activo."),result("TELEGRAM","SIN_CANAL",false,"No hay un destinatario activo.")));
            var d=destination.get();
            String message="Orden #"+event.ordenId()+": "+label(event.tipo())+".\nConsultá la información actual en el portal: "+config.portal+"/ordenes/"+event.ordenId()+"\nLas decisiones de presupuestos se realizan con sesión iniciada en el portal.";
            long deadline=System.nanoTime()+espera.toNanos();
            var mail=enviar("CORREO",d.confirmado(),correo.configurado(),()->correo.enviar(d.correo(),"Taller - Actualización de orden",message));
            var bot=enviar("TELEGRAM",d.chat()!=null,telegram.configurado(),()->telegram.enviar(d.chat(),message));
            return new Informe(List.of(await(mail,"CORREO",deadline),await(bot,"TELEGRAM",deadline)));
        }catch(RuntimeException e){return new Informe(List.of(result("CORREO","FALLO",false,"La operación quedó guardada; no se pudieron consultar los canales."),result("TELEGRAM","FALLO",false,"La operación quedó guardada; no se pudieron consultar los canales.")));}
    }
    private static String label(String type){return switch(type){
        case "PRESUPUESTO_PUBLICADO"->"hay una nueva versión del presupuesto";
        case "APROBADO"->"el servicio está aprobado";
        case "EN_REPARACION"->"se inició la reparación";
        case "FINALIZADO"->"el servicio está finalizado";
        case "ENTREGADO"->"se registró la entrega";
        case "RECHAZADO"->"el presupuesto inicial fue rechazado";
        case "CANCELADO"->"la orden fue cancelada";
        default->throw new IllegalArgumentException("Evento no permitido");
    };}
    @PreDestroy public void close(){executor.shutdownNow();}
}
