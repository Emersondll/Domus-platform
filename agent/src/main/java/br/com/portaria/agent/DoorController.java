package br.com.portaria.agent;

/**
 * Controladora local da porta. O contrato é deliberadamente simples: {@code open} só retorna normalmente se o
 * equipamento confirmou a execução; qualquer dúvida é {@link DoorException}.
 */
public interface DoorController {

    /** A controladora está indisponível ou não confirmou a abertura. */
    class DoorException extends Exception {
        public DoorException(String message) {
            super(message);
        }
    }

    void open(String entrance) throws DoorException;
}
