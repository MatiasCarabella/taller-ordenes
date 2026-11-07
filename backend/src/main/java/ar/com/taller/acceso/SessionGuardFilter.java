package ar.com.taller.acceso;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionGuardFilter extends OncePerRequestFilter {
    public static final String START = "taller.session.start";
    public static final String LAST = "taller.session.last";
    public static final Duration IDLE = Duration.ofMinutes(20);
    public static final Duration ABSOLUTE = Duration.ofHours(8);
    private final CuentaRepository accounts;
    private final Clock clock;

    public SessionGuardFilter(CuentaRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().equals("/api/health");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CuentaPrincipal principal) {
            var session = request.getSession(false);
            var start = session == null ? null : session.getAttribute(START);
            var last = session == null ? null : session.getAttribute(LAST);
            Instant now = clock.instant();
            boolean expired = !(start instanceof Instant s && last instanceof Instant l)
                    || !now.isBefore(((Instant) start).plus(ABSOLUTE))
                    || !now.isBefore(((Instant) last).plus(IDLE));
            if (expired) {
                invalidate(request);
                ApiErrors.write(response, 401, "SESION_VENCIDA", "Tu sesión terminó. Volvé a ingresar.");
                return;
            }
            try {
                var current = accounts.findState(principal.id());
                if (current.isEmpty() || !current.get().activo()
                        || !current.get().perfil().equals(principal.perfil())
                        || !current.get().credencial().equals(principal.credencial())) {
                    invalidate(request);
                    ApiErrors.write(response, 401, "SESION_INVALIDA", "Tu sesión terminó. Volvé a ingresar.");
                    return;
                }
            } catch (DataAccessException ex) {
                ApiErrors.write(response, 503, "SERVICIO_NO_DISPONIBLE", "No se pudo acceder al servicio. Intentá nuevamente.");
                return;
            }
            session.setAttribute(LAST, now);
        }
        chain.doFilter(request, response);
    }

    private void invalidate(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
