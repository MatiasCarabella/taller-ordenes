package ar.com.taller.acceso;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class CuentaPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final long id;
    private final String nombre;
    private final String perfil;
    private final String credencial;

    public CuentaPrincipal(long id, String nombre, String correo, String perfil, String hash, boolean activo) {
        super(correo, hash, activo, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + perfil)));
        this.id = id;
        this.nombre = nombre;
        this.perfil = perfil;
        try {
            this.credencial = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public long id() { return id; }
    public String nombre() { return nombre; }
    public String perfil() { return perfil; }
    public String credencial() { return credencial; }
}
