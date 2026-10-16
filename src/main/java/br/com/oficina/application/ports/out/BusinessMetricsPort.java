package br.com.oficina.application.ports.out;

import br.com.oficina.application.ports.in.CreateWorkOrderUseCase.Origin;
import br.com.oficina.domain.model.WorkOrderStatus;
import java.time.Duration;
import java.util.Optional;

/**
 * Porta de saída das métricas de negócio que alimentam os dashboards e alertas (R7). A aplicação
 * declara o que aconteceu; o adapter decide como medir. O domínio nunca chama esta porta: a
 * instrumentação mora na camada de aplicação, para o aggregate continuar sem framework.
 */
public interface BusinessMetricsPort {

    void workOrderCreated(Origin origin);

    /**
     * @param timeInFrom tempo que a OS passou em {@code from}, quando o instante de entrada é
     *                   conhecido — base do "tempo médio por status".
     */
    void statusTransition(WorkOrderStatus from, WorkOrderStatus to, Optional<Duration> timeInFrom);

    /** Falha no processamento de OS: base do alerta correspondente. */
    void transitionFailed(TransitionFailure reason);

    void integrationFailed(Integration integration);

    void authDenied(String reason);

    enum TransitionFailure { INVALID_TRANSITION, STOCK, PERSISTENCE }

    enum Integration { SMTP, DATABASE }
}
