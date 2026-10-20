package br.com.portaria.platform.shared.error;

/** Recurso inexistente ou fora do escopo do chamador (indistinguíveis de propósito, contra IDOR/BOLA). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
