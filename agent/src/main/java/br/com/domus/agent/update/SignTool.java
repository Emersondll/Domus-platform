package br.com.domus.agent.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Ferramenta de lançamento (roda na máquina de quem publica, NUNCA no agente nem na central).
 * <pre>
 *   genkey &lt;pastaDeSaida&gt;                                   gera release-private.key (600) e release-public.key
 *   sign &lt;agent.jar&gt; &lt;versao&gt; &lt;canal&gt; &lt;chavePrivada&gt; &lt;pastaDeSaida&gt; [notBefore ISO-8601 ou -] [chavesConfiaveis]
 *                                                          copia o jar e escreve manifest.json assinado.
 *                                                          chavesConfiaveis (opcional, separadas por vírgula, 1 a 4 chaves públicas):
 *                                                          ROTAÇÃO — o agente passa a confiar só nelas depois de instalar
 * </pre>
 * A chave privada deve ficar offline (cofre/HSM); só a pública vai para os agentes ({@code PORTARIA_UPDATE_PUBLIC_KEY}).
 */
public final class SignTool {

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("genkey")) {
            genKey(Path.of(args[1]));
        } else if (args.length >= 6 && args[0].equals("sign")) {
            sign(Path.of(args[1]), args[2], args[3], Path.of(args[4]), Path.of(args[5]),
                    args.length > 6 && !args[6].equals("-") ? Instant.parse(args[6]) : Instant.now(),
                    args.length > 7 ? java.util.List.of(args[7].split(",")) : java.util.List.of());
        } else {
            System.err.println("uso: genkey <pasta> | sign <agent.jar> <versao> <canal> <chavePrivada> <pasta> [notBefore|-] [chavesConfiaveis]");
            System.exit(2);
        }
    }

    static void genKey(Path dir) throws Exception {
        Files.createDirectories(dir);
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path priv = dir.resolve("release-private.key");
        Files.writeString(priv, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n");
        try {
            Files.setPosixFilePermissions(priv, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // sistema sem POSIX: o operador precisa restringir o arquivo
        }
        Files.writeString(dir.resolve("release-public.key"), Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n");
        System.out.println("Chave pública (PORTARIA_UPDATE_PUBLIC_KEY): " + Files.readString(dir.resolve("release-public.key")).trim());
    }

    static UpdateManifest sign(Path jar, String version, String channel, Path privateKeyFile, Path outDir, Instant notBefore)
            throws Exception {
        return sign(jar, version, channel, privateKeyFile, outDir, notBefore, java.util.List.of());
    }

    static UpdateManifest sign(Path jar, String version, String channel, Path privateKeyFile, Path outDir, Instant notBefore,
                               java.util.List<String> trustedKeys) throws Exception {
        byte[] bytes = Files.readAllBytes(jar);
        String fileName = "portaria-agent-" + version + ".jar";
        UpdateManifest unsigned = new UpdateManifest(version, channel, fileName,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.length, notBefore, "", trustedKeys);
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(privateKeyFile).trim())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(unsigned.signedPayload().getBytes(StandardCharsets.UTF_8));
        UpdateManifest signed = new UpdateManifest(version, channel, fileName, unsigned.sha256(), unsigned.size(), notBefore,
                Base64.getEncoder().encodeToString(s.sign()), trustedKeys);
        Files.createDirectories(outDir);
        Files.copy(jar, outDir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(outDir.resolve("manifest.json"), String.format(
                "{\"version\":\"%s\",\"channel\":\"%s\",\"fileName\":\"%s\",\"sha256\":\"%s\",\"size\":%d,\"notBefore\":\"%s\","
                        + "%s\"signature\":\"%s\"}%n", signed.version(), signed.channel(), signed.fileName(), signed.sha256(),
                signed.size(), signed.notBefore(),
                signed.trustedKeys().isEmpty() ? "" : "\"trustedKeys\":\"" + String.join(",", signed.trustedKeys()) + "\",",
                signed.signature()));
        return signed;
    }

    private SignTool() {
    }
}
