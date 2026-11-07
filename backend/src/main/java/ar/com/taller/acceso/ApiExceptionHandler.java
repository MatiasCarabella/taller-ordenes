package ar.com.taller.acceso;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ar.com.taller.operacion.NegocioException.class)
    ResponseEntity<ProblemDetail> business(ar.com.taller.operacion.NegocioException ex) {
        return problem(ex.status(),ex.code(),ex.getMessage(),null);
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> parameter() {
        return problem(HttpStatus.BAD_REQUEST,"FILTRO_INVALIDO","Revisá los filtros de búsqueda.",null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> credentials() {
        return problem(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Correo o contraseña inválidos.", null);
    }

    @ExceptionHandler(IntentosLogin.LimitExceeded.class)
    ResponseEntity<ProblemDetail> limit(IntentosLogin.LimitExceeded exception) {
        var result = problem(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS",
                "Demasiados intentos. Esperá antes de volver a ingresar.", null);
        return ResponseEntity.status(result.getStatusCode()).headers(result.getHeaders())
                .header("Retry-After", Long.toString(exception.seconds())).body(result.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException ex) {
        var fields = ex.getBindingResult().getFieldErrors().stream().map(error ->
                Map.of("field", error.getField(), "message", "Revisá este campo.")).distinct().toList();
        return problem(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", "Revisá los datos ingresados.", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformed() {
        return problem(HttpStatus.BAD_REQUEST, "DATOS_INVALIDOS", "Revisá los datos ingresados.", null);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE",
                "No se pudo acceder al servicio. Intentá nuevamente.", null);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title, List<?> fields) {
        var body = ProblemDetail.forStatus(status);
        body.setTitle(title);
        body.setProperty("code", code);
        if (fields != null) body.setProperty("fieldErrors", fields);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }
}
