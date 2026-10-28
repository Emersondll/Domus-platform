package br.com.domus.agent.video;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Configuração da publicação de vídeo, lida de variáveis de ambiente. Desligada quando não há câmeras configuradas.
 *
 * @param ffmpeg      executável do ffmpeg (padrão {@code ffmpeg} no PATH)
 * @param publishBase endereço RTSP do servidor de mídia da central onde o agente publica (sem credenciais)
 * @param publishSecret segredo de publicação do servidor de mídia ({@code MEDIA_PUBLISH_SECRET} da central)
 * @param cameras     câmeras locais a publicar
 */
public record VideoRelayConfig(String ffmpeg, String publishBase, String publishSecret, List<Camera> cameras) {

    /**
     * Uma câmera local a publicar.
     *
     * @param deviceId  identificador da câmera na central (o caminho publicado é {@code cam-<deviceId>})
     * @param sourceUrl endereço RTSP local, que pode conter usuário e senha; nunca vai a log
     */
    public record Camera(UUID deviceId, String sourceUrl) {
        @Override
        public String toString() {
            return "Camera[deviceId=" + deviceId + ", sourceUrl=***]";
        }
    }

    @Override
    public String toString() {
        return "VideoRelayConfig[publishBase=" + publishBase + ", cameras=" + cameras.size() + "]";
    }

    /**
     * Lê o ambiente. {@code PORTARIA_VIDEO_CAMERAS} = {@code uuid=rtsp://...;uuid=rtsp://...}.
     *
     * @param env variáveis de ambiente
     * @return a configuração, ou vazio se nenhuma câmera foi configurada
     */
    public static Optional<VideoRelayConfig> fromEnv(Map<String, String> env) {
        String raw = env.get("PORTARIA_VIDEO_CAMERAS");
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String base = required(env, "PORTARIA_VIDEO_PUBLISH_URL");
        URI uri = URI.create(base);
        if (!("rtsp".equals(uri.getScheme()) || "rtsps".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("PORTARIA_VIDEO_PUBLISH_URL must be rtsp(s)://host[:port] without credentials");
        }
        List<Camera> cameras = new ArrayList<>();
        for (String entry : raw.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq < 1) {
                throw new IllegalStateException("PORTARIA_VIDEO_CAMERAS entries must be uuid=rtsp-url");
            }
            String source = entry.substring(eq + 1).trim();
            URI s = URI.create(source);
            if (!("rtsp".equals(s.getScheme()) || "rtsps".equals(s.getScheme())) || s.getHost() == null) {
                throw new IllegalStateException("Camera source must be an rtsp(s) URL");
            }
            cameras.add(new Camera(UUID.fromString(entry.substring(0, eq).trim()), source));
        }
        return Optional.of(new VideoRelayConfig(env.getOrDefault("PORTARIA_FFMPEG", "ffmpeg"),
                base.endsWith("/") ? base.substring(0, base.length() - 1) : base,
                required(env, "PORTARIA_VIDEO_PUBLISH_SECRET"), List.copyOf(cameras)));
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return v;
    }
}
