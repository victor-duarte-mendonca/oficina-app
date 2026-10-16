package br.com.oficina.infrastructure.adapters.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.application.ports.in.CreateWorkOrderUseCase.Origin;
import br.com.oficina.application.ports.out.BusinessMetricsPort.Integration;
import br.com.oficina.application.ports.out.BusinessMetricsPort.TransitionFailure;
import br.com.oficina.domain.model.WorkOrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerBusinessMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerBusinessMetrics metrics = new MicrometerBusinessMetrics(registry);

    @Test
    void workOrderCreated_shouldCountPerOrigin() {
        metrics.workOrderCreated(Origin.CLIENT);
        metrics.workOrderCreated(Origin.CLIENT);
        metrics.workOrderCreated(Origin.STAFF);

        assertThat(registry.get("oficina.work_orders.created").tag("origin", "client").counter().count()).isEqualTo(2);
        assertThat(registry.get("oficina.work_orders.created").tag("origin", "staff").counter().count()).isEqualTo(1);
    }

    @Test
    void statusTransition_shouldCountTheFunnelAndTimeTheStatusLeft() {
        metrics.statusTransition(WorkOrderStatus.IN_DIAGNOSIS, WorkOrderStatus.AWAITING_APPROVAL,
            Optional.of(Duration.ofMinutes(20)));

        assertThat(registry.get("oficina.work_order.status.transitions")
            .tag("from", "IN_DIAGNOSIS").tag("to", "AWAITING_APPROVAL").counter().count()).isEqualTo(1);
        var timer = registry.get("oficina.work_order.status.duration").tag("status", "IN_DIAGNOSIS").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MINUTES)).isEqualTo(20);
    }

    @Test
    void statusTransition_withoutKnownDuration_shouldCountButNotTime() {
        metrics.statusTransition(WorkOrderStatus.RECEIVED, WorkOrderStatus.IN_DIAGNOSIS, Optional.empty());

        assertThat(registry.get("oficina.work_order.status.transitions").counter().count()).isEqualTo(1);
        assertThat(registry.find("oficina.work_order.status.duration").timer()).isNull();
    }

    @Test
    void failuresAndDenials_shouldUseLowercaseClosedTags() {
        metrics.transitionFailed(TransitionFailure.INVALID_TRANSITION);
        metrics.integrationFailed(Integration.SMTP);
        metrics.authDenied("forbidden");

        assertThat(registry.get("oficina.work_order.transition.errors").tag("reason", "invalid_transition")
            .counter().count()).isEqualTo(1);
        assertThat(registry.get("oficina.integration.failures").tag("integration", "smtp")
            .counter().count()).isEqualTo(1);
        assertThat(registry.get("oficina.auth.denied").tag("reason", "forbidden").counter().count()).isEqualTo(1);
    }
}
