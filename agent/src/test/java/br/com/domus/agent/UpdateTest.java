package br.com.domus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.domus.agent.update.AgentUpdater;
import br.com.domus.agent.update.AgentUpdater.Outcome;
import br.com.domus.agent.update.UpdateManifest;
import br.com.domus.agent.update.UpdateVerifier;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Atualização segura do agente: só instala jar com manifesto assinado, mais novo, íntegro e no momento certo. */
class UpdateTest {

    @TempDir Path dir;

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final byte[] oldJar = "JAR-VELHO".getBytes(StandardCharsets.UTF_8);
    private final byte[] newJar = "JAR-NOVO-1.1.0".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private volatile String manifestJson;
    private volatile byte[] servedJar;
    private final AtomicInteger jarDownloads = new AtomicInteger();
    private KeyPair releaseKey;
    private Path jarPath;
    private Path pending;
    private final AtomicBoolean restarted = new AtomicBoolean();
    private final AtomicBoolean idle = new AtomicBoolean(true);

    @BeforeEach
    void start() throws Exception {
        releaseKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jarPath = dir.resolve("bin/agent.jar");
        Files.createDirectories(jarPath.getParent());
        Files.write(jarPath, oldJar);
        pending = dir.resolve("update-pending");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/manifest.json", ex -> respond(ex, manifestJson.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().endsWith(".jar")) {
                jarDownloads.incrementAndGet();
                respond(ex, servedJar);
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        server.start();
        servedJar = newJar;
        manifestJson = manifest("1.1.0", "stable", newJar, NOW.minusSeconds(60), releaseKey, "1.1.0");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, byte[] body) throws java.io.IOException {
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private String manifest(String version, String channel, byte[] jar, Instant notBefore, KeyPair signer, String fileVersion)
            throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
        UpdateManifest unsigned = new UpdateManifest(version, channel, "portaria-agent-" + fileVersion + ".jar", sha, jar.length,
                notBefore, "");
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(signer.getPrivate());
        s.update(unsigned.signedPayload().getBytes(StandardCharsets.UTF_8));
        return String.format("{\"version\":\"%s\",\"channel\":\"%s\",\"fileName\":\"%s\",\"sha256\":\"%s\",\"size\":%d,"
                        + "\"notBefore\":\"%s\",\"signature\":\"%s\"}", version, channel, unsigned.fileName(), sha, jar.length,
                notBefore, Base64.getEncoder().encodeToString(s.sign()));
    }

    private AgentUpdater updater(String running) {
        String pub = Base64.getEncoder().encodeToString(releaseKey.getPublic().getEncoded());
        return new AgentUpdater(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                new UpdateVerifier(pub, "stable", 1 << 20), running, jarPath, pending, idle::get, () -> restarted.set(true), clock);
    }

    private void assertNothingChanged() throws Exception {
        assertThat(Files.readAllBytes(jarPath)).isEqualTo(oldJar);
        assertThat(pending).doesNotExist();
        assertThat(restarted).isFalse();
    }

    private static String b64(KeyPair k) {
        return Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
    }

