package br.com.domus.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cliente da API do agente na central. As falhas são tipadas: o chamador distingue rede, serviço e credencial. */
public interface CentralClient {

    /** Sem rede: DNS, conexão recusada, timeout. */
    class UnreachableException extends Exception {
        public UnreachableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A central respondeu 5xx/429: indisponível no momento. */
    class UnavailableException extends Exception {
        public UnavailableException(String message) {
            super(message);
        }
    }

    /** A central recusou as credenciais do agente (401/403). */
    class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }
    }

    /** Política recebida (ainda não verificada). */
    record PolicyEnvelope(String policy, String signature) {
    }

    /** Resultado da central para um evento enviado. */
    record EventResult(UUID eventId, String status, String detail) {
    }

    /** Comando de abertura buscado na central. */
    record Command(UUID commandId, UUID deviceId, String entrance, java.time.Instant expiresAt) {
    }

    Optional<PolicyEnvelope> fetchPolicy(Long sinceVersion, Long ageSeconds, int backlog)
            throws UnreachableException, UnavailableException, AuthException;

    List<EventResult> postEvents(List<OutboxEvent> events, int backlog)
            throws UnreachableException, UnavailableException, AuthException;

    List<Command> fetchCommands(int backlog) throws UnreachableException, UnavailableException, AuthException;

    /** Troca a chave do agente; devolve a nova (a anterior vale por uma janela de transição). */
    String rotateKey() throws UnreachableException, UnavailableException, AuthException;
}
