package br.com.oficina.domain.saga;

/**
 * Comportamento da Saga em um estado (State pattern). Cada implementação conhece só os eventos que
 * fazem sentido no seu estado; o resto é ignorado e registrado — evento atrasado ou fora de ordem
 * nunca quebra a Saga.
 */
interface SagaStateHandler {

    SagaDecision on(WorkOrderSaga saga, SagaEvent event, SagaContext ctx);

    /** Prazo do passo venceu. Padrão: estados sem prazo não têm o que fazer. */
    default SagaDecision onTimeout(WorkOrderSaga saga, SagaContext ctx) {
        return SagaDecision.ignored("estado " + saga.state() + " não tem prazo");
    }

    static SagaDecision unexpected(WorkOrderSaga saga, SagaEvent event) {
        return SagaDecision.ignored(event.type() + " não se aplica ao estado " + saga.state());
    }
}
