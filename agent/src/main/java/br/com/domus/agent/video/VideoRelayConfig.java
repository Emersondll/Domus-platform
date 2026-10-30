package br.com.domus.agent.video;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Configuração da publicação de vídeo, lida de variáveis de ambiente. Desligada quando não há câmeras configuradas.
 *
 * @param ffmpeg      executável do ffmpeg (padrão {@code ffmpeg} no PATH)
 * @param publishBase endereço RTSP do servidor de mídia da central onde o agente publica (sem credenciais)
 * @param publishUser usuário com que o agente se apresenta ao publicar ({@code agent-<id>}; {@code publisher} no modo de segredo único)
 * @param publishPassword fornece a senha de publicação no momento de cada (re)início do ffmpeg: a chave do agente, que pode
 *                    ter sido rotacionada, ou o segredo único de publicação (fonte de teste)
 * @param cameras     câmeras locais a publicar
 */
public record VideoRelayConfig(String ffmpeg, String publishBase, String publishUser, Supplier<String> publishPassword,
                               List<Camera> cameras) {

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
     * Lê o ambiente no modo de segredo único (fonte de teste e diagnóstico): exige {@code PORTARIA_VIDEO_PUBLISH_SECRET}.
     * {@code PORTARIA_VIDEO_CAMERAS} = {@code uuid=rtsp://...;uuid=rtsp://...}.
     *
     * @param env variáveis de ambiente
     * @return a configuração, ou vazio se nenhuma câmera foi configurada
     */
    public static Optional<VideoRelayConfig> fromEnv(Map<String, String> env) {
        return fromEnv(env, null, null);
    }

    /**
     * Lê o ambiente do agente completo. Sem {@code PORTARIA_VIDEO_PUBLISH_SECRET}, o agente publica com a identidade e a chave
     * dele mesmo ({@code agent-<id>} e a chave atual): a central só aceita a câmera do próprio condomínio e nada é
     * compartilhado entre agentes. Com o segredo definido, vale o modo de segredo único.
     *
     * @param env variáveis de ambiente
     * @param agentId identidade do agente
     * @param agentKey chave atual do agente (lida a cada início do ffmpeg, pois pode ser rotacionada)
     * @return a configuração, ou vazio se nenhuma câmera foi configurada
     */
    public static Optional<VideoRelayConfig> fromEnv(Map<String, String> env, UUID agentId, Supplier<String> agentKey) {
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
        String shared = env.get("PORTARIA_VIDEO_PUBLISH_SECRET");
        boolean useShared = shared != null && !shared.isBlank();
        if (!useShared && agentId == null) {
            required(env, "PORTARIA_VIDEO_PUBLISH_SECRET");
        }
        return Optional.of(new VideoRelayConfig(env.getOrDefault("PORTARIA_FFMPEG", "ffmpeg"),
                base.endsWith("/") ? base.substring(0, base.length() - 1) : base,
                useShared ? "publisher" : "agent-" + agentId, useShared ? () -> shared : agentKey, List.copyOf(cameras)));
    }

    private static String required(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return v;
    }
}
