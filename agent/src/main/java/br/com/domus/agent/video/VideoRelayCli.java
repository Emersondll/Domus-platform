package br.com.domus.agent.video;

import java.util.concurrent.CountDownLatch;

/**
 * Executa só a publicação de vídeo, sem o resto do agente (provas ponta a ponta e diagnóstico em campo). Configuração pelas
 * mesmas variáveis de ambiente do agente completo: {@code PORTARIA_VIDEO_CAMERAS}, {@code PORTARIA_VIDEO_PUBLISH_URL} e
 * {@code PORTARIA_VIDEO_PUBLISH_SECRET} (modo de segredo único) ou, sem ele, {@code PORTARIA_AGENT_ID} e {@code PORTARIA_AGENT_KEY}
 * (a identidade do agente, como no agente completo). Roda até receber um sinal de parada.
 */
public final class VideoRelayCli {

    private VideoRelayCli() {
    }

    public static void main(String[] args) throws InterruptedException {
        java.util.Map<String, String> env = System.getenv();
        String agentKey = env.get("PORTARIA_AGENT_KEY");
        java.util.UUID agentId = env.get("PORTARIA_AGENT_ID") == null ? null : java.util.UUID.fromString(env.get("PORTARIA_AGENT_ID"));
        VideoRelayConfig config = VideoRelayConfig.fromEnv(env, agentId, agentKey == null ? null : () -> agentKey)
                .orElseThrow(() -> new IllegalStateException("PORTARIA_VIDEO_CAMERAS is not set"));
        VideoRelay relay = new VideoRelay(config);
        Runtime.getRuntime().addShutdownHook(new Thread(relay::close));
        relay.start();
        new CountDownLatch(1).await();
    }
}
