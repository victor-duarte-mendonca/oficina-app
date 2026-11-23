package br.com.oficina.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.messaging.Destination;
import br.com.oficina.infrastructure.adapters.out.OutboxRepositoryAdapter;
import br.com.oficina.infrastructure.persistence.OutboxMessageEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Relay do outbox: publica em ordem e, na primeira falha, para o lote para não furar a ordem por OS. */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock OutboxRepositoryAdapter outbox;
    @Mock MessageTransport transport;

    @Test
    void publishBatch_shouldSendEveryPendingMessageAndMarkItPublished() {
        OutboxMessageEntity first = message("GenerateBudget");
        OutboxMessageEntity second = message("DiagnosisStarted");
        when(outbox.lockPending(50)).thenReturn(List.of(first, second));

        int published = new OutboxPublisher(outbox, transport).publishBatch(50);

        assertThat(published).isEqualTo(2);
        verify(transport).send(Destination.BILLING_COMMANDS, "wo-1", first.getId(), "{}");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void failure_shouldStopTheBatchAndRecordTheError() {
        OutboxMessageEntity failing = message("GenerateBudget");
        OutboxMessageEntity next = message("RequestPayment");
        when(outbox.lockPending(50)).thenReturn(List.of(failing, next));
        doThrow(new IllegalStateException("SQS indisponível"))
            .when(transport).send(any(), any(), eq(failing.getId()), any());

        int published = new OutboxPublisher(outbox, transport).publishBatch(50);

        assertThat(published).isZero();
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).isEqualTo("SQS indisponível");
        verify(transport, never()).send(any(), any(), eq(next.getId()), any());
    }

    private static OutboxMessageEntity message(String type) {
        return new OutboxMessageEntity(UUID.randomUUID(), Destination.BILLING_COMMANDS, "wo-1", type, "{}",
            OffsetDateTime.now());
    }
}
