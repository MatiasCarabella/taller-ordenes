package ar.com.taller.cuentas;

import java.io.Console;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "taller.cuentas")
public class CuentasCommand implements CommandLineRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    private final TransactionTemplate transactions;

    public CuentasCommand(JdbcClient jdbc, PasswordEncoder encoder, PasswordPolicy policy,
            Environment environment, ConfigurableApplicationContext context, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.policy = policy;
        this.environment = environment;
        this.context = context;
        this.transactions = transactions;
    }

    @Override
    public void run(String... args) {
        if (environment.getProperty("spring.main.web-application-type", "").equalsIgnoreCase("none") == false) {
            throw new IllegalStateException("La utilidad exige --spring.main.web-application-type=none");
        }
        switch (environment.getRequiredProperty("taller.cuentas")) {
            case "demo" -> initializeDemo();
            case "clave" -> setPassword(console());
            case "crear" -> createAccount(console());
            case "desactivar" -> deactivate(console());
            default -> throw new IllegalArgumentException("Operaciones disponibles: demo, crear, clave, desactivar");
        }
        context.close();
    }

    private Console console() {
        var console = System.console();
        if (console == null) throw new IllegalStateException("Se requiere una terminal interactiva: usar docker compose run --rm -it");
        return console;
    }

    private String email(Console console) {
        String email = console.readLine("Correo: ").strip().toLowerCase(Locale.ROOT);
        if (email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Correo inválido");
        }
        return email;
    }

    private String passwordHash(Console console) {
        char[] password = console.readPassword("Contraseña (15–128 caracteres): ");
        char[] confirmation = console.readPassword("Repetir contraseña: ");
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Las contraseñas no coinciden");
            }
            String value = new String(password);
            policy.validate(value);
            return encoder.encode(value);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private void initializeDemo() {
        var fixtures = List.of(
            new Fixture("ADMINISTRACION", "Administración de prueba", "admin@taller.example"),
            new Fixture("PERSONAL", "Personal uno", "personal1@taller.example"),
            new Fixture("PERSONAL", "Personal dos", "personal2@taller.example"),
            new Fixture("CLIENTE", "Cliente uno", "cliente1@taller.example"),
            new Fixture("CLIENTE", "Cliente dos", "cliente2@taller.example"));
        transactions.executeWithoutResult(status -> {
            for (var fixture : fixtures) {
                boolean exists = jdbc.sql("SELECT COUNT(*) FROM usuario WHERE correo = :correo")
                        .param("correo", fixture.email()).query(Long.class).single() > 0;
                if (exists) continue;
                if (fixture.profile().equals("ADMINISTRACION") &&
                        jdbc.sql("SELECT COUNT(*) FROM usuario WHERE perfil = 'ADMINISTRACION'")
                                .query(Long.class).single() > 0) continue;
                byte[] randomPassword = new byte[32];
                new SecureRandom().nextBytes(randomPassword);
                String hash = encoder.encode(HexFormat.of().formatHex(randomPassword));
                Arrays.fill(randomPassword, (byte) 0);
                jdbc.sql("""
                        INSERT INTO usuario (perfil,nombre,correo,clave_hash,activo,tarifa_hora_vigente)
                        VALUES (:perfil,:nombre,:correo,:hash,FALSE,:tarifa)
                        """).param("perfil", fixture.profile()).param("nombre", fixture.name())
                        .param("correo", fixture.email()).param("hash", hash)
                        .param("tarifa", fixture.profile().equals("ADMINISTRACION") ? new BigDecimal("6000.00") : null)
                        .update();
            }
        });
        System.out.println("Datos ficticios preparados sin modificar cuentas existentes. Las cuentas nuevas están inactivas.");
        System.out.println("Usar la operación clave para establecer una contraseña individual y activar cada cuenta.");
    }

    private void setPassword(Console console) {
        String email = email(console);
        long exists = jdbc.sql("SELECT COUNT(*) FROM usuario WHERE correo = :correo")
                .param("correo", email).query(Long.class).single();
        if (exists != 1) throw new IllegalArgumentException("La cuenta no existe");
        String hash = passwordHash(console);
        jdbc.sql("""
                UPDATE usuario SET clave_hash=:hash, activo=TRUE WHERE correo=:correo
                """).param("hash", hash).param("correo", email).update();
        console.printf("Contraseña establecida y cuenta activada. Las sesiones anteriores dejarán de autorizar solicitudes.%n");
    }

    private void createAccount(Console console) {
        String email = email(console);
        String profile = console.readLine("Perfil (ADMINISTRACION/PERSONAL/CLIENTE): ").strip().toUpperCase(Locale.ROOT);
        if (!List.of("ADMINISTRACION", "PERSONAL", "CLIENTE").contains(profile)) {
            throw new IllegalArgumentException("Perfil inválido");
        }
        String name = console.readLine("Nombre visible: ").strip();
        if (name.isEmpty() || name.length() > 255) throw new IllegalArgumentException("Nombre inválido");
        String hash = passwordHash(console);
        jdbc.sql("""
                INSERT INTO usuario (perfil,nombre,correo,clave_hash,activo)
                VALUES (:perfil,:nombre,:correo,:hash,TRUE)
                """).param("perfil", profile).param("nombre", name).param("correo", email).param("hash", hash).update();
        console.printf("Cuenta creada y activada. La tarifa se configurará desde Administración.%n");
    }

    private void deactivate(Console console) {
        String email = email(console);
        int count = jdbc.sql("UPDATE usuario SET activo=FALSE WHERE correo=:correo")
                .param("correo", email).update();
        if (count != 1) throw new IllegalArgumentException("La cuenta no existe");
        console.printf("Cuenta desactivada. Las sesiones anteriores dejarán de autorizar solicitudes.%n");
    }

    private record Fixture(String profile, String name, String email) {}
}
