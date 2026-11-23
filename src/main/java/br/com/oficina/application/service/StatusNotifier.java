package br.com.oficina.application.service;

import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.application.ports.out.BusinessMetricsPort.Integration;
import br.com.oficina.application.ports.out.NotificationGatewayPort;
import br.com.oficina.domain.event.WorkOrderStatusChangedEvent;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Notifica o cliente sobre uma transição já persistida, sem deixar uma falha de notificação abortar a
 * transação: a mudança de status é a invariante de negócio; o e-mail é efeito colateral. O adapter de
 * e-mail já engole erros de SMTP, mas o boundary transacional é aqui — este try/catch garante que
 * qualquer implementação de {@code NotificationGatewayPort} que venha a propagar uma exceção não force
 * o rollback do {@code save} anterior (defesa em profundidade).
 */
@ApplicationScoped
public class StatusNotifier {

    private static final Logger LOG = Logger.getLogger(StatusNotifier.class);

    private final NotificationGatewayPort notificationGateway;
    private final BusinessMetricsPort metrics;

    public StatusNotifier(NotificationGatewayPort notificationGateway, BusinessMetricsPort metrics) {
        this.notificationGateway = notificationGateway;
        this.metrics = metrics;
    }

    public void notifyClient(WorkOrder saved) {
        try {
            notificationGateway.notifyStatusChange(WorkOrderStatusChangedEvent.of(saved));
        } catch (RuntimeException e) {
            metrics.integrationFailed(Integration.SMTP);
            LOG.errorf(e, "Falha ao notificar mudança de status da OS %s; transição preservada.",
                saved.getOrderNumber());
        }
    }
}
