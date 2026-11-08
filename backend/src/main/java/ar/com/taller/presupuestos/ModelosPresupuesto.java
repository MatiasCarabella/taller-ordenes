package ar.com.taller.presupuestos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class ModelosPresupuesto {
    private ModelosPresupuesto() {}
    public record DiagnosticoRequest(@NotBlank @Size(max=4000) String diagnostico) {
        public DiagnosticoRequest {if(diagnostico!=null)diagnostico=diagnostico.strip();}
    }
    public record RenglonRequest(@NotBlank @Pattern(regexp="REPUESTO|MANO_OBRA") String tipo,
            @NotBlank @Size(max=255) String descripcion,
            @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=6,fraction=2) BigDecimal cantidad,
            @NotNull @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal precioUnitario) {
        public RenglonRequest {if(descripcion!=null)descripcion=descripcion.strip();}
    }
    public record PublicacionRequest(@NotNull @Min(0) Integer ultimaVersionEsperada,
            @Positive Long presupuestoAutorizadoEsperadoId,@NotNull Instant venceEn,
            @NotEmpty @Size(max=100) List<@NotNull @Valid RenglonRequest> renglones) {}
    public record DecisionRequest(@NotBlank @Pattern(regexp="ACEPTADO|RECHAZADO|REVISION") String decision) {}
    public record RenglonView(int numero,String tipo,String descripcion,String cantidad,String precioUnitario,String subtotal) {}
    public record PresupuestoView(long id,int version,String autorNombre,String publicadoEn,String venceEn,String total,
            String decision,String decisorNombre,String decididoEn,boolean ultimaVersion,boolean autorizado,
            boolean respuestaVencida,boolean puedeResponder,List<RenglonView> renglones) {}
    public record Contexto(long ordenId,String estado,String diagnostico,String diagnosticoAutor,String diagnosticadoEn,
            int ultimaVersion,Long presupuestoAutorizadoId,boolean puedeEditarDiagnostico,boolean puedePublicar,
            String ahoraEn,List<PresupuestoView> presupuestos,ar.com.taller.canales.ModelosCanales.Informe avisos) {
        public Contexto(long ordenId,String estado,String diagnostico,String diagnosticoAutor,String diagnosticadoEn,
            int ultimaVersion,Long presupuestoAutorizadoId,boolean puedeEditarDiagnostico,boolean puedePublicar,
            String ahoraEn,List<PresupuestoView> presupuestos){this(ordenId,estado,diagnostico,diagnosticoAutor,diagnosticadoEn,ultimaVersion,presupuestoAutorizadoId,puedeEditarDiagnostico,puedePublicar,ahoraEn,presupuestos,null);}
        public Contexto conAvisos(ar.com.taller.canales.ModelosCanales.Informe informe){return new Contexto(ordenId,estado,diagnostico,diagnosticoAutor,diagnosticadoEn,ultimaVersion,presupuestoAutorizadoId,puedeEditarDiagnostico,puedePublicar,ahoraEn,presupuestos,informe);}
    }
    public record Previsualizacion(String venceEn,String total,List<RenglonView> renglones) {}
}
