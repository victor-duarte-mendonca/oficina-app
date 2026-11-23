package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.BudgetGenerated;
import br.com.oficina.domain.saga.SagaEvent.PaymentApproved;
import java.util.Optional;

/**
 * Efeitos externos que chegam depois de a Saga desistir (spec 04 §5, regra 3). Exemplo clássico: o
 * cliente paga o Pix um instante depois de a cobrança expirar e a OS ser cancelada — o dinheiro entrou
 * e precisa voltar, mesmo sem a compensação estar na pilha.
 */
final class LateEvents {

    private LateEvents() {
    }

    static Optional<SagaDecision> compensateIfOrphan(WorkOrderSaga saga, SagaEvent event) {
        return switch (event) {
            case PaymentApproved approved when isOrphanPayment(saga, approved) -> Optional.of(
                saga.compensateOrphan(new SagaCommand.RefundPayment(approved.paymentId(),
                    "pagamento aprovado depois do cancelamento da OS"), "pagamento órfão " + approved.paymentId()));
            case BudgetGenerated generated when saga.budgetId() == null -> {
                saga.recordBudget(generated.budgetId());
                yield Optional.of(saga.compensateOrphan(new SagaCommand.VoidBudget(generated.budgetId(),
                    "orçamento gerado depois do cancelamento da OS"), "orçamento órfão " + generated.budgetId()));
            }
            default -> Optional.empty();
        };
    }

    /** Pagamento já capturado pela Saga tem estorno na pilha (ou já estornado): não é órfão. */
    private static boolean isOrphanPayment(WorkOrderSaga saga, PaymentApproved approved) {
        boolean alreadyCaptured = saga.paymentCaptured() && approved.paymentId().equals(saga.paymentId());
        return !alreadyCaptured;
    }
}
