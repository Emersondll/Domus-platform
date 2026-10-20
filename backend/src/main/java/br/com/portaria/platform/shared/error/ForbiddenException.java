package br.com.portaria.platform.shared.error;

/** Chamador identificado, mas sem permissão para a operação. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
