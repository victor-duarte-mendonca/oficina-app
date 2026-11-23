package br.com.oficina.application.ports.in;

import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.security.AuthenticatedCaller;
import java.util.List;

/** Consulta do histórico de status da OS (R1: "consulta de status e histórico"). */
public interface WorkOrderHistoryUseCase {

    List<StatusHistoryEntryDto> history(Long workOrderId);

    /** Histórico de uma OS do próprio cliente; OS de outro cliente responde 404. */
    List<StatusHistoryEntryDto> historyForClient(AuthenticatedCaller caller, Long workOrderId);
}
