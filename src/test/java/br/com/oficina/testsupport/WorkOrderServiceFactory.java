package br.com.oficina.testsupport;

import br.com.oficina.application.ports.out.BusinessMetricsPort;
import br.com.oficina.application.ports.out.ClientRepositoryPort;
import br.com.oficina.application.ports.out.NotificationGatewayPort;
import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.application.ports.out.ServiceItemRepositoryPort;
import br.com.oficina.application.ports.out.VehicleRepositoryPort;
import br.com.oficina.application.ports.out.WorkOrderHistoryPort;
import br.com.oficina.application.ports.out.WorkOrderRepositoryPort;
import br.com.oficina.application.service.LegacyApprovalWorkflow;
import br.com.oficina.application.service.StatusNotifier;
import br.com.oficina.application.service.WorkOrderService;
import br.com.oficina.application.service.WorkOrderStock;
import br.com.oficina.application.service.WorkOrderTransitions;

/**
 * Monta o {@link WorkOrderService} com colaboradores reais (transições, estoque, notificação, fluxo da
 * Fase 3) sobre portas simuladas — os testes de unidade exercitam o comportamento completo do caso de
 * uso, não um esqueleto de mocks.
 */
public final class WorkOrderServiceFactory {

    private WorkOrderServiceFactory() {
    }

    public static WorkOrderService withLegacyWorkflow(WorkOrderRepositoryPort workOrders,
                                                      ClientRepositoryPort clients,
                                                      VehicleRepositoryPort vehicles,
                                                      PartRepositoryPort parts,
                                                      ServiceItemRepositoryPort serviceItems,
                                                      NotificationGatewayPort notifications,
                                                      BusinessMetricsPort metrics,
                                                      WorkOrderHistoryPort history) {
        WorkOrderTransitions transitions = new WorkOrderTransitions(workOrders, metrics, history);
        WorkOrderStock stock = new WorkOrderStock(parts);
        StatusNotifier notifier = new StatusNotifier(notifications, metrics);
        return new WorkOrderService(workOrders, clients, vehicles, parts, serviceItems, metrics, transitions, stock,
            notifier, new LegacyApprovalWorkflow(transitions, notifier, stock));
    }
}
