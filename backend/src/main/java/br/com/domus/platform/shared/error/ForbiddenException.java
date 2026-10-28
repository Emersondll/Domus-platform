package br.com.domus.platform.shared.error;

/** Chamador identificado, mas sem permissão para a operação. */
public class ForbiddenException extends RuntimeException {

    /** Construtor de ForbiddenException; recebe as dependências por injeção. */
    public ForbiddenException(String message) {
        super(message);
    }
}
