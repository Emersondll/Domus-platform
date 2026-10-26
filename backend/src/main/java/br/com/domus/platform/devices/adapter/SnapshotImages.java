package br.com.domus.platform.devices.adapter;

import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

/** Normaliza a imagem de uma câmera: decodifica (recusa o que não é imagem), limita a 640 px e recomprime em JPEG ≤ 200 KB. */
public final class SnapshotImages {

    public static final int MAX_SIDE = 640;
    public static final int MAX_BYTES = 200 * 1024;

    private SnapshotImages() {
    }

    public static byte[] normalize(byte[] raw) throws DeviceAdapter.DeviceCommunicationException {
        if (raw == null || raw.length == 0) {
            throw new DeviceAdapter.DeviceCommunicationException("Camera returned no image");
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(raw));
            if (source == null) {
                throw new DeviceAdapter.DeviceCommunicationException("Camera did not return a valid image");
            }
            BufferedImage image = scale(source);
            for (float quality = 0.75f; quality >= 0.3f; quality -= 0.15f) {
                byte[] jpeg = jpeg(image, quality);
                if (jpeg.length <= MAX_BYTES) {
                    return jpeg;
                }
            }
            throw new DeviceAdapter.DeviceCommunicationException("Image is too large");
        } catch (IOException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Camera did not return a valid image");
        }
    }

    private static BufferedImage scale(BufferedImage source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        double ratio = longest > MAX_SIDE ? (double) MAX_SIDE / longest : 1.0;
        int w = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); // sem canal alfa: exigência do JPEG
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
