package ar.com.taller.canales;

import jakarta.validation.constraints.*;
import java.util.List;

public final class ModelosCanales {
    private ModelosCanales(){}
    public record EstadoCanales(String correo,boolean correoConfirmado,boolean telegramVinculado,
            String tokenPendiente,String tokenVenceEn,boolean smtpConfigurado,boolean telegramConfigurado,String conexionTelegram) {}
    public record CambiarCorreo(@NotBlank @Email @Size(max=255) @Pattern(regexp="[^\\s<>]+@[^\\s<>]+") String correo){
        public CambiarCorreo {if(correo!=null)correo=correo.strip().toLowerCase(java.util.Locale.ROOT);}
    }
    public record ConfirmarCorreo(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{22}") String codigo){
        public ConfirmarCorreo{if(codigo!=null)codigo=codigo.strip();}
    }
    public record ResultadoCanal(String canal,String estado,boolean intentado,String mensaje){}
    public record Informe(List<ResultadoCanal> canales){}
    public record CodigoSolicitado(EstadoCanales canales,ResultadoCanal envio){}
    public record EnlaceTelegram(String enlace,String venceEn,EstadoCanales canales){}
    public record Evento(long ordenId,long clienteId,String tipo){}
    public record Operacion<T>(T datos,Evento evento){}
    public record Vinculacion(boolean aceptada,String mensaje){}
}
