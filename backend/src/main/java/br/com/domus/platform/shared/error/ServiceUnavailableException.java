package br.com.domus.platform.shared.error;

/** Um sistema externo de que a operação depende não respondeu ou recusou o pedido (vira HTTP 503). */
public class ServiceUnavailableException extends RuntimeException {

    /** Construtor de ServiceUnavailableException. */
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
