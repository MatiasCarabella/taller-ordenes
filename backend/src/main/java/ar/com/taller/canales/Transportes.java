package ar.com.taller.canales;

import java.util.List;

public final class Transportes {
    private Transportes(){}
    public interface Correo {
        boolean configurado();
        void enviar(String destino,String asunto,String texto);
    }
    public interface Telegram {
        boolean configurado();
        String usuario();
        String conexion();
        boolean verificar();
        List<Actualizacion> recibir(Long offset);
        void enviar(String chat,String texto);
    }
    public record Actualizacion(long id,String tipoChat,long chatId,long emisorId,boolean bot,String texto){}
    public static final class Fallo extends RuntimeException {
        public Fallo(){super("El proveedor no confirmó la operación.");}
    }
}
