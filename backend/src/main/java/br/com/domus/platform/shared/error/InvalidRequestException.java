package br.com.domus.platform.shared.error;

/**
 * Pedido recusado por regra de entrada; a mensagem é escrita para quem pediu e nunca carrega dado pessoal. Estende
 * {@link IllegalArgumentException} para não mudar quem já a captura; o tratador global devolve a mensagem no 400.
 * Defesas anti-SSRF (HostPolicy) continuam lançando a genérica de propósito: a resposta não pode servir de oráculo.
 */
public class InvalidRequestException extends IllegalArgumentException {

    /** Construtor de InvalidRequestException; recebe a mensagem mostrada a quem fez o pedido. */
    public InvalidRequestException(String message) {
        super(message);
    }
}
