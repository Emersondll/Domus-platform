package br.com.domus.agent.update;

import java.time.Instant;

/**
 * Manifesto de uma versão do agente, assinado pela chave de lançamento (Ed25519). A central e o servidor de arquivos NÃO fazem
 * parte da cadeia de confiança: quem não tem a chave privada de lançamento não consegue produzir um manifesto aceito.
 *
 * @param version   versão (ex.: 1.4.0); só é aceita se for maior que a em execução (sem retrocesso)
 * @param channel   canal de distribuição (ex.: stable)
 * @param fileName  nome do jar no servidor ({@code [A-Za-z0-9._-]+.jar})
 * @param sha256    hash SHA-256 do jar, em hexadecimal minúsculo
 * @param size      tamanho do jar em bytes
 * @param notBefore instante a partir do qual a versão pode ser instalada (liberação escalonada)
 * @param signature assinatura Ed25519 (base64) de {@link #signedPayload()}
 */
public record UpdateManifest(String version, String channel, String fileName, String sha256, long size, Instant notBefore,
                             String signature) {

    /** Texto exato que é assinado; qualquer campo alterado invalida a assinatura. */
    public String signedPayload() {
        return String.join("|", "portaria-agent-update", "v1", version, channel, fileName, sha256, Long.toString(size),
                notBefore.toString());
    }
}
