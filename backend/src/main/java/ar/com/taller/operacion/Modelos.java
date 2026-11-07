package ar.com.taller.operacion;

import java.util.List;
import java.util.Locale;
import jakarta.validation.constraints.*;

public final class Modelos {
    private Modelos() {}
    public record CuentaOpcion(long id,String nombre,String correo) {}
    public record VehiculoView(long id,long clienteId,String clienteNombre,boolean clienteActivo,
            String dominio,String marca,String modelo) {}
    public record RegistroVehiculo(VehiculoView vehiculo,boolean creado) {}
    public record OrdenView(long id,long vehiculoId,String dominio,String marca,String modelo,
            long clienteId,String clienteNombre,long responsableId,String responsableNombre,
            String motivoIngreso,String estado,String recibidoEn,String actualizadoEn) {}
    public record Pagina<T>(List<T> items,int page,int size,long total) {}
    public record PedidoVehiculo(@NotNull @Positive Long clienteId,
            @NotBlank @Pattern(regexp="[A-Z0-9]{1,15}",message="Usá hasta 15 letras o números.") String dominio,
            @NotBlank @Size(max=80) String marca,@NotBlank @Size(max=80) String modelo) {
        public PedidoVehiculo {
            if(dominio!=null) dominio=normalizarDominio(dominio);
            if(marca!=null) marca=marca.strip();
            if(modelo!=null) modelo=modelo.strip();
        }
    }
    public record PedidoOrden(@NotNull @Positive Long vehiculoId,@NotNull @Positive Long responsableId,
            @NotBlank @Size(max=4000) String motivoIngreso,@Positive Long clienteId) {
        public PedidoOrden { if(motivoIngreso!=null) motivoIngreso=motivoIngreso.strip(); }
    }
    public static String normalizarDominio(String value) {
        return value.replaceAll("[\\s\\p{Z}-]+","").toUpperCase(Locale.ROOT);
    }
}
