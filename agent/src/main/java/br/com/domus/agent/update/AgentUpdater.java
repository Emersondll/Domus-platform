package br.com.domus.agent.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Busca, verifica e instala uma nova versão do agente. Fluxo: baixa o manifesto → verifica a assinatura de lançamento e as regras
 * (canal, janela, sem retrocesso) → baixa o jar com limite de tamanho → confere tamanho e hash → grava em arquivo temporário
 * → guarda o jar atual como {@code .previous} → troca atomicamente → grava o marcador {@code update-pending} → pede reinício.
 * O reinício é feito pelo systemd; o script de partida desfaz a troca se a versão nova não se mantiver de pé.
 * Só instala quando não há reserva de entrada em andamento (o reinício no meio de uma abertura geraria um caso incerto).
 */
public final class AgentUpdater {

    private static final Logger log = LoggerFactory.getLogger(AgentUpdater.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Resultado de uma verificação, para log e testes. */
    public enum Outcome { DISABLED, UP_TO_DATE, NOT_YET_RELEASED, INSTALLED, REJECTED, BUSY, FAILED }

    private final URI baseUrl;
    private final UpdateVerifier verifier;
    private final String runningVersion;
    private final Path jarPath;
    private final Path pendingMarker;
    private final BooleanSupplier idle;
    private final Runnable restart;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /**
     * @param baseUrl        pasta (HTTPS) onde ficam manifest.json e os jars; HTTP só é aceito em localhost (testes)
     * @param runningVersion versão em execução ("0.0.0" desliga a atualização)
     * @param jarPath        jar que o systemd executa; é substituído pela nova versão
     * @param pendingMarker  arquivo que indica "atualização ainda não confirmada" (lido pelo script de partida)
     * @param idle           verdadeiro quando não há operação de entrada em andamento
     * @param restart        como pedir o reinício (em produção, sair com código 75)
     */
    public AgentUpdater(URI baseUrl, UpdateVerifier verifier, String runningVersion, Path jarPath, Path pendingMarker,
                        BooleanSupplier idle, Runnable restart, Clock clock) {
        boolean local = "localhost".equals(baseUrl.getHost()) || "127.0.0.1".equals(baseUrl.getHost());
        if (!"https".equals(baseUrl.getScheme()) && !(local && "http".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("PORTARIA_UPDATE_URL must be https");
        }
        this.baseUrl = baseUrl.toString().endsWith("/") ? baseUrl : URI.create(baseUrl + "/");
        this.verifier = verifier;
        this.runningVersion = runningVersion;
        this.jarPath = jarPath;
        this.pendingMarker = pendingMarker;
        this.idle = idle;
        this.restart = restart;
        this.clock = clock;
    }

    public Outcome checkOnce() {
        if ("0.0.0".equals(runningVersion) || !runningVersion.matches("\\d+\\.\\d+\\.\\d+")) {
            return Outcome.DISABLED; // build de desenvolvimento/snapshot: nunca se atualiza sozinho
        }
        try {
            UpdateManifest manifest = fetchManifest();
            try {
                verifier.verifyManifest(manifest, runningVersion, Instant.now(clock));
            } catch (UpdateVerifier.Rejected r) {
                boolean upToDate = r.getMessage().startsWith("not newer");
                if (upToDate) {
                    return Outcome.UP_TO_DATE;
                }
                if (r.getMessage().startsWith("not yet")) {
                    return Outcome.NOT_YET_RELEASED; // liberação escalonada: ainda não é a hora, não é ataque
                }
                log.error("SECURITY: update manifest rejected: {}", r.getMessage());
                return Outcome.REJECTED;
            }
            if (!idle.getAsBoolean()) {
                return Outcome.BUSY; // tenta no próximo ciclo
            }
            byte[] jar = download(baseUrl.resolve(manifest.fileName()), manifest.size());
            try {
                UpdateVerifier.verifyFile(manifest, jar);
            } catch (UpdateVerifier.Rejected r) {
                log.error("SECURITY: downloaded update rejected: {}", r.getMessage());
                return Outcome.REJECTED;
            }
            install(manifest, jar);
            log.warn("Agent updated {} -> {}; restarting", runningVersion, manifest.version());
            restart.run();
            return Outcome.INSTALLED;
        } catch (IOException | RuntimeException e) {
            log.warn("Update check failed (will retry): {}", e.getMessage());
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
    }

    private UpdateManifest fetchManifest() throws IOException, InterruptedException {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(baseUrl.resolve("manifest.json"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || r.body().length > 16_384) {
            throw new IOException("manifest unavailable (HTTP " + r.statusCode() + ")");
        }
        JsonNode n = JSON.readTree(r.body());
        return new UpdateManifest(text(n, "version"), text(n, "channel"), text(n, "fileName"), text(n, "sha256"),
                n.path("size").asLong(0), Instant.parse(text(n, "notBefore")), text(n, "signature"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("manifest field missing: " + field);
        }
        return v.asString();
    }

    private byte[] download(URI uri, long expectedSize) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("jar unavailable (HTTP " + r.statusCode() + ")");
        }
        try (InputStream in = r.body()) {
            // lê no máximo size+1 bytes: um servidor que mande mais do que o manifesto declarou é cortado
            byte[] data = in.readNBytes((int) expectedSize + 1);
            return data;
        }
    }

    private void install(UpdateManifest m, byte[] jar) throws IOException {
        Path staged = jarPath.resolveSibling(jarPath.getFileName() + ".new");
        Files.write(staged, jar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (Files.exists(jarPath)) {
            Files.copy(jarPath, jarPath.resolveSibling(jarPath.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(pendingMarker, m.version() + "|" + Instant.now(clock) + "\n");
        Files.move(staged, jarPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Versão em execução, lida do manifesto do jar ({@code Implementation-Version}); "0.0.0" fora de um jar de lançamento. */
    public static String runningVersion() {
        return Optional.ofNullable(AgentUpdater.class.getPackage().getImplementationVersion()).orElse("0.0.0");
    }
}
