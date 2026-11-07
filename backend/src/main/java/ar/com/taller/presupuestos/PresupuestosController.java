package ar.com.taller.presupuestos;

import static ar.com.taller.presupuestos.ModelosPresupuesto.*;
import java.net.URI;
import ar.com.taller.acceso.CuentaPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class PresupuestosController {
    private final PresupuestosService service;
    public PresupuestosController(PresupuestosService service){this.service=service;}
    @GetMapping("/api/ordenes/{id}/presupuestos")
    public Contexto contexto(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id){return service.contexto(actor,id);}
    @GetMapping("/api/ordenes/{id}/presupuestos/{presupuestoId}")
    public PresupuestoView version(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@PathVariable long presupuestoId){return service.presupuesto(actor,id,presupuestoId);}
    @PostMapping("/api/ordenes/{id}/diagnostico")
    public Contexto diagnostico(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@Valid @RequestBody DiagnosticoRequest request){return service.diagnosticar(actor,id,request);}
    @PostMapping("/api/ordenes/{id}/presupuestos/previsualizacion")
    public Previsualizacion preview(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@Valid @RequestBody PublicacionRequest request){return service.previsualizar(actor,id,request);}
    @PostMapping("/api/ordenes/{id}/presupuestos")
    public ResponseEntity<Contexto> publicar(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@Valid @RequestBody PublicacionRequest request){
        var result=service.publicar(actor,id,request);
        return ResponseEntity.created(URI.create("/api/ordenes/"+id+"/presupuestos/"+result.presupuestos().getFirst().id())).body(result);
    }
    @PostMapping("/api/ordenes/{id}/presupuestos/{presupuestoId}/decision")
    public Contexto decidir(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@PathVariable long presupuestoId,@Valid @RequestBody DecisionRequest request){return service.decidir(actor,id,presupuestoId,request);}
}
