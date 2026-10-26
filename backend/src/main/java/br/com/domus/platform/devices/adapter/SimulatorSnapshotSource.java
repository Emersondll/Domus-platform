package br.com.domus.platform.devices.adapter;

import br.com.domus.platform.devices.entity.Device;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Imagem sintética para câmeras do adaptador SIMULATOR (desenvolvimento e testes). Nunca parece uma foto real: é um
 * padrão listrado com a legenda "IMAGEM SIMULADA".
 */
@Component
class SimulatorSnapshotSource implements SnapshotSource {

    private final Clock clock;

    SimulatorSnapshotSource(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean supports(Device camera) {
        return "SIMULATOR".equals(camera.getAdapter());
    }

    @Override
    public byte[] capture(Device camera) throws DeviceAdapter.DeviceCommunicationException {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(20, 40, 80), 640, 360, new Color(60, 110, 150)));
        g.fillRect(0, 0, 640, 360);
        g.setColor(new Color(255, 255, 255, 40));
        for (int x = -360; x < 640; x += 40) {
            g.drawLine(x, 360, x + 360, 0);
        }
        try { // sem fontes no sistema (contêiner enxuto) a legenda cai fora; o padrão listrado continua
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
            g.drawString("IMAGEM SIMULADA", 150, 170);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            g.drawString(camera.getName() + " · " + Instant.now(clock), 90, 210);
        } catch (RuntimeException | Error e) {
            // legenda opcional
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Could not render the simulated image");
        }
        return out.toByteArray();
    }
}
