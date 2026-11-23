package br.com.oficina.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import br.com.oficina.infrastructure.configuration.MessagingConfig;
import br.com.oficina.infrastructure.configuration.SagaConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Agendamentos: o relay esvazia o outbox em lotes; uma Saga com erro no timeout não trava as outras. */
@ExtendWith(MockitoExtension.class)
class SagaJobsTest {

    @Mock OutboxPublisher publisher;
    @Mock SagaReplyConsumer consumer;
    @Mock SagaAdministrationUseCase sagas;
    @Mock MessagingConfig messaging;
    @Mock SagaConfig saga;

    private SagaJobs jobs() {
        return new SagaJobs(publisher, consumer, sagas, messaging, saga);
    }

    @Test
    void relay_shouldKeepPublishingWhileBatchesComeFull() {
        when(messaging.relayBatchSize()).thenReturn(50);
        when(publisher.publishBatch(50)).thenReturn(50, 50, 7);

        jobs().relayOutbox();

        verify(publisher, times(3)).publishBatch(50);
    }

    @Test
    void consumer_shouldPollOncePerExecution() {
        jobs().consumeReplies();
        verify(consumer).pollOnce();
    }

    @Test
    void timeoutJob_shouldIsolateFailuresPerSaga() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(saga.timeoutBatchSize()).thenReturn(50);
        when(sagas.overdueIds(50)).thenReturn(List.of(failing, next));
        doThrow(new IllegalStateException("conflito")).when(sagas).expire(failing);

        jobs().expireOverdueSagas();

        verify(sagas).expire(next);
    }

    @Test
    void skipPredicates_shouldFollowTheFeatureFlags() {
        when(messaging.enabled()).thenReturn(false);
        when(saga.enabled()).thenReturn(true);

        assertThat(new SagaJobs.MessagingDisabled(messaging).test(null)).isTrue();
        assertThat(new SagaJobs.SagaDisabled(saga).test(null)).isFalse();
    }
}
