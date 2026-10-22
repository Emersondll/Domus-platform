package br.com.domus.platform.shared.error;

/** Violação de regra de unicidade ou estado. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
