package br.com.oficina.infrastructure.adapters.out.metrics;

import br.com.oficina.application.ports.in.CreateWorkOrderUseCase.Origin;
import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.domain.model.WorkOrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Adapter Micrometer das métricas de negócio, exposto em {@code /q/metrics}. Os nomes pontuados
 * viram os do Prometheus: {@code oficina.work_orders.created} → {@code oficina_work_orders_created_total}.
 * As tags têm cardinalidade fechada (enums), nunca ids nem texto livre.
 */
@ApplicationScoped
public class MicrometerBusinessMetrics implements BusinessMetricsPort {

    private final MeterRegistry registry;

    public MicrometerBusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void workOrderCreated(Origin origin) {
        registry.counter("oficina.work_orders.created", "origin", lower(origin)).increment();
    }

    @Override
    public void statusTransition(WorkOrderStatus from, WorkOrderStatus to, Optional<Duration> timeInFrom) {
        registry.counter("oficina.work_order.status.transitions",
            "from", from.name(), "to", to.name()).increment();
        // O timer mede o tempo no status que a OS deixou: é o "tempo médio por status" do dashboard.
        timeInFrom.ifPresent(duration -> registry.timer("oficina.work_order.status.duration",
            "status", from.name()).record(duration));
    }

    @Override
    public void transitionFailed(TransitionFailure reason) {
        registry.counter("oficina.work_order.transition.errors", "reason", lower(reason)).increment();
    }

    @Override
    public void integrationFailed(Integration integration) {
        registry.counter("oficina.integration.failures", "integration", lower(integration)).increment();
    }

    @Override
    public void authDenied(String reason) {
        registry.counter("oficina.auth.denied", "reason", reason).increment();
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
