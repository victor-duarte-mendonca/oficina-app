package br.com.oficina.domain.saga;

import java.util.Optional;

/**
 * {@code COMPENSATING} e {@code COMPENSATION_PENDING}: desfaz um efeito por vez, do mais recente para o
 * mais antigo. A lógica é genérica — cada {@link Compensation} sabe reconhecer a própria resposta.
 * Em {@code COMPENSATION_PENDING} uma confirmação atrasada ainda é aceita (o estorno pode ter dado
 * certo depois da última tentativa), mas uma nova recusa não gera retry automático.
 */
final class CompensatingHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        Optional<Compensation> next = saga.nextCompensation();
        if (next.isPresent()) {
            Compensation top = next.get();
            if (top.confirmedBy(event)) {
                return saga.confirmCompensation(ctx);
            }
            if (top.irrecoverableBy(event)) {
                return saga.park(event.type() + ": " + top + " não pode ser concluída automaticamente");
            }
            if (top.failedBy(event)) {
                return saga.state() == SagaState.COMPENSATION_PENDING
                    ? SagaDecision.ignored("nova recusa em COMPENSATION_PENDING; aguardando intervenção")
                    : saga.retryCompensationOrPark(ctx, event.type() + " em " + top);
            }
        }
        return LateEvents.compensateIfOrphan(saga, event)
            .orElseGet(() -> SagaStateHandler.unexpected(saga, event));
    }

    @Override
    public SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        return saga.retryCompensationOrPark(ctx, "sem resposta à compensação "
            + saga.nextCompensation().map(Enum::name).orElse("-"));
    }
}
