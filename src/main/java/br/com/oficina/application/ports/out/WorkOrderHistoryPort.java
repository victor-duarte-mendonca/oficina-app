package br.com.oficina.application.ports.out;

import br.com.oficina.domain.model.StatusChange;
import java.util.List;

/**
 * Histórico de status da OS. O adapter completa {@code actor} e {@code correlationId} a partir do
 * contexto da requisição ou da mensagem em processamento.
 */
public interface WorkOrderHistoryPort {

    void record(StatusChange change);

    /** Transições da OS, da mais antiga para a mais recente. */
    List<StatusChange> findByWorkOrderId(Long workOrderId);
}
