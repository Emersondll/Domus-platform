package br.com.domus.platform.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.domus.platform.shared.crypto.FieldCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Cifra de campos: formato, adulteração, rotação de chave, legado em texto puro e índice cego. */
class FieldCipherTest {

    private static final String K1 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String K2 = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());
    private static final String BLIND = "blind-index-key-0123456789-abcdefghij";

    private FieldCipher cipher(String keys, String active) {
        return new FieldCipher(keys, active, BLIND);
    }

    @Test
    void roundTripKeepsAccentsAndNullStaysNull() {
        FieldCipher c = cipher("k1:" + K1, "");
        String stored = c.encrypt("João da Conceição");
        assertThat(stored).startsWith("enc:v1:k1:").doesNotContain("João");
        assertThat(c.decrypt(stored)).isEqualTo("João da Conceição");
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.decrypt(null)).isNull();
    }

    @Test
    void sameValueEncryptsDifferentlyEveryTime() {
        FieldCipher c = cipher("k1:" + K1, "");
        assertThat(c.encrypt("mesmo valor")).isNotEqualTo(c.encrypt("mesmo valor"));
    }

    @Test
    void tamperedCiphertextIsRejectedNeverReturnedAsGarbage() {
        FieldCipher c = cipher("k1:" + K1, "");
        String stored = c.encrypt("segredo");
        char last = stored.charAt(stored.length() - 3);
        String tampered = stored.substring(0, stored.length() - 3) + (last == 'A' ? 'B' : 'A') + stored.substring(stored.length() - 2);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class).hasMessageContaining("tampered");
    }

    @Test
    void legacyPlaintextIsReadAsIsAndIsNotConsideredCurrent() {
        FieldCipher c = cipher("k1:" + K1, "");
        assertThat(c.decrypt("Maria Silva")).isEqualTo("Maria Silva");
        assertThat(c.isCurrent("Maria Silva")).isFalse();
        assertThat(c.isCurrent(c.encrypt("x"))).isTrue();
    }

    @Test
    void rotationWritesWithTheActiveKeyAndStillReadsTheOldOne() {
        String old = cipher("k1:" + K1, "k1").encrypt("antigo");
        FieldCipher rotated = cipher("k1:" + K1 + ",k2:" + K2, "k2");
        assertThat(rotated.decrypt(old)).isEqualTo("antigo");
        assertThat(rotated.isCurrent(old)).isFalse();
        assertThat(rotated.encrypt("novo")).startsWith("enc:v1:k2:");
        // sem a chave antiga configurada, o valor antigo não abre
        assertThatThrownBy(() -> cipher("k2:" + K2, "k2").decrypt(old)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDifferentKeyCannotOpenTheValue() {
        String stored = cipher("k1:" + K1, "k1").encrypt("x");
        String forged = stored.replace(":k1:", ":k2:");
        assertThatThrownBy(() -> cipher("k1:" + K1 + ",k2:" + K2, "k1").decrypt(forged)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexIsDeterministicKeyedAndHidesTheValue() {
        FieldCipher c = cipher("k1:" + K1, "");
        assertThat(c.blindIndex("ABC1D23")).isEqualTo(c.blindIndex("ABC1D23")).hasSize(64).doesNotContain("ABC1D23");
        assertThat(c.blindIndex("ABC1D23")).isNotEqualTo(c.blindIndex("ABC1D24"));
        assertThat(new FieldCipher("k1:" + K1, "", "another-blind-index-key-0123456789-abcdef").blindIndex("ABC1D23"))
                .isNotEqualTo(c.blindIndex("ABC1D23"));
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> cipher("", "")).hasMessageContaining("required");
        assertThatThrownBy(() -> cipher("k1:" + Base64.getEncoder().encodeToString("curta".getBytes()), "")).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> cipher("k1:naoebase64!!", "")).hasMessageContaining("base64");
        assertThatThrownBy(() -> cipher("k1:" + K1, "inexistente")).hasMessageContaining("active-key-id");
        assertThatThrownBy(() -> new FieldCipher("k1:" + K1, "", "curta")).hasMessageContaining("blind-index-key");
    }
}
