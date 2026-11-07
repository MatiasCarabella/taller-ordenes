package ar.com.taller.ejecucion;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ModelosEjecucion {
    private ModelosEjecucion() {}
    public record Inicio(@NotNull @Positive Long presupuestoAutorizadoId) {}
    public record Trabajo(@NotBlank @Size(max=4000) String trabajoRealizado,
            @NotNull @Positive Long presupuestoAutorizadoId,@AssertTrue @NotNull Boolean alcanceConfirmado) {
        public Trabajo {if(trabajoRealizado!=null)trabajoRealizado=trabajoRealizado.strip();}
    }
    public record Cierre(@NotBlank @Size(max=4000) String trabajoRealizado,
            @NotNull @Positive Long presupuestoAutorizadoId,@AssertTrue @NotNull Boolean alcanceConfirmado,
            @NotNull @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal costoRepuestosTotal,
            @NotNull @DecimalMin("0") @Digits(integer=6,fraction=2) BigDecimal horasManoObraTotal) {
        public Cierre {if(trabajoRealizado!=null)trabajoRealizado=trabajoRealizado.strip();}
    }
    public record ConfirmarCierre(@NotNull @Valid Cierre datos,@NotBlank @Size(max=200) String confirmacion) {}
    public record Cancelacion(@NotBlank @Size(max=4000) String motivo) {
        public Cancelacion {if(motivo!=null)motivo=motivo.strip();}
    }
    public record NuevaTarifa(@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal tarifaHora) {}
    public record Tarifa(String tarifaHora) {}
    // This shared view deliberately has no internal cost, rate or margin fields.
    public record Ejecucion(long ordenId,String estado,Long presupuestoAutorizadoId,Integer versionAutorizada,
            String trabajoRealizado,String iniciadoEn,String finalizadoEn,String entregadoEn,String canceladoEn,
            String motivoCancelacion,String ingresoFinal,boolean puedeIniciar,boolean puedeGuardarTrabajo,
            boolean puedeCerrar,boolean puedeEntregar,boolean puedeCancelar) {}
    public record VistaCierre(long ordenId,long presupuestoAutorizadoId,int versionAutorizada,String trabajoRealizado,
            String ingresoFinal,String costoRepuestosTotal,String horasManoObraTotal,String tarifaHoraAplicada,
            String costoManoObra,String confirmacion,String venceEn) {}
    public record Resultado(long ordenId,String dominio,String clienteNombre,String estado,String finalizadoEn,
            String ingresoFinal,String costoRepuestosTotal,String horasManoObraTotal,String tarifaHoraAplicada,
            String costoManoObra,String margenDirecto,String margenPorcentual) {}
    public record Totales(long finalizadas,long rechazadas,long canceladas,String ingreso,String costoRepuestos,
            String costoManoObra,String margenDirecto,String margenPorcentual) {}
    public record Reporte(String desde,String hasta,String inicioUtc,String finExclusivoUtc,
            Totales totales,List<Resultado> items,int page,int size) {}
}
