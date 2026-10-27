package br.com.domus.platform.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Criptografia em nível de campo para dados pessoais (nome, e-mail, telefone, placa). AES-256-GCM com IV aleatório de 12 bytes
 * por valor; o formato guardado é {@code enc:v1:<idDaChave>:<base64(iv||texto cifrado)>}.
 *
 * <p>Rotação: {@code portaria.crypto.keys} aceita várias chaves ({@code id:base64,id2:base64}); grava-se sempre com a chave
 * ativa e lê-se com qualquer uma. Valor sem o prefixo {@code enc:v1:} é tratado como texto puro legado (lido como está), o que
 * permite ligar a criptografia em um banco com dados antigos e migrá-los depois ({@code PiiEncryptionMigration}).
 *
 * <p>Busca por igualdade (ex.: placa já cadastrada) usa o <i>índice cego</i>: HMAC-SHA256 do valor normalizado com uma chave
 * própria ({@code portaria.crypto.blind-index-key}), nunca o valor cifrado, que muda a cada gravação.
 * As chaves vêm de um gerenciador de segredos, <b>nunca</b> do banco; perder as chaves = perder os dados pessoais (guardar à parte do backup).
 */
@Component
public class FieldCipher {

    static final String PREFIX = "enc:v1:";
    private static final byte[] AAD = "portaria:pii:v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String activeId;
    private final byte[] blindKey;
    private final SecureRandom random = new SecureRandom();

    public FieldCipher(@Value("${portaria.crypto.keys:}") String keysConfig,
                       @Value("${portaria.crypto.active-key-id:}") String activeId,
                       @Value("${portaria.crypto.blind-index-key:}") String blindIndexKey) {
        for (String entry : keysConfig.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,16}")) {
                throw new IllegalStateException("portaria.crypto.keys must look like 'id:base64key[,id2:base64key2]'");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(parts[1].trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("portaria.crypto.keys: key '" + parts[0] + "' is not valid base64");
            }
            if (raw.length != 32) {
                throw new IllegalStateException("portaria.crypto.keys: key '" + parts[0] + "' must be 32 bytes (AES-256)");
            }
            keys.put(parts[0], new SecretKeySpec(raw, "AES"));
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("portaria.crypto.keys (FIELD_ENCRYPTION_KEYS) is required");
        }
        this.activeId = activeId.isBlank() ? keys.keySet().iterator().next() : activeId;
        if (!keys.containsKey(this.activeId)) {
            throw new IllegalStateException("portaria.crypto.active-key-id does not match any configured key");
        }
        if (blindIndexKey.length() < 32) {
            throw new IllegalStateException("portaria.crypto.blind-index-key (BLIND_INDEX_KEY) must have at least 32 characters");
        }
        this.blindKey = blindIndexKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Cifra com a chave ativa; {@code null} continua {@code null}. */
    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.get(activeId), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return PREFIX + activeId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    /** Decifra; texto sem o prefixo (legado) é devolvido como está. Valor adulterado ou chave desconhecida lança exceção. */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        int sep = stored.indexOf(':', PREFIX.length());
        if (sep < 0) {
            throw new IllegalStateException("Malformed encrypted value");
        }
        SecretKeySpec key = keys.get(stored.substring(PREFIX.length(), sep));
        if (key == null) {
            throw new IllegalStateException("Encrypted value uses an unknown key id");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(sep + 1));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            c.updateAAD(AAD);
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Encrypted value is corrupted or was tampered with", e);
        }
    }

    /** Verdadeiro se o valor já está cifrado com a chave ativa (usado pela migração/rotação). */
    public boolean isCurrent(String stored) {
        return stored != null && stored.startsWith(PREFIX + activeId + ":");
    }

    /** Normalização da placa para o índice cego: maiúsculas, só letras e números. */
    public static String normalizePlate(String plate) {
        return plate.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** Índice cego do e-mail (minúsculas, sem espaços), separado do contexto da placa por um prefixo. */
    public String emailIndex(String email) {
        return blindIndex("email:" + email.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Índice cego (HMAC-SHA256, hex) do valor já normalizado, para busca por igualdade sem expor o texto. */
    public String blindIndex(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(blindKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
