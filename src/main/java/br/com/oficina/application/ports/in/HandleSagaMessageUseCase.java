package br.com.oficina.application.ports.in;

import br.com.oficina.application.messaging.InboundMessage;

/** Porta de entrada do consumidor de mensagens: processa uma resposta dos participantes da Saga. */
public interface HandleSagaMessageUseCase {

    Result handle(InboundMessage message);

    enum Result {
        /** A Saga foi avaliada (avançou, compensou ou registrou o evento como ignorado). */
        PROCESSED,
        /** Mesma mensagem já processada antes: nada foi feito. */
        DUPLICATE,
        /** Informativo, tipo desconhecido ou Saga inexistente: confirmado sem mover a Saga. */
        SKIPPED
    }
}
