package br.com.oficina.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.messaging.InboundMessage;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase;
import br.com.oficina.application.ports.in.HandleSagaMessageUseCase.Result;
import br.com.oficina.infrastructure.messaging.MessageTransport.ReceivedMessage;
import br.com.oficina.infrastructure.observability.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Consumidor das respostas: confirma só depois de processar; mensagem malformada ou falha no caso de
 * uso não é confirmada (volta à fila e, no limite, vai para a DLQ). A correlação da mensagem vale
 * durante o processamento e é limpa depois.
 */
@ExtendWith(MockitoExtension.class)
class SagaReplyConsumerTest {

    private static final String VALID = """
        {"messageId":"6f1c2a10-0000-4000-8000-000000000009","type":"PaymentApproved","version":1,"kind":"EVENT",
         "sagaId":"0b7d2f5e-1111-4000-8000-000000000001","workOrderId":1,"correlationId":"req-42",
         "occurredAt":"2026-11-23T14:10:00Z","payload":{"paymentId":"p-1","amount":"450.00"}}
        """;

    @Mock MessageTransport transport;
    @Mock HandleSagaMessageUseCase handler;

    private SagaReplyConsumer consumer() {
        return new SagaReplyConsumer(transport, new MessageEnvelopeCodec(new ObjectMapper()), handler);
    }

    @Test
    void processedMessage_shouldBeAcknowledgedWithItsCorrelation() {
        ReceivedMessage received = new ReceivedMessage(VALID, "r-1", 1);
        when(transport.receive()).thenReturn(List.of(received));
        AtomicReference<String> correlationSeen = new AtomicReference<>();
        when(handler.handle(any())).thenAnswer(invocation -> {
            correlationSeen.set(RequestContext.correlationId().orElse(null));
            return Result.PROCESSED;
        });

        int acknowledged = consumer().pollOnce();

        assertThat(acknowledged).isEqualTo(1);
        verify(transport).acknowledge(received);
        assertThat(correlationSeen).hasValue("req-42");
        assertThat(RequestContext.correlationId()).isEmpty();
    }

    @Test
    void malformedMessage_shouldNotBeAcknowledged() {
        ReceivedMessage received = new ReceivedMessage("{\"type\": 1", "r-2", 3);
        when(transport.receive()).thenReturn(List.of(received));

        assertThat(consumer().pollOnce()).isZero();

        verify(handler, never()).handle(any(InboundMessage.class));
        verify(transport, never()).acknowledge(received);
    }

    @Test
    void failureInTheUseCase_shouldLeaveTheMessageForRedelivery() {
        ReceivedMessage received = new ReceivedMessage(VALID, "r-3", 1);
        when(transport.receive()).thenReturn(List.of(received));
        when(handler.handle(any())).thenThrow(new IllegalStateException("conflito de versão"));

        assertThat(consumer().pollOnce()).isZero();

        verify(transport, never()).acknowledge(received);
        assertThat(RequestContext.correlationId()).isEmpty();
    }
}
