package ar.com.taller.documentos;

import ar.com.taller.acceso.CuentaPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class DocumentosController {
    private final DocumentosService service;private final PdfDocumentos pdf;
    public DocumentosController(DocumentosService service,PdfDocumentos pdf){this.service=service;this.pdf=pdf;}
    private ResponseEntity<byte[]> response(DocumentosService.Documento data,String name){
        byte[] content=pdf.generar(data);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(content.length)
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name).build().toString())
                .body(content);
    }
    @GetMapping("/api/ordenes/{id}/presupuestos/{presupuestoId}/pdf")
    public ResponseEntity<byte[]> presupuesto(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id,@PathVariable long presupuestoId){
        var data=service.presupuesto(actor,id,presupuestoId);
        return response(data,"presupuesto-orden-"+id+"-v"+data.version().numero()+".pdf");
    }
    @GetMapping("/api/ordenes/{id}/resumen/pdf")
    public ResponseEntity<byte[]> resumen(@AuthenticationPrincipal CuentaPrincipal actor,@PathVariable long id){
        return response(service.resumen(actor,id),"resumen-orden-"+id+".pdf");
    }
}
