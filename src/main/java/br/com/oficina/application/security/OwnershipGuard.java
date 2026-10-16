package br.com.oficina.application.security;

import br.com.oficina.domain.exception.ResourceNotFoundException;
import br.com.oficina.domain.model.Vehicle;
import br.com.oficina.domain.model.WorkOrder;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Regra de posse do recurso: um cliente só enxerga o que é dele. Vive aqui — e não no controller nem
 * repetida em cada método de serviço — para existir um único ponto a auditar.
 *
 * <p>Recurso de outro cliente responde <b>404, não 403</b>: um 403 confirmaria que o id existe e
 * permitiria enumerar o volume de ordens da oficina. A mensagem é idêntica à de recurso
 * inexistente, de propósito.</p>
 */
@ApplicationScoped
public class OwnershipGuard {

    public void assertOwns(AuthenticatedCaller caller, WorkOrder workOrder) {
        if (!caller.clientId().equals(workOrder.getCustomer().clientId())) {
            throw new ResourceNotFoundException("Ordem de Serviço", workOrder.getId());
        }
    }

    public void assertOwns(AuthenticatedCaller caller, Vehicle vehicle) {
        if (!caller.clientId().equals(vehicle.getClientId())) {
            throw new ResourceNotFoundException("Veículo", vehicle.getId());
        }
    }
}
