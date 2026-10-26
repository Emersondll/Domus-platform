package br.com.domus.platform.devices.adapter;

import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.service.CameraStreamService;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Captura um quadro lendo o fluxo da câmera <i>pelo servidor de mídia</i> (o caminho {@code cam-<id>} que a visualização ao
 * vivo já usa) com o ffmpeg. A senha da câmera não aparece na linha de comando: o ffmpeg só recebe um token curto.
 */
@Component
class MediaSnapshotSource implements SnapshotSource {

    private static final int TIMEOUT_SECONDS = 6;

    private final CameraStreamService streams;
    private final String ffmpeg;

    MediaSnapshotSource(CameraStreamService streams, @Value("${portaria.snapshots.ffmpeg-path:ffmpeg}") String ffmpeg) {
        this.streams = streams;
        this.ffmpeg = ffmpeg;
    }

    @Override
    public boolean supports(Device camera) {
        return !"SIMULATOR".equals(camera.getAdapter()) && streams.canReadInternally(camera);
    }

    @Override
    public byte[] capture(Device camera) throws DeviceAdapter.DeviceCommunicationException {
        List<String> command = List.of(ffmpeg, "-v", "error", "-rtsp_transport", "tcp", "-i",
                streams.internalReadUrl(camera), "-frames:v", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new DeviceAdapter.DeviceCommunicationException("ffmpeg is not available");
        }
        try {
            process.getOutputStream().close();
            CompletableFuture<byte[]> frame = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            return frame.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (IOException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Could not start the capture");
        } catch (TimeoutException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Camera did not answer in time");
        } catch (ExecutionException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Could not read the camera image");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceAdapter.DeviceCommunicationException("Capture interrupted");
        } finally {
            process.destroyForcibly();
        }
    }
}
