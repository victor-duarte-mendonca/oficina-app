package br.com.oficina.infrastructure.messaging;

import br.com.oficina.application.ports.in.SagaAdministrationUseCase;
import br.com.oficina.infrastructure.configuration.MessagingConfig;
import br.com.oficina.infrastructure.configuration.SagaConfig;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Agendamentos da Saga: relay do outbox, consumo das respostas e prazos. Cada um é pulado quando a
 * parte correspondente está desligada — dev/test sem AWS não tocam no SQS. Execuções nunca se
 * sobrepõem ({@code SKIP}); com várias réplicas, o {@code SKIP LOCKED} do outbox e a trava otimista da
 * Saga evitam trabalho dobrado.
 */
@ApplicationScoped
public class SagaJobs {

    private static final Logger LOG = Logger.getLogger(SagaJobs.class);

    private final OutboxPublisher publisher;
    private final SagaReplyConsumer consumer;
    private final SagaAdministrationUseCase sagas;
    private final MessagingConfig messaging;
    private final SagaConfig saga;

    public SagaJobs(OutboxPublisher publisher, SagaReplyConsumer consumer, SagaAdministrationUseCase sagas,
                    MessagingConfig messaging, SagaConfig saga) {
        this.publisher = publisher;
        this.consumer = consumer;
        this.sagas = sagas;
        this.messaging = messaging;
        this.saga = saga;
    }

    @Scheduled(identity = "outbox-relay", every = "${app.messaging.relay-interval:1s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void relayOutbox() {
        int batch = messaging.relayBatchSize();
        int published;
        do {
            published = publisher.publishBatch(batch);
        } while (published == batch);
    }

    @Scheduled(identity = "saga-replies", every = "${app.messaging.poll-interval:1s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = MessagingDisabled.class)
    void consumeReplies() {
        consumer.pollOnce();
    }

    @Scheduled(identity = "saga-timeouts", every = "${app.saga.timeout-scan-interval:10s}",
        concurrentExecution = ConcurrentExecution.SKIP, skipExecutionIf = SagaDisabled.class)
    void expireOverdueSagas() {
        for (UUID sagaId : sagas.overdueIds(saga.timeoutBatchSize())) {
            try {
                sagas.expire(sagaId);
            } catch (RuntimeException e) {
                LOG.errorf(e, "Falha ao aplicar o timeout da Saga %s; nova tentativa no próximo ciclo", sagaId);
            }
        }
    }

    @Singleton
    public static class MessagingDisabled implements Scheduled.SkipPredicate {

        private final MessagingConfig config;

        public MessagingDisabled(MessagingConfig config) {
            this.config = config;
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !config.enabled();
        }
    }

    @Singleton
    public static class SagaDisabled implements Scheduled.SkipPredicate {

        private final SagaConfig config;

        public SagaDisabled(SagaConfig config) {
            this.config = config;
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !config.enabled();
        }
    }
}
