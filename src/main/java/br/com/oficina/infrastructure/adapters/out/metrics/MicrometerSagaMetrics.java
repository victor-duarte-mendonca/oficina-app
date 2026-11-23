package br.com.oficina.infrastructure.adapters.out.metrics;

import br.com.oficina.application.ports.out.SagaMetricsPort;
import br.com.oficina.domain.saga.SagaState;
import br.com.oficina.domain.saga.StepOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Locale;

/**
 * Métricas da Saga em {@code /q/metrics} (spec 13 §3). Tags de baixa cardinalidade apenas (desfecho e
 * estado final) — o motivo de cada compensação fica no log da Saga, não em tag.
 */
@ApplicationScoped
public class MicrometerSagaMetrics implements SagaMetricsPort {

    private final MeterRegistry registry;

    public MicrometerSagaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void sagaStarted() {
        registry.counter("oficina_saga_started_total").increment();
    }

    @Override
    public void stepRecorded(StepOutcome outcome) {
        registry.counter("oficina_saga_steps_total", "outcome", tag(outcome)).increment();
        if (outcome == StepOutcome.COMPENSATION_PENDING) {
            registry.counter("oficina_saga_compensation_pending_total").increment();
        }
    }

    @Override
    public void sagaClosed(SagaState finalState, Duration duration) {
        String state = tag(finalState);
        registry.counter("oficina_saga_closed_total", "state", state).increment();
        registry.timer("oficina_saga_duration", "state", state).record(duration);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
