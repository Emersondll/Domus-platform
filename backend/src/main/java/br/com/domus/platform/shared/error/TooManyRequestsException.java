package br.com.domus.platform.shared.error;

/** Limite de frequência de uma ação sensível excedido. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
