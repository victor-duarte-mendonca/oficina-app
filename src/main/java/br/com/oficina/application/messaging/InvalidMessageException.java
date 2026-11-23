package br.com.oficina.application.messaging;

/** Mensagem que não respeita o contrato: não é processada e segue o caminho de reentrega até a DLQ. */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
