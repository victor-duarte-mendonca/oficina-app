package br.com.oficina.infrastructure.configuration;

import br.com.oficina.application.service.ApprovalWorkflow;
import br.com.oficina.application.service.LegacyApprovalWorkflow;
import br.com.oficina.application.service.SagaApprovalWorkflow;
import br.com.oficina.domain.saga.SagaPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
import org.jboss.logging.Logger;

/**
 * Monta os objetos da Saga a partir da configuração. É aqui — e só aqui — que se decide qual
 * {@link ApprovalWorkflow} vale para a aplicação inteira.
 */
@ApplicationScoped
public class SagaBeans {

    private static final Logger LOG = Logger.getLogger(SagaBeans.class);

    @Produces
    @Singleton
    SagaPolicy sagaPolicy(SagaConfig config) {
        return new SagaPolicy(config.maxStepAttempts(), config.maxPaymentAttempts(), config.stepReplyTimeout(),
            config.approvalTimeout(), config.paymentTimeout());
    }

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    ApprovalWorkflow approvalWorkflow(SagaConfig config, Instance<LegacyApprovalWorkflow> legacy,
                                      Instance<SagaApprovalWorkflow> saga) {
        LOG.infof("Fluxo de aprovação: %s", config.enabled() ? "Saga (Fase 4)" : "local (Fase 3)");
        return config.enabled() ? saga.get() : legacy.get();
    }
}
