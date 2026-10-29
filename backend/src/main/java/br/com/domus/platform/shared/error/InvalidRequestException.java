package br.com.domus.platform.shared.error;

/** Pedido recusado por regra de entrada; a mensagem é escrita para quem pediu e nunca carrega dado pessoal. */
public class InvalidRequestException extends RuntimeException {

    /** Construtor de InvalidRequestException; recebe a mensagem mostrada a quem fez o pedido. */
    public InvalidRequestException(String message) {
        super(message);
    }
}
