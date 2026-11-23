package br.com.oficina.domain.saga;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma linha da trilha de auditoria da Saga: o que chegou (evento, timeout, cancelamento), o que foi
 * decidido e em que estado a Saga ficou. É o que o vídeo mostra para provar a compensação.
 *
 * @param step      tipo do evento ou do gatilho (ex.: {@code PaymentApproved}, {@code Timeout})
 * @param messageId mensagem que causou o passo; {@code null} quando o gatilho foi interno
 */
public record SagaStepRecord(UUID sagaId, String step, UUID messageId, StepOutcome outcome,
                             SagaState stateAfter, String detail, OffsetDateTime occurredAt) {
}
