package br.com.oficina.application.ports.in;

import br.com.oficina.application.dto.ApprovalDecisionDto;
import br.com.oficina.application.dto.ClientResponseDto;
import br.com.oficina.application.dto.ClientWorkOrderRequestDto;
import br.com.oficina.application.dto.VehicleResponseDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;
import br.com.oficina.application.security.AuthenticatedCaller;
import java.util.List;

/**
 * Porta de entrada da área do cliente ({@code /me}). O chamador entra como parâmetro explícito: o
 * id do cliente vem sempre do token, nunca do path ou do corpo.
 */
public interface ClientSelfServiceUseCase {

    ClientResponseDto profile(AuthenticatedCaller caller);

    List<VehicleResponseDto> vehicles(AuthenticatedCaller caller);

    List<WorkOrderResponseDto> listWorkOrders(AuthenticatedCaller caller, int page, int size);

    long countWorkOrders(AuthenticatedCaller caller);

    /** 404 (não 403) quando a OS pertence a outro cliente. */
    WorkOrderResponseDto findWorkOrder(AuthenticatedCaller caller, Long id);

    /** Reaproveita {@code CreateWorkOrderUseCase}; exige que o veículo pertença ao chamador. */
    WorkOrderResponseDto openWorkOrder(AuthenticatedCaller caller, ClientWorkOrderRequestDto request);

    /** Aprovar/recusar o orçamento; só vale em {@code AWAITING_APPROVAL} (regra do aggregate). */
    WorkOrderResponseDto decideApproval(AuthenticatedCaller caller, Long id, ApprovalDecisionDto decision);
}
