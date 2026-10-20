package br.com.portaria.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Política de contingência emitida pela central e verificada pelo agente.
 *
 * @param version          versão monotônica da política do condomínio
 * @param agentId          agente a que se destina
 * @param condominiumId    condomínio
 * @param issuedAt         emissão
 * @param validUntil       depois deste instante nenhuma entrada offline é aceita
 * @param allowOfflineEntry se a contingência pode aceitar credenciais
 * @param entrances        entradas em que a contingência vale
 * @param credentials      credenciais aceitáveis offline
 */
public record Policy(long version, UUID agentId, UUID condominiumId, Instant issuedAt, Instant validUntil,
                     boolean allowOfflineEntry, Set<String> entrances, List<Credential> credentials) {

    /**
     * @param id         identificador da credencial
     * @param sig        assinatura esperada no token (o segredo nunca sai da central)
     * @param validFrom  início da validade
     * @param validUntil fim da validade
     * @param maxUses    usos permitidos
     * @param usesSoFar  usos já consumidos na central quando a política foi emitida
     */
    public record Credential(UUID id, String sig, Instant validFrom, Instant validUntil, int maxUses, int usesSoFar) {
    }

    public Optional<Credential> find(UUID id) {
        return credentials.stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
