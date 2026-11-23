package br.com.oficina.domain.saga;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Política vigente e o "agora" de um passo: o relógio é injetado, o domínio nunca lê o relógio do sistema. */
public record SagaContext(SagaPolicy policy, OffsetDateTime now) {

    public SagaContext {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(now);
    }

    OffsetDateTime deadline(Duration timeout) {
        return timeout == null ? null : now.plus(timeout);
    }
}
