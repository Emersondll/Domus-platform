package br.com.portaria.platform.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token do QR Code: {@code <credentialId>.<assinatura>}, sem dado pessoal. A assinatura HMAC-SHA256 liga a
 * credencial ao condomínio, então um token de outro condomínio é rejeitado mesmo que o ID exista.
 * Validade, uso e revogação não estão no token: são lidos do banco a cada validação.
 */
@Component
public class CredentialTokenCodec {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;

    public CredentialTokenCodec(@Value("${portaria.credentials.hmac-secret:}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "portaria.credentials.hmac-secret (CREDENTIAL_HMAC_SECRET) must have at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(UUID credentialId, UUID condominiumId) {
        return credentialId + "." + ENC.encodeToString(sign(credentialId, condominiumId));
    }

    /** Assinatura (parte após o ponto) esperada para a credencial naquele condomínio. */
    public String signatureOf(UUID credentialId, UUID condominiumId) {
        return ENC.encodeToString(sign(credentialId, condominiumId));
    }

    /** @return o ID da credencial se o formato e a assinatura forem válidos para o condomínio informado */
    public Optional<UUID> decode(String token, UUID condominiumId) {
        if (token == null || token.length() > 200) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(token.substring(0, dot));
            byte[] given = DEC.decode(token.substring(dot + 1));
            return MessageDigest.isEqual(given, sign(id, condominiumId)) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(UUID credentialId, UUID condominiumId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal((credentialId + "|" + condominiumId).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
