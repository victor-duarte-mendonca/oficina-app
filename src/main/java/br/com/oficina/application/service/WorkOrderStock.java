package br.com.oficina.application.service;

import br.com.oficina.application.ports.out.PartRepositoryPort;
import br.com.oficina.domain.model.WorkOrder;
import br.com.oficina.domain.model.WorkOrderPart;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Devolução de estoque das peças da OS (coordenação entre os agregados {@code WorkOrder} e
 * {@code Part}). É a compensação <em>local</em> do OS: roda em todo caminho que termina em
 * {@code CANCELLED}, seja o cancelamento manual, a recusa do orçamento ou a compensação da Saga.
 */
@ApplicationScoped
public class WorkOrderStock {

    private final PartRepositoryPort partRepository;

    public WorkOrderStock(PartRepositoryPort partRepository) {
        this.partRepository = partRepository;
    }

    /** Devolve ao estoque a quantidade de uma linha removida. */
    public void restore(WorkOrderPart line) {
        partRepository.fetchById(line.getPartId())
            .ifPresent(part -> {
                part.increaseStock(line.getQuantity());
                partRepository.save(part);
            });
    }

    public void restoreAll(WorkOrder workOrder) {
        workOrder.getParts().forEach(this::restore);
    }
}
