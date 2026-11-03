package br.com.domus.agent.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Decide se uma atualização pode ser instalada: assinatura, canal, janela, retrocesso e integridade do arquivo. */
public final class UpdateVerifier {

    /** Motivo de recusa (mensagem sem dados sensíveis). */
    public static final class Rejected extends Exception {
        public Rejected(String message) {
            super(message);
        }
    }

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,100}\\.jar");
    private static final Pattern VERSION = Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}");

    /** Chaves de lançamento confiáveis (principal e reserva); qualquer uma valida um manifesto. Troca-se só por rotação assinada. */
    private volatile List<PublicKey> releaseKeys;
    private final String channel;
    private final long maxBytes;

    /**
     * @param publicKeysBase64 chaves públicas de lançamento (X.509, base64), separadas por vírgula: a principal e, se houver, a de
     *                         reserva. Uma só chave continua funcionando como antes.
     * @param channel          canal aceito
     * @param maxBytes         tamanho máximo aceito para o jar
     */
    public UpdateVerifier(String publicKeysBase64, String channel, long maxBytes) {
        this.releaseKeys = parseKeys(publicKeysBase64);
        if (releaseKeys.isEmpty()) {
            throw new IllegalArgumentException("PORTARIA_UPDATE_PUBLIC_KEY has no key");
        }
        this.channel = channel;
        this.maxBytes = maxBytes;
    }

    private static List<PublicKey> parseKeys(String csv) {
        List<PublicKey> keys = new ArrayList<>();
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (String k : csv.split(",")) {
                if (!k.isBlank()) {
                    keys.add(kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(k.trim()))));
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("PORTARIA_UPDATE_PUBLIC_KEY is not a valid list of Ed25519 public keys", e);
        }
        return List.copyOf(keys);
    }

    /** Passa a confiar só nestas chaves (rotação já verificada e instalada). */
    public void replaceKeys(List<String> publicKeysBase64) {
        this.releaseKeys = parseKeys(String.join(",", publicKeysBase64));
    }

    /** Verifica o manifesto; a ordem importa: primeiro a assinatura, só depois se confia em qualquer outro campo. */
    public void verifyManifest(UpdateManifest m, String runningVersion, Instant now) throws Rejected {
        boolean signed = false;
        if (m.signature() != null) {
            for (PublicKey key : releaseKeys) {
                try {
                    Signature sig = Signature.getInstance("Ed25519");
                    sig.initVerify(key);
                    sig.update(m.signedPayload().getBytes(StandardCharsets.UTF_8));
                    if (sig.verify(Base64.getDecoder().decode(m.signature()))) {
                        signed = true;
                        break;
                    }
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    // tenta a próxima chave
                }
            }
        }
        if (!signed) {
            throw new Rejected("invalid signature");
        }
        if (!m.trustedKeys().isEmpty()) {
            try {
                if (m.trustedKeys().size() > 4 || new java.util.HashSet<>(m.trustedKeys()).size() != m.trustedKeys().size()) {
                    throw new Rejected("malformed manifest");
                }
                parseKeys(String.join(",", m.trustedKeys()));
            } catch (IllegalArgumentException e) {
                throw new Rejected("malformed manifest");
            }
        }
        if (!channel.equals(m.channel())) {
            throw new Rejected("different channel");
        }
        if (!VERSION.matcher(m.version()).matches() || !FILE.matcher(m.fileName()).matches()
                || !m.sha256().matches("[0-9a-f]{64}")) {
            throw new Rejected("malformed manifest");
        }
        if (m.size() <= 0 || m.size() > maxBytes) {
            throw new Rejected("size out of bounds");
        }
        if (compare(m.version(), runningVersion) <= 0) {
            throw new Rejected("not newer than the running version (downgrade/replay refused)");
        }
        if (now.isBefore(m.notBefore())) {
            throw new Rejected("not yet released");
        }
    }

    /** Confere tamanho e hash do arquivo baixado contra o manifesto já verificado. */
    public static void verifyFile(UpdateManifest m, byte[] jar) throws Rejected {
        if (jar.length != m.size()) {
            throw new Rejected("size mismatch");
        }
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), m.sha256().getBytes(StandardCharsets.UTF_8))) {
                throw new Rejected("hash mismatch");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compara versões X.Y.Z numericamente; versão em execução desconhecida (dev) nunca atualiza. */
    public static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
