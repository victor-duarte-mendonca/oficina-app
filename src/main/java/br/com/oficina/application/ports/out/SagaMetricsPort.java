package br.com.oficina.application.ports.out;

import br.com.oficina.domain.saga.SagaState;
import br.com.oficina.domain.saga.StepOutcome;
import java.time.Duration;

/**
 * Métricas da Saga para os dashboards e alertas da spec 13 (iniciadas × concluídas × compensadas,
 * duração, compensações que exigem intervenção). Porta separada das métricas de OS (ISP): quem só
 * registra transições de OS não depende do que é da Saga.
 */
public interface SagaMetricsPort {

    void sagaStarted();

    void stepRecorded(StepOutcome outcome);

    /** Saga chegou a {@code COMPLETED} ou {@code CANCELLED}. */
    void sagaClosed(SagaState finalState, Duration duration);
}
