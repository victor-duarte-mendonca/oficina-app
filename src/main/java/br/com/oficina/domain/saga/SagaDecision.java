package br.com.oficina.domain.saga;

import java.util.List;

/**
 * Saída de um passo da Saga: os comandos a enviar, a transição a aplicar na OS e o registro do passo.
 * Valor imutável: a aplicação só executa o que o domínio decidiu.
 */
public record SagaDecision(StepOutcome outcome, List<SagaCommand> commands, OrderEffect effect, String detail) {

    public SagaDecision {
        commands = List.copyOf(commands);
    }

    static SagaDecision of(StepOutcome outcome, SagaCommand command, OrderEffect effect) {
        return new SagaDecision(outcome, List.of(command), effect, null);
    }

    static SagaDecision of(StepOutcome outcome, OrderEffect effect, String detail) {
        return new SagaDecision(outcome, List.of(), effect, detail);
    }

    static SagaDecision ignored(String detail) {
        return new SagaDecision(StepOutcome.IGNORED, List.of(), OrderEffect.NONE, detail);
    }

    SagaDecision withDetail(String newDetail) {
        return new SagaDecision(outcome, commands, effect, newDetail);
    }

    public boolean isIgnored() {
        return outcome == StepOutcome.IGNORED;
    }
}
