package ar.com.taller.operacion;

import org.springframework.http.HttpStatus;

public class NegocioException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public NegocioException(HttpStatus status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static NegocioException notFound() {
        return new NegocioException(HttpStatus.NOT_FOUND,"RECURSO_NO_ENCONTRADO","No se encontró el registro solicitado.");
    }
}
