package ar.com.taller.canales;

import static ar.com.taller.canales.ModelosCanales.*;
import ar.com.taller.acceso.CuentaPrincipal;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class CanalesController {
    private final CanalesService service;
    public CanalesController(CanalesService service){this.service=service;}
    @GetMapping("/api/canales")
    public EstadoCanales estado(@AuthenticationPrincipal CuentaPrincipal a){return service.estado(a);}
    @PostMapping("/api/canales/correo")
    public EstadoCanales correo(@AuthenticationPrincipal CuentaPrincipal a,@Valid @RequestBody CambiarCorreo r){return service.cambiarCorreo(a,r);}
    @PostMapping("/api/canales/correo/codigo")
    public CodigoSolicitado codigo(@AuthenticationPrincipal CuentaPrincipal a){return service.solicitarCodigo(a);}
    @PostMapping("/api/canales/correo/confirmacion")
    public EstadoCanales confirmar(@AuthenticationPrincipal CuentaPrincipal a,@Valid @RequestBody ConfirmarCorreo r){return service.confirmarCorreo(a,r);}
    @PostMapping("/api/canales/telegram/enlace")
    public EnlaceTelegram enlace(@AuthenticationPrincipal CuentaPrincipal a){return service.enlace(a);}
    @PostMapping("/api/canales/telegram/desvincular")
    public EstadoCanales desvincular(@AuthenticationPrincipal CuentaPrincipal a){return service.desvincular(a);}
}
