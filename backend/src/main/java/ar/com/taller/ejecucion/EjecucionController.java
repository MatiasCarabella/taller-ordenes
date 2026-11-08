package ar.com.taller.ejecucion;

import static ar.com.taller.ejecucion.ModelosEjecucion.*;
import ar.com.taller.acceso.CuentaPrincipal;
import java.time.LocalDate;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class EjecucionController {
    private final EjecucionService service;
    private final ar.com.taller.canales.AvisosCoordinator avisos;
    public EjecucionController(EjecucionService service,ar.com.taller.canales.AvisosCoordinator avisos){this.service=service;this.avisos=avisos;}
    @GetMapping("/api/ordenes/{id}/ejecucion")
    public Ejecucion contexto(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id){return service.contexto(a,id);}
    @PostMapping("/api/ordenes/{id}/iniciar")
    public Ejecucion iniciar(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id,@Valid @RequestBody Inicio r){var operation=service.iniciar(a,id,r);return operation.datos().conAvisos(avisos.enviar(operation.evento()));}
    @PostMapping("/api/ordenes/{id}/trabajo")
    public Ejecucion trabajo(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id,@Valid @RequestBody Trabajo r){return service.trabajo(a,id,r);}
    @PostMapping("/api/ordenes/{id}/cierre/previsualizacion")
    public VistaCierre preview(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id,@Valid @RequestBody Cierre r){return service.previsualizar(a,id,r);}
    @PostMapping("/api/ordenes/{id}/cierre")
    public Ejecucion cerrar(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id,@Valid @RequestBody ConfirmarCierre r){var operation=service.cerrar(a,id,r);return operation.datos().conAvisos(avisos.enviar(operation.evento()));}
    @PostMapping("/api/ordenes/{id}/entrega")
    public Ejecucion entregar(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id){var operation=service.entregar(a,id);return operation.datos().conAvisos(avisos.enviar(operation.evento()));}
    @PostMapping("/api/ordenes/{id}/cancelacion")
    public Ejecucion cancelar(@AuthenticationPrincipal CuentaPrincipal a,@PathVariable long id,@Valid @RequestBody Cancelacion r){var operation=service.cancelar(a,id,r);return operation.datos().conAvisos(avisos.enviar(operation.evento()));}
    @GetMapping("/api/administracion/tarifa")
    public Tarifa tarifa(@AuthenticationPrincipal CuentaPrincipal a){return service.tarifa(a);}
    @PostMapping("/api/administracion/tarifa")
    public Tarifa tarifa(@AuthenticationPrincipal CuentaPrincipal a,@Valid @RequestBody NuevaTarifa r){return service.cambiarTarifa(a,r);}
    @GetMapping("/api/administracion/resultados")
    public Reporte resultados(@AuthenticationPrincipal CuentaPrincipal a,@RequestParam LocalDate desde,@RequestParam LocalDate hasta,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.resultados(a,desde,hasta,page,size);}
}
