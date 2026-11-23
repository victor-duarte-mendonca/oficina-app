package br.com.oficina.infrastructure.messaging;

import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase;
import br.com.oficina.infrastructure.messaging.MessageTransport.ReceivedMessage;
import br.com.oficina.infrastructure.observability.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Processa as respostas dos participantes. A mensagem só é confirmada (apagada da fila) depois de a
 * transação do caso de uso confirmar; qualquer falha deixa a mensagem voltar — até o limite de
 * reentregas da fila, quando o SQS a move para a DLQ (spec 03 §5).
 */
@ApplicationScoped
public class SagaReplyConsumer {

    private static final Logger LOG = Logger.getLogger(SagaReplyConsumer.class);

    private final MessageTransport transport;
    private final MessageEnvelopeCodec codec;
    private final HandleSagaMessageUseCase handler;

    public SagaReplyConsumer(MessageTransport transport, MessageEnvelopeCodec codec, HandleSagaMessageUseCase handler) {
        this.transport = transport;
        this.codec = codec;
        this.handler = handler;
    }

    /** @return quantas mensagens foram confirmadas */
    public int pollOnce() {
        int acknowledged = 0;
        for (ReceivedMessage received : transport.receive()) {
            if (process(received)) {
                acknowledged++;
            }
        }
        return acknowledged;
    }

    boolean process(ReceivedMessage received) {
        try {
            InboundMessage message = codec.decode(received.body());
            RequestContext.putCorrelationId(message.correlationId());
            HandleSagaMessageUseCase.Result result = handler.handle(message);
            transport.acknowledge(received);
            LOG.debugf("Mensagem %s (%s): %s", message.messageId(), message.type(), result);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "Falha ao processar mensagem (entrega %d); ela voltará à fila", received.receiveCount());
            return false;
        } finally {
            RequestContext.clearCorrelationId();
        }
    }
}
