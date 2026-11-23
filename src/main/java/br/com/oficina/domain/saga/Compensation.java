package br.com.oficina.domain.saga;

import br.com.oficina.domain.saga.SagaEvent.BudgetVoided;
import br.com.oficina.domain.saga.SagaEvent.ExecutionCancelled;
import br.com.oficina.domain.saga.SagaEvent.PaymentRefundFailed;
import br.com.oficina.domain.saga.SagaEvent.PaymentRefunded;

/**
 * Ação que desfaz o efeito de um passo já concluído. Cada passo bem-sucedido empilha a sua
 * compensação; ao falhar, a Saga as executa em ordem inversa (LIFO), uma por vez.
 *
 * <p>Cada constante sabe montar o seu comando e reconhecer a resposta que a confirma ou a recusa —
 * o handler de compensação fica genérico e um novo tipo de compensação não mexe nele.</p>
 */
public enum Compensation {

    VOID_BUDGET {
        @Override
        SagaCommand command(WorkOrderSaga saga, String reason) {
            return new SagaCommand.VoidBudget(saga.budgetId(), reason);
        }

        @Override
        boolean confirmedBy(SagaEvent event) {
            return event instanceof BudgetVoided;
        }
    },

    REFUND_PAYMENT {
        @Override
        SagaCommand command(WorkOrderSaga saga, String reason) {
            return new SagaCommand.RefundPayment(saga.paymentId(), reason);
        }

        @Override
        boolean confirmedBy(SagaEvent event) {
            return event instanceof PaymentRefunded;
        }

        @Override
        boolean failedBy(SagaEvent event) {
            return event instanceof PaymentRefundFailed;
        }
    },

    CANCEL_EXECUTION {
        @Override
        SagaCommand command(WorkOrderSaga saga, String reason) {
            return new SagaCommand.CancelExecution(reason);
        }

        @Override
        boolean confirmedBy(SagaEvent event) {
            return event instanceof ExecutionCancelled cancelled && !cancelled.alreadyFinished();
        }

        /** Serviço já concluído não se desfaz cancelando a tarefa: exige decisão humana. */
        @Override
        boolean irrecoverableBy(SagaEvent event) {
            return event instanceof ExecutionCancelled cancelled && cancelled.alreadyFinished();
        }
    };

    abstract SagaCommand command(WorkOrderSaga saga, String reason);

    abstract boolean confirmedBy(SagaEvent event);

    /** Recusa recuperável: tenta de novo até o limite da política. */
    boolean failedBy(SagaEvent event) {
        return false;
    }

    /** Recusa definitiva: vai direto para intervenção manual. */
    boolean irrecoverableBy(SagaEvent event) {
        return false;
    }
}
