package br.com.oficina.application.ports.out;

import br.com.oficina.application.messaging.OutboundMessage;

/**
 * Enfileira uma mensagem <b>na mesma transação</b> da mudança de estado que a originou (Transactional
 * Outbox). Se a transação desfaz, a mensagem nunca existiu; se confirma, o relay a publica — não há
 * janela em que o estado mudou e a mensagem se perdeu (nem o contrário).
 */
public interface OutboxPort {

    void enqueue(OutboundMessage message);
}
