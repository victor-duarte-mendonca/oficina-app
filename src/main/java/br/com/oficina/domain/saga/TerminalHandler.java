package br.com.oficina.domain.saga;

/**
 * {@code COMPLETED} e {@code CANCELLED}: nada avança. Só um efeito externo que chegou depois do
 * cancelamento (orçamento gerado ou pagamento aprovado tardiamente) é desfeito à parte.
 */
final class TerminalHandler implements SagaStateHandler {

    @Override
    public SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx) {
        if (saga.state() == SagaState.CANCELLED) {
            return LateEvents.compensateIfOrphan(saga, event)
                .orElseGet(() -> SagaStateHandler.unexpected(saga, event));
        }
        return SagaStateHandler.unexpected(saga, event);
    }
}
