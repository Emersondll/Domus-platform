package br.com.domus.agent.video;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publica no servidor de mídia da central o vídeo das câmeras da rede local, para câmeras atrás de NAT (a central não alcança
 * o RTSP delas). A conexão é sempre <b>de dentro para fora</b>: o agente puxa a câmera na rede local e empurra o fluxo para a
 * central; nenhuma porta é aberta no condomínio. O ffmpeg copia o vídeo sem recodificar ({@code -c copy}). Cada câmera tem
 * sua própria supervisão: se o ffmpeg cair (câmera reiniciando, queda de internet), ele sobe de novo com espera crescente.
 *
 * <p>Limites: o endereço da câmera (que pode ter usuário e senha) e o segredo de publicação aparecem na lista de argumentos
 * do processo ffmpeg, visíveis a quem tiver acesso de leitura aos processos da máquina do agente; nunca vão a log. O vídeo
 * só passa pela internet de forma protegida se o servidor de mídia aceitar {@code rtsps}.
 */
public final class VideoRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VideoRelay.class);

    /** Inicia um processo. Separado para os testes não precisarem do ffmpeg. */
    @FunctionalInterface
    public interface Launcher {
        /** @return o processo iniciado */
        Process start(List<String> command) throws IOException;
    }

    private final VideoRelayConfig config;
    private final Launcher launcher;
    private final List<Duration> backoff;
    private final Duration healthyAfter;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Process> live = new ArrayList<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Cria o relé com o lançador real ({@link ProcessBuilder}) e a espera 5, 10, 20, 30 s. */
    public VideoRelay(VideoRelayConfig config) {
        this(config, command -> new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start(),
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(30)),
                Duration.ofSeconds(60));
    }

    /** Cria o relé com lançador e esperas próprios (testes). */
    public VideoRelay(VideoRelayConfig config, Launcher launcher, List<Duration> backoff, Duration healthyAfter) {
        this.config = config;
        this.launcher = launcher;
        this.backoff = backoff;
        this.healthyAfter = healthyAfter;
    }

    /** Linha de comando do ffmpeg de uma câmera: lê por TCP, copia sem recodificar e publica em {@code cam-<id>}. */
    static List<String> command(VideoRelayConfig config, VideoRelayConfig.Camera camera) {
        String target = withPublishCredentials(config.publishBase(), config.publishUser(), config.publishPassword().get())
                + "/cam-" + camera.deviceId();
        return List.of(config.ffmpeg(), "-hide_banner", "-loglevel", "error", "-nostdin",
                "-rtsp_transport", "tcp", "-i", camera.sourceUrl(),
                "-an", "-c:v", "copy", "-f", "rtsp", "-rtsp_transport", "tcp", target);
    }

    private static String withPublishCredentials(String base, String user, String password) {
        int i = base.indexOf("://") + 3;
        return base.substring(0, i) + encode(user) + ":" + encode(password) + "@" + base.substring(i);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Começa a publicar todas as câmeras configuradas. */
    public void start() {
        for (VideoRelayConfig.Camera camera : config.cameras()) {
            workers.submit(() -> supervise(camera));
        }
        log.info("Video relay started for {} camera(s) towards {}", config.cameras().size(), config.publishBase());
    }

    private void supervise(VideoRelayConfig.Camera camera) {
        int attempt = 0;
        while (running.get()) {
            long started = System.nanoTime();
            try {
                Process process = launcher.start(command(config, camera));
                synchronized (live) {
                    live.add(process);
                }
                int exit = process.waitFor();
                synchronized (live) {
                    live.remove(process);
                }
                if (running.get()) {
                    // nenhum endereço no log: só a câmera pela identificação e o código de saída
                    log.warn("Video relay for camera {} stopped (exit {}); will retry", camera.deviceId(), exit);
                }
            } catch (IOException e) {
                log.warn("Video relay for camera {} could not start ({}); will retry", camera.deviceId(), e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (Duration.ofNanos(System.nanoTime() - started).compareTo(healthyAfter) >= 0) {
                attempt = 0; // rodou bem por um tempo: a próxima falha recomeça da espera curta
            }
            Duration wait = backoff.get(Math.min(attempt++, backoff.size() - 1));
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Para a publicação e encerra os processos. */
    @Override
    public void close() {
        running.set(false);
        synchronized (live) {
            live.forEach(Process::destroy);
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
