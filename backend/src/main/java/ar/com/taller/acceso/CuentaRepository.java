package ar.com.taller.acceso;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CuentaRepository {
    private final JdbcClient jdbc;
    public CuentaRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<CuentaPrincipal> findForLogin(String correo) {
        return jdbc.sql("SELECT id,nombre,correo,perfil,clave_hash,activo FROM usuario WHERE correo=:correo")
                .param("correo", correo).query((row, n) -> new CuentaPrincipal(row.getLong("id"),
                    row.getString("nombre"), row.getString("correo"), row.getString("perfil"),
                    row.getString("clave_hash"), row.getBoolean("activo"))).optional();
    }

    public Optional<EstadoCuenta> findState(long id) {
        return jdbc.sql("SELECT id,nombre,correo,perfil,activo,SHA2(clave_hash,256) AS credencial FROM usuario WHERE id=:id")
                .param("id", id).query(EstadoCuenta.class).optional();
    }

    public record EstadoCuenta(long id, String nombre, String correo, String perfil, boolean activo, String credencial) {}
}
