package ar.com.taller.acceso;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;

public final class ApiErrors {
    private ApiErrors() {}

    public static void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        // Only server-defined constants are accepted here; no rejected input is echoed.
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status +
                ",\"code\":\"" + code + "\",\"title\":\"" + title + "\"}");
    }
}
