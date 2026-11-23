package br.com.oficina.infrastructure.messaging;

import br.com.oficina.application.messaging.Destination;
import br.com.oficina.infrastructure.configuration.MessagingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * SQS FIFO (ADR 0020). {@code MessageGroupId} = OS (ordem por OS, paralelismo entre OS) e
 * {@code MessageDeduplicationId} = {@code messageId} (republicação do relay dentro de 5 min não duplica).
 */
@ApplicationScoped
public class SqsMessageTransport implements MessageTransport {

    private final SqsClient sqs;
    private final MessagingConfig config;

    public SqsMessageTransport(SqsClient sqs, MessagingConfig config) {
        this.sqs = sqs;
        this.config = config;
    }

    @Override
    public void send(Destination destination, String groupId, UUID deduplicationId, String body) {
        sqs.sendMessage(request -> request
            .queueUrl(queueUrl(destination))
            .messageGroupId(groupId)
            .messageDeduplicationId(deduplicationId.toString())
            .messageBody(body));
    }

    @Override
    public List<ReceivedMessage> receive() {
        String queue = required(config.queues().osReplies(), "app.messaging.queues.os-replies");
        return sqs.receiveMessage(request -> request
                .queueUrl(queue)
                .maxNumberOfMessages(config.maxMessages())
                .waitTimeSeconds(config.waitSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
            .messages().stream()
            .map(message -> new ReceivedMessage(message.body(), message.receiptHandle(),
                Integer.parseInt(message.attributes().getOrDefault(
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))))
            .toList();
    }

    @Override
    public void acknowledge(ReceivedMessage message) {
        String queue = required(config.queues().osReplies(), "app.messaging.queues.os-replies");
        sqs.deleteMessage(request -> request.queueUrl(queue).receiptHandle(message.receipt()));
    }

    private String queueUrl(Destination destination) {
        return switch (destination) {
            case BILLING_COMMANDS -> required(config.queues().billingCommands(), "app.messaging.queues.billing-commands");
            case EXECUTION_COMMANDS -> required(config.queues().executionCommands(), "app.messaging.queues.execution-commands");
        };
    }

    private static String required(Optional<String> url, String property) {
        return url.filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalStateException("Fila não configurada: " + property));
    }
}
