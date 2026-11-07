package ar.com.taller.operacion;

import static ar.com.taller.operacion.Modelos.*;
import java.net.URI;
import java.util.List;
import ar.com.taller.acceso.CuentaPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class TallerController {
    private final TallerService service;
    public TallerController(TallerService service) {this.service=service;}

    @GetMapping("/api/catalogos/clientes")
    public List<CuentaOpcion> clientes(){return service.catalogo("CLIENTE");}
    @GetMapping("/api/catalogos/personal")
    public List<CuentaOpcion> personal(){return service.catalogo("PERSONAL");}

    @PostMapping("/api/vehiculos")
    public ResponseEntity<RegistroVehiculo> registrar(@AuthenticationPrincipal CuentaPrincipal actor,
            @Valid @RequestBody PedidoVehiculo request) {
        var result=service.registrar(actor,request);
        return ResponseEntity.status(result.creado()?201:200)
                .location(URI.create("/api/vehiculos/"+result.vehiculo().id())).body(result);
    }
    @GetMapping("/api/vehiculos")
    public Pagina<VehiculoView> vehiculos(@AuthenticationPrincipal CuentaPrincipal actor,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Long clienteId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return service.vehiculos(actor,q,clienteId,page,size);
    }
    @GetMapping("/api/vehiculos/{id}")
    public VehiculoView vehiculo(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id) {
        return service.vehiculo(actor,id);
    }
    @PostMapping("/api/ordenes")
    public ResponseEntity<OrdenView> abrir(@AuthenticationPrincipal CuentaPrincipal actor,@Valid @RequestBody PedidoOrden request) {
        var result=service.abrir(actor,request);
        return ResponseEntity.created(URI.create("/api/ordenes/"+result.id())).body(result);
    }
    @GetMapping("/api/ordenes")
    public Pagina<OrdenView> ordenes(@AuthenticationPrincipal CuentaPrincipal actor,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String estado,@RequestParam(required=false) Long clienteId,
            @RequestParam(required=false) Long vehiculoId,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return service.ordenes(actor,q,estado,clienteId,vehiculoId,page,size);
    }
    @GetMapping("/api/ordenes/{id}")
    public OrdenView orden(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id) {
        return service.orden(actor,id);
    }
}
