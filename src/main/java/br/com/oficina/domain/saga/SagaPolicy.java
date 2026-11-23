package br.com.oficina.domain.saga;

import java.time.Duration;
import java.util.Objects;

/**
 * Limites de tentativa e prazos da Saga (spec 04 §5). Valor do domínio, preenchido pela configuração:
 * a demonstração encurta os prazos (ex.: aprovação em 2 min) sem mudar código.
 *
 * @param maxStepAttempts         envios do mesmo comando antes de desistir do passo (resposta técnica ausente)
 * @param maxPaymentAttempts      cobranças Pix por orçamento (1 original + novas tentativas)
 * @param stepReplyTimeout        espera pela resposta técnica de um comando
 * @param approvalTimeout         prazo do cliente para decidir o orçamento
 * @param paymentTimeout          prazo para o Pix ser pago
 */
public record SagaPolicy(int maxStepAttempts,
                         int maxPaymentAttempts,
                         Duration stepReplyTimeout,
                         Duration approvalTimeout,
                         Duration paymentTimeout) {

    public SagaPolicy {
        if (maxStepAttempts < 1 || maxPaymentAttempts < 1) {
            throw new IllegalArgumentException("Limites de tentativa devem ser >= 1");
        }
        Objects.requireNonNull(stepReplyTimeout);
        Objects.requireNonNull(approvalTimeout);
        Objects.requireNonNull(paymentTimeout);
    }

    /** Padrões da spec 04: 3 envios por passo, 3 cobranças, 30 s de resposta, 72 h de aprovação, 30 min de Pix. */
    public static SagaPolicy defaults() {
        return new SagaPolicy(3, 3, Duration.ofSeconds(30), Duration.ofHours(72), Duration.ofMinutes(30));
    }
}
