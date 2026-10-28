package br.com.domus.platform.shared.error;

/** Recurso inexistente ou fora do escopo do chamador (indistinguíveis de propósito, contra IDOR/BOLA). */
public class NotFoundException extends RuntimeException {

    /** Construtor de NotFoundException; recebe as dependências por injeção. */
    public NotFoundException(String message) {
        super(message);
    }
}
