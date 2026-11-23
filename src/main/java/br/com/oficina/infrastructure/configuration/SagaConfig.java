package br.com.oficina.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

/**
 * Configuração da Saga ({@code app.saga.*}). Desligada por padrão: o fluxo da Fase 3 continua valendo
 * até Billing e Execution estarem no ar; ligar é uma variável de ambiente ({@code APP_SAGA_ENABLED}).
 */
@ConfigMapping(prefix = "app.saga")
public interface SagaConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("3")
    int maxStepAttempts();

    @WithDefault("3")
    int maxPaymentAttempts();

    @WithDefault("30s")
    Duration stepReplyTimeout();

    @WithDefault("72h")
    Duration approvalTimeout();

    @WithDefault("30m")
    Duration paymentTimeout();

    /** Sagas vencidas avaliadas por execução do job de prazos. */
    @WithDefault("50")
    int timeoutBatchSize();
}
