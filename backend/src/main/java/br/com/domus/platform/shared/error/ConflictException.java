package br.com.domus.platform.shared.error;

/** Violação de regra de unicidade ou estado. */
public class ConflictException extends RuntimeException {

    /** Construtor de ConflictException; recebe as dependências por injeção. */
    public ConflictException(String message) {
        super(message);
    }
}
