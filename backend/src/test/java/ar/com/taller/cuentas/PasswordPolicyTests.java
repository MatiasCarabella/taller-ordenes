package ar.com.taller.cuentas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordPolicyTests {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test void acceptsPassphraseWithoutCompositionRules() {
        assertDoesNotThrow(() -> policy.validate("una frase larga y propia"));
    }

    @Test void rejectsShortLongAndCommonPasswords() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate("corta"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("PasswordPassword"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(" ".repeat(20)));
    }

    @Test void countsUnicodeCharactersAndPreservesFullPassword() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate("🚙".repeat(14)));
        String password = "🚙".repeat(15);
        assertDoesNotThrow(() -> policy.validate(password));
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        String hash = encoder.encode(password);
        assertTrue(hash.startsWith("$argon2id$"));
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(password + "a", hash));
        assertNotEquals(hash, encoder.encode(password));
    }
}