    /** Manifesto 1.2.0 (ou a versão dada) com rotação de chaves, assinado por {@code signer}. */
    private String rotationManifest(String version, KeyPair signer, java.util.List<String> keys) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newJar));
        UpdateManifest unsigned = new UpdateManifest(version, "stable", "portaria-agent-" + version + ".jar", sha, newJar.length,
                NOW.minusSeconds(60), "", keys);
        Signature sg = Signature.getInstance("Ed25519");
        sg.initSign(signer.getPrivate());
        sg.update(unsigned.signedPayload().getBytes(StandardCharsets.UTF_8));
        return String.format("{\"version\":\"%s\",\"channel\":\"stable\",\"fileName\":\"%s\",\"sha256\":\"%s\",\"size\":%d,"
                        + "\"notBefore\":\"%s\",\"trustedKeys\":\"%s\",\"signature\":\"%s\"}", version, unsigned.fileName(), sha,
                newJar.length, NOW.minusSeconds(60), String.join(",", keys), Base64.getEncoder().encodeToString(sg.sign()));
    }

    private AgentUpdater updaterWithKeys(String keysCsv, Path keysFile) {
        return new AgentUpdater(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                new UpdateVerifier(keysCsv, "stable", 1 << 20), "1.0.0", jarPath, pending, idle::get, () -> restarted.set(true), clock,
                keysFile);
    }

    @Test
    void theReserveKeyCanSignAReleaseWhenBothKeysAreConfigured() throws Exception {
        KeyPair reserve = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        manifestJson = manifest("1.1.0", "stable", newJar, NOW.minusSeconds(60), reserve, "1.1.0");
        assertThat(updaterWithKeys(b64(releaseKey) + "," + b64(reserve), null).checkOnce()).isEqualTo(Outcome.INSTALLED);
        // com só a chave principal configurada, a de reserva não vale (e uma terceira nunca vale)
        Files.write(jarPath, oldJar);
        Files.deleteIfExists(pending);
        restarted.set(false);
        assertThat(updaterWithKeys(b64(releaseKey), null).checkOnce()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void aSignedRotationReplacesTheTrustedKeysAndTheLeakedOneStopsWorking() throws Exception {
        KeyPair reserve = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair newReserve = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keysFile = dir.resolve("update-trusted-keys");
        AgentUpdater u = updaterWithKeys(b64(releaseKey) + "," + b64(reserve), keysFile);
        // a chave principal vazou: a reserva assina uma versão que troca o conjunto para [reserva, nova reserva]
        manifestJson = rotationManifest("1.1.0", reserve, java.util.List.of(b64(reserve), b64(newReserve)));
        assertThat(u.checkOnce()).isEqualTo(Outcome.INSTALLED);
        assertThat(Files.readString(keysFile).trim()).isEqualTo(b64(reserve) + "," + b64(newReserve));
        // quem tem a chave vazada já não consegue assinar nada; a reserva e a nova reserva continuam valendo
        manifestJson = manifest("1.2.0", "stable", newJar, NOW.minusSeconds(60), releaseKey, "1.2.0");
        assertThat(u.checkOnce()).isEqualTo(Outcome.REJECTED);
        manifestJson = manifest("1.2.0", "stable", newJar, NOW.minusSeconds(60), newReserve, "1.2.0");
        assertThat(u.checkOnce()).isEqualTo(Outcome.INSTALLED);
    }

    @Test
    void aRotationWithMalformedOrTooManyKeysIsRejectedEvenWhenSignedByATrustedKey() throws Exception {
        Path keysFile = dir.resolve("update-trusted-keys");
        AgentUpdater u = updaterWithKeys(b64(releaseKey), keysFile);
        manifestJson = rotationManifest("1.1.0", releaseKey, java.util.List.of("isto-nao-e-uma-chave"));
        assertThat(u.checkOnce()).isEqualTo(Outcome.REJECTED);
        java.util.List<String> five = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(b64(KeyPairGenerator.getInstance("Ed25519").generateKeyPair()));
        }
        manifestJson = rotationManifest("1.1.0", releaseKey, five);
        assertThat(u.checkOnce()).isEqualTo(Outcome.REJECTED);
        assertThat(keysFile).doesNotExist();
        assertNothingChanged();
    }

    @Test
    void theTrustedKeysCannotBeAddedToAnAlreadySignedManifestWithoutBreakingTheSignature() throws Exception {
        // um atacante que só tem o manifesto assinado (sem rotação) não pode acrescentar a própria chave
        String signedWithoutRotation = manifest("1.1.0", "stable", newJar, NOW.minusSeconds(60), releaseKey, "1.1.0");
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        manifestJson = signedWithoutRotation.replace("\"signature\"", "\"trustedKeys\":\"" + b64(attacker) + "\",\"signature\"");
        assertThat(updaterWithKeys(b64(releaseKey), dir.resolve("update-trusted-keys")).checkOnce()).isEqualTo(Outcome.REJECTED);
        assertThat(dir.resolve("update-trusted-keys")).doesNotExist();
        assertNothingChanged();
    }

    @Test
    void aValidSignedNewerReleaseIsInstalledKeepingThePreviousJar() throws Exception {
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.INSTALLED);
        assertThat(Files.readAllBytes(jarPath)).isEqualTo(newJar);
        assertThat(Files.readAllBytes(dir.resolve("bin/agent.jar.previous"))).isEqualTo(oldJar);
        assertThat(Files.readString(pending)).startsWith("1.1.0|");
        assertThat(restarted).isTrue();
        assertThat(dir.resolve("bin/agent.jar.new")).doesNotExist();
    }

    @Test
    void aManifestSignedByAnotherKeyIsRejectedAndNothingIsDownloaded() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        manifestJson = manifest("9.9.9", "stable", "MALWARE".getBytes(), NOW.minusSeconds(60), attacker, "9.9.9");
        servedJar = "MALWARE".getBytes();
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        assertThat(jarDownloads).hasValue(0);
        assertNothingChanged();
    }

    @Test
    void editingAnySignedFieldInvalidatesTheSignature() throws Exception {
        manifestJson = manifestJson.replace("\"version\":\"1.1.0\"", "\"version\":\"9.0.0\"");
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        manifestJson = manifest("1.1.0", "stable", newJar, NOW.minusSeconds(60), releaseKey, "1.1.0")
                .replaceAll("\"size\":\\d+", "\"size\":999");
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        assertNothingChanged();
    }

    @Test
    void aJarThatDoesNotMatchTheSignedHashIsRejected() throws Exception {
        servedJar = "JAR-NOVO-1.1.X".getBytes(StandardCharsets.UTF_8); // mesmo tamanho, conteúdo trocado no servidor
        assertThat(servedJar).hasSameSizeAs(newJar);
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        assertNothingChanged();
    }

    @Test
    void aServerSendingMoreBytesThanSignedIsCutAndRejected() throws Exception {
        servedJar = (new String(newJar, StandardCharsets.UTF_8) + "-EXTRA-DATA").getBytes(StandardCharsets.UTF_8);
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        assertNothingChanged();
    }

    @Test
    void sameOrOlderVersionsAreNeverInstalledDowngradeAndReplayAreRefused() throws Exception {
        assertThat(updater("1.1.0").checkOnce()).isEqualTo(Outcome.UP_TO_DATE);
        assertThat(updater("2.0.0").checkOnce()).isEqualTo(Outcome.UP_TO_DATE); // manifesto antigo reapresentado
        assertThat(jarDownloads).hasValue(0);
        assertNothingChanged();
    }

    @Test
    void aReleaseNotYetDueWaitsWithoutBeingTreatedAsAnAttack() throws Exception {
        manifestJson = manifest("1.1.0", "stable", newJar, NOW.plusSeconds(3600), releaseKey, "1.1.0");
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.NOT_YET_RELEASED);
        assertNothingChanged();
    }

    @Test
    void aDifferentChannelIsIgnored() throws Exception {
        manifestJson = manifest("1.1.0", "beta", newJar, NOW.minusSeconds(60), releaseKey, "1.1.0");
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.REJECTED);
        assertNothingChanged();
    }

    @Test
    void nothingIsInstalledWhileAnEntryIsBeingRecorded() throws Exception {
        idle.set(false);
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.BUSY);
        assertThat(jarDownloads).hasValue(0);
        idle.set(true);
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.INSTALLED); // no ciclo seguinte instala
    }

    @Test
    void developmentAndSnapshotBuildsNeverUpdateThemselves() {
        assertThat(updater("0.0.0").checkOnce()).isEqualTo(Outcome.DISABLED);
        assertThat(updater("0.1.0-SNAPSHOT").checkOnce()).isEqualTo(Outcome.DISABLED);
    }

    @Test
    void unreachableServerFailsSoftlyAndKeepsRunning() throws Exception {
        server.stop(0);
        assertThat(updater("1.0.0").checkOnce()).isEqualTo(Outcome.FAILED);
        assertNothingChanged();
    }

    @Test
    void plainHttpToARemoteHostIsRefused() {
        String pub = Base64.getEncoder().encodeToString(releaseKey.getPublic().getEncoded());
        assertThatThrownBy(() -> new AgentUpdater(URI.create("http://releases.example.com/"), new UpdateVerifier(pub, "stable", 1000),
                "1.0.0", jarPath, pending, () -> true, () -> { }, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionsCompareNumericallyNotAsText() {
        assertThat(UpdateVerifier.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(UpdateVerifier.compare("1.0.0", "1.0.0")).isZero();
        assertThat(UpdateVerifier.compare("0.9.9", "1.0.0")).isNegative();
    }

    @Test
    void signToolOutputIsAcceptedByTheVerifier() throws Exception {
        Path keys = dir.resolve("keys");
        br.com.domus.agent.update.SignTool.main(new String[] {"genkey", keys.toString()});
        Path jar = dir.resolve("agent-build.jar");
        Files.write(jar, newJar);
        Path out = dir.resolve("release");
        br.com.domus.agent.update.SignTool.main(new String[] {"sign", jar.toString(), "1.1.0", "stable",
                keys.resolve("release-private.key").toString(), out.toString(), NOW.minusSeconds(5).toString()});
        // o servidor de arquivos passa a servir a pasta gerada pela ferramenta
        manifestJson = Files.readString(out.resolve("manifest.json"));
        servedJar = Files.readAllBytes(out.resolve("portaria-agent-1.1.0.jar"));
        String pub = Files.readString(keys.resolve("release-public.key")).trim();
        AgentUpdater u = new AgentUpdater(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                new UpdateVerifier(pub, "stable", 1 << 20), "1.0.0", jarPath, pending, () -> true, () -> restarted.set(true), clock);
        assertThat(u.checkOnce()).isEqualTo(Outcome.INSTALLED);
        assertThat(Files.readAllBytes(jarPath)).isEqualTo(newJar);
    }
}
