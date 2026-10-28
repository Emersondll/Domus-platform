package br.com.domus.agent.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Publicação de vídeo do agente: configuração, linha de comando do ffmpeg e supervisão (sem câmera nem ffmpeg reais). */
class VideoRelayTest {

    private static final UUID CAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String SECRET = "publish-secret-Zk82";
    private static final String CAMERA_URL = "rtsp://admin:camera-pass-Qw17@192.168.0.20:554/stream1";

    private static VideoRelayConfig config() {
        return new VideoRelayConfig("ffmpeg", "rtsp://media.example.invalid:8554", SECRET,
                List.of(new VideoRelayConfig.Camera(CAM, CAMERA_URL)));
    }

    @Test
    void theCommandPullsTheLocalCameraAndPushesWithoutReencoding() {
        List<String> cmd = VideoRelay.command(config(), config().cameras().get(0));
        assertThat(cmd).containsSequence("-rtsp_transport", "tcp", "-i", CAMERA_URL);
        assertThat(cmd).containsSequence("-an", "-c:v", "copy", "-f", "rtsp");
        assertThat(cmd.get(cmd.size() - 1))
                .isEqualTo("rtsp://publisher:" + SECRET + "@media.example.invalid:8554/cam-" + CAM);
    }

    @Test
    void theSecretsNeverAppearWhenTheConfigIsPrinted() {
        assertThat(config().toString()).doesNotContain(SECRET).doesNotContain("camera-pass-Qw17");
        assertThat(config().cameras().get(0).toString()).doesNotContain("camera-pass-Qw17");
    }

    @Test
    void isOffUnlessCamerasAreConfigured() {
        assertThat(VideoRelayConfig.fromEnv(Map.of())).isEmpty();
        assertThat(VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", "  "))).isEmpty();
    }

    @Test
    void readsTheEnvironmentAndRefusesUnsafeOrIncompleteSettings() {
        Map<String, String> ok = Map.of("PORTARIA_VIDEO_CAMERAS", CAM + "=" + CAMERA_URL + ";",
                "PORTARIA_VIDEO_PUBLISH_URL", "rtsp://media.example.invalid:8554/", "PORTARIA_VIDEO_PUBLISH_SECRET", SECRET);
        VideoRelayConfig parsed = VideoRelayConfig.fromEnv(ok).orElseThrow();
        assertThat(parsed.cameras()).hasSize(1);
        assertThat(parsed.publishBase()).isEqualTo("rtsp://media.example.invalid:8554");
        assertThat(parsed.ffmpeg()).isEqualTo("ffmpeg");

        assertThatThrownBy(() -> VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", CAM + "=" + CAMERA_URL,
                "PORTARIA_VIDEO_PUBLISH_SECRET", SECRET))).hasMessageContaining("PORTARIA_VIDEO_PUBLISH_URL");
        assertThatThrownBy(() -> VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", CAM + "=" + CAMERA_URL,
                "PORTARIA_VIDEO_PUBLISH_URL", "rtsp://media.example.invalid:8554"))).hasMessageContaining("PORTARIA_VIDEO_PUBLISH_SECRET");
        assertThatThrownBy(() -> VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", CAM + "=" + CAMERA_URL,
                "PORTARIA_VIDEO_PUBLISH_URL", "rtsp://user:pw@media.example.invalid", "PORTARIA_VIDEO_PUBLISH_SECRET", SECRET)))
                .hasMessageContaining("without credentials");
        assertThatThrownBy(() -> VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", CAM + "=http://192.168.0.20/x",
                "PORTARIA_VIDEO_PUBLISH_URL", "rtsp://media.example.invalid", "PORTARIA_VIDEO_PUBLISH_SECRET", SECRET)))
                .hasMessageContaining("rtsp");
        assertThatThrownBy(() -> VideoRelayConfig.fromEnv(Map.of("PORTARIA_VIDEO_CAMERAS", "not-a-pair",
                "PORTARIA_VIDEO_PUBLISH_URL", "rtsp://media.example.invalid", "PORTARIA_VIDEO_PUBLISH_SECRET", SECRET)))
                .hasMessageContaining("uuid=");
    }

    @Test
    void restartsAnExitedProcessAndStopsWhenClosed() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        List<List<String>> commands = new CopyOnWriteArrayList<>();
        VideoRelay relay = new VideoRelay(config(), command -> {
            starts.incrementAndGet();
            commands.add(command);
            return new ProcessBuilder("sh", "-c", "exit 3").start(); // o "ffmpeg" cai na hora
        }, List.of(Duration.ofMillis(10)), Duration.ofHours(1));
        relay.start();
        waitUntil(() -> starts.get() >= 3);
        relay.close();
        int after = starts.get();
        Thread.sleep(150);
        assertThat(starts.get()).as("depois de fechar não sobe mais nenhum processo").isEqualTo(after);
        assertThat(commands).allSatisfy(c -> assertThat(c.get(c.size() - 1)).endsWith("/cam-" + CAM));
    }

    @Test
    void keepsRetryingWhenTheExecutableCannotBeStarted() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        VideoRelay relay = new VideoRelay(config(), command -> {
            attempts.incrementAndGet();
            throw new IOException("ffmpeg not found");
        }, List.of(Duration.ofMillis(10)), Duration.ofHours(1));
        relay.start();
        waitUntil(() -> attempts.get() >= 3);
        relay.close();
    }

    @Test
    void closingDestroysARunningProcess() throws Exception {
        List<Process> started = new CopyOnWriteArrayList<>();
        VideoRelay relay = new VideoRelay(config(), command -> {
            Process p = new ProcessBuilder("sleep", "30").start();
            started.add(p);
            return p;
        }, List.of(Duration.ofMillis(10)), Duration.ofHours(1));
        relay.start();
        waitUntil(() -> !started.isEmpty());
        relay.close();
        assertThat(started.get(0).waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
