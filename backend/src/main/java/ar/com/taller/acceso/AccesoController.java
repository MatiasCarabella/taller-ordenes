package ar.com.taller.acceso;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication
public class AccesoController {
    private final AuthenticationManager manager;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    private final SessionAuthenticationStrategy sessions;
    private final IntentosLogin attempts;
    private final CuentaRepository accounts;
    private final Clock clock;

    public AccesoController(AuthenticationManager manager, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf, SessionAuthenticationStrategy sessions, IntentosLogin attempts,
            CuentaRepository accounts, Clock clock) {
        this.manager = manager; this.contexts = contexts; this.csrf = csrf; this.sessions = sessions;
        this.attempts = attempts; this.accounts = accounts; this.clock = clock;
    }

    @GetMapping("/api/auth/csrf")
    public CsrfView csrf(CsrfToken token) { return new CsrfView(token.getHeaderName(), token.getToken()); }

    @PostMapping("/api/auth/login")
    public SesionView login(@Valid @RequestBody LoginRequest data, HttpServletRequest request,
            HttpServletResponse response) {
        String email = data.correo().strip().toLowerCase(Locale.ROOT);
        try (var permit = attempts.begin(email, request.getRemoteAddr())) {
            Authentication auth;
            try {
                if (data.contrasena().codePointCount(0, data.contrasena().length()) > 128)
                    throw new BadCredentialsException("Credenciales inválidas");
                auth = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, data.contrasena()));
            } catch (AuthenticationServiceException ex) {
                throw new DataAccessResourceFailureException("Servicio de acceso no disponible", ex);
            } catch (AuthenticationException ex) {
                permit.failed();
                throw new BadCredentialsException("Credenciales inválidas");
            }
            sessions.onAuthentication(auth, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            var session = request.getSession(true);
            session.setAttribute(SessionGuardFilter.START, clock.instant());
            session.setAttribute(SessionGuardFilter.LAST, clock.instant());
            contexts.saveContext(context, request, response);
            permit.succeeded();
            return view(auth, request);
        }
    }

    @GetMapping({"/api/auth/me", "/api/administracion/inicio", "/api/personal/inicio", "/api/cliente/inicio"})
    public SesionView current(Authentication auth, HttpServletRequest request) { return view(auth, request); }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        new CsrfLogoutHandler(csrf).logout(request, response, auth);
        new SecurityContextLogoutHandler().logout(request, response, auth);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("JSESSIONID", "")
                .path("/").httpOnly(true).secure(request.isSecure()).sameSite("Lax").maxAge(0).build().toString());
        return ResponseEntity.noContent().build();
    }

    private SesionView view(Authentication auth, HttpServletRequest request) {
        var principal = (CuentaPrincipal) auth.getPrincipal();
        var account = accounts.findState(principal.id()).orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));
        var session = request.getSession(false);
        return new SesionView(new UsuarioView(account.id(), account.nombre(), account.correo(), account.perfil()),
                (Instant) session.getAttribute(SessionGuardFilter.START),
                ((Instant) session.getAttribute(SessionGuardFilter.START)).plus(SessionGuardFilter.ABSOLUTE),
                ((Instant) session.getAttribute(SessionGuardFilter.LAST)).plus(SessionGuardFilter.IDLE));
    }

    public record LoginRequest(@NotBlank @Email @Size(max=255) String correo,
            @NotNull @Size(min=1,max=256) String contrasena) {}
    public record CsrfView(String headerName, String token) {}
    public record UsuarioView(long id, String nombre, String correo, String perfil) {}
    public record SesionView(UsuarioView usuario, Instant iniciadaEn, Instant limiteAbsoluto, Instant vencePorInactividadEn) {}
}
