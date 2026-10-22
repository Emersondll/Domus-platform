package br.com.portaria.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia em repouso do armazenamento local: AES-256-GCM, chave derivada (HKDF-SHA256) de {@code PORTARIA_STORE_KEY}.
 * O SQLite guarda a política (que contém as assinaturas das credenciais, isto é, segredos de QR Code), a fila de eventos e a
 * chave de API do agente; sem a chave quem lê o arquivo não obtém nada disso. O <i>contexto</i> (ex.: {@code outbox:<eventId>})
 * entra como dado autenticado: mover um valor cifrado para outra linha/coluna faz a decifragem falhar.
 * Formato: {@code enc:v1:<base64(iv||texto cifrado)>}. Valor sem o prefixo é legado em texto puro (migrado na abertura).
 */
final class StoreCipher {

    static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private StoreCipher(byte[] aesKey) {
        this.key = new SecretKeySpec(aesKey, "AES");
    }

    /** @throws IllegalArgumentException se o segredo tiver menos de 32 caracteres */
    static StoreCipher fromSecret(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("PORTARIA_STORE_KEY must have at least 32 characters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("portaria-agent-store-v1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prk = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update("aes-256-gcm".getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            return new StoreCipher(Arrays.copyOf(mac.doFinal(), 32));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    String encrypt(String plain, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Store encryption failed", e);
        }
    }

    /** Texto puro legado volta como está. Chave errada, contexto trocado ou adulteração lançam {@link IllegalStateException}. */
    String decrypt(String stored, String context) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, IV_BYTES));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored value cannot be decrypted (wrong key, moved or tampered)", e);
        }
    }
}
