package br.com.oficina.infrastructure.configuration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.net.URI;
import java.util.Optional;

/**
 * Configuração do transporte de mensagens ({@code app.messaging.*}). As URLs das filas vêm do contrato
 * SSM publicado pelo {@code oficina-infra-k8s} (spec 12 §1). Com {@code enabled=false} o outbox
 * acumula e nenhum job toca no SQS — é o modo de dev/test sem AWS.
 */
@ConfigMapping(prefix = "app.messaging")
public interface MessagingConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("us-east-1")
    String region();

    /** LocalStack/ElasticMQ em dev e teste; vazio = endpoint público da AWS. */
    Optional<URI> endpointOverride();

    Queues queues();

    /** Mensagens por recebimento (máximo do SQS: 10). */
    @WithDefault("10")
    int maxMessages();

    /** Long polling: reduz chamadas vazias (e custo) sem atrasar a entrega. */
    @WithDefault("10")
    int waitSeconds();

    /** Mensagens do outbox publicadas por transação do relay. */
    @WithDefault("50")
    int relayBatchSize();

    interface Queues {
        Optional<String> billingCommands();

        Optional<String> executionCommands();

        Optional<String> osReplies();
    }
}
