package br.com.portaria.agent.update;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuração da atualização automática (tudo por variável de ambiente). Desligada por padrão: só liga se
 * {@code PORTARIA_UPDATE_URL} <b>e</b> {@code PORTARIA_UPDATE_PUBLIC_KEY} estiverem definidos (sem a chave não há como confiar em nada).
 *
 * @param baseUrl   pasta com manifest.json e jars (HTTPS)
 * @param publicKey chave pública de lançamento (Ed25519/X.509, base64)
 * @param channel   canal (padrão stable)
 * @param firstCheck atraso da primeira verificação depois da partida (padrão 5 min)
 * @param interval  intervalo entre verificações (padrão 24 h)
 * @param jarPath   jar executado pelo systemd, que será substituído
 * @param pending   marcador de atualização não confirmada
 */
public record UpdateConfig(URI baseUrl, String publicKey, String channel, Duration firstCheck, Duration interval, Path jarPath, Path pending) {

    public static Optional<UpdateConfig> fromEnv(Path dataDir) {
        String url = System.getenv("PORTARIA_UPDATE_URL");
        String key = System.getenv("PORTARIA_UPDATE_PUBLIC_KEY");
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("PORTARIA_UPDATE_URL is set but PORTARIA_UPDATE_PUBLIC_KEY is missing");
        }
        return Optional.of(new UpdateConfig(URI.create(url.trim()), key.trim(),
                System.getenv().getOrDefault("PORTARIA_UPDATE_CHANNEL", "stable"),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("PORTARIA_UPDATE_FIRST_CHECK_SECONDS", "300"))),
                Duration.ofHours(Long.parseLong(System.getenv().getOrDefault("PORTARIA_UPDATE_CHECK_HOURS", "24"))),
                Path.of(System.getenv().getOrDefault("PORTARIA_JAR_PATH", dataDir.resolve("bin/agent.jar").toString())),
                dataDir.resolve("update-pending")));
    }
}
