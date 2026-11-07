package ar.com.taller.cuentas;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {
    private final Set<String> common;

    public PasswordPolicy() {
        var resource = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt");
        if (resource == null) throw new IllegalStateException("Falta la lista local de contraseñas comunes");
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            common = reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("No se pudo cargar la lista local de contraseñas", ex);
        }
    }

    public void validate(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < 15 || length > 128) {
            throw new IllegalArgumentException("La contraseña debe tener entre 15 y 128 caracteres");
        }
        if (password.isBlank() || common.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("La contraseña es demasiado común; elegí otra");
        }
    }
}
