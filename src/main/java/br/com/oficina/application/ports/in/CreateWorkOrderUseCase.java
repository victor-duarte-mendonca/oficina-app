package br.com.oficina.application.ports.in;

import br.com.oficina.application.dto.WorkOrderCreateDto;
import br.com.oficina.application.dto.WorkOrderResponseDto;

/**
 * Porta de entrada (driver port) para abertura de Ordem de Serviço, incluindo o
 * registro inicial de serviços e peças informados na criação.
 */
public interface CreateWorkOrderUseCase {

    /** Quem abriu a OS — a única diferença entre os dois caminhos; a máquina de estados é a mesma. */
    enum Origin { CLIENT, STAFF }

    WorkOrderResponseDto create(WorkOrderCreateDto dto, Origin origin);

    /** Abertura pela oficina (rotas {@code /admin}). */
    default WorkOrderResponseDto create(WorkOrderCreateDto dto) {
        return create(dto, Origin.STAFF);
    }
}
