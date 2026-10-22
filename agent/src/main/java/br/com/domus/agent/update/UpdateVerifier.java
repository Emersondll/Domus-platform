package br.com.domus.agent.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
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

    private final PublicKey releaseKey;
    private final String channel;
    private final long maxBytes;

    /**
     * @param publicKeyBase64 chave pública de lançamento (X.509, base64), configurada na instalação do agente
     * @param channel         canal aceito
     * @param maxBytes        tamanho máximo aceito para o jar
     */
    public UpdateVerifier(String publicKeyBase64, String channel, long maxBytes) {
        try {
            this.releaseKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("PORTARIA_UPDATE_PUBLIC_KEY is not a valid Ed25519 public key", e);
        }
        this.channel = channel;
        this.maxBytes = maxBytes;
    }

    /** Verifica o manifesto; a ordem importa: primeiro a assinatura, só depois se confia em qualquer outro campo. */
    public void verifyManifest(UpdateManifest m, String runningVersion, Instant now) throws Rejected {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(releaseKey);
            sig.update(m.signedPayload().getBytes(StandardCharsets.UTF_8));
            if (m.signature() == null || !sig.verify(Base64.getDecoder().decode(m.signature()))) {
                throw new Rejected("invalid signature");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Rejected("invalid signature");
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
