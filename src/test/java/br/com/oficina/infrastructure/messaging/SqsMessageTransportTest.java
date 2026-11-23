package br.com.oficina.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.oficina.application.messaging.Destination;
import br.com.oficina.infrastructure.configuration.MessagingConfig;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * O transporte contra um SQS de verdade (LocalStack): filas FIFO, grupo por OS, deduplicação pelo
 * {@code messageId} e confirmação que apaga a mensagem. É o teste que prova as premissas do ADR 0020
 * sem depender da conta AWS.
 */
class SqsMessageTransportTest {

    private static final LocalStackContainer LOCALSTACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1")).withServices(Service.SQS);

    private static SqsClient sqs;
    private static String billingQueue;
    private static String repliesQueue;
    private static SqsMessageTransport transport;

    @BeforeAll
    static void startSqs() {
        LOCALSTACK.start();
        sqs = SqsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpointOverride(Service.SQS))
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .httpClient(UrlConnectionHttpClient.create())
            .build();
        billingQueue = fifo("oficina-test-billing-commands.fifo");
        repliesQueue = fifo("oficina-test-os-saga-replies.fifo");
        transport = new SqsMessageTransport(sqs, config(billingQueue, repliesQueue));
    }

    @AfterAll
    static void stopSqs() {
        if (sqs != null) {
            sqs.close();
        }
        LOCALSTACK.stop();
    }

    @Test
    void send_shouldPublishToTheParticipantQueueWithGroupAndDeduplication() {
        UUID messageId = UUID.randomUUID();

        transport.send(Destination.BILLING_COMMANDS, "wo-7", messageId, "{\"type\":\"GenerateBudget\"}");
        // Republicação do relay (ex.: falha depois do envio e antes do commit): mesmo id, nenhuma mensagem nova.
        transport.send(Destination.BILLING_COMMANDS, "wo-7", messageId, "{\"type\":\"GenerateBudget\"}");

        List<Message> received = sqs.receiveMessage(r -> r.queueUrl(billingQueue).maxNumberOfMessages(10)
            .waitTimeSeconds(2).messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID,
                MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)).messages();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).attributes())
            .containsEntry(MessageSystemAttributeName.MESSAGE_GROUP_ID, "wo-7")
            .containsEntry(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID, messageId.toString());
    }

    @Test
    void receiveAndAcknowledge_shouldRemoveTheReplyFromTheQueue() {
        sqs.sendMessage(r -> r.queueUrl(repliesQueue).messageGroupId("wo-8")
            .messageDeduplicationId(UUID.randomUUID().toString()).messageBody("{\"type\":\"BudgetGenerated\"}"));

        List<MessageTransport.ReceivedMessage> replies = transport.receive();
        assertThat(replies).singleElement().satisfies(reply -> {
            assertThat(reply.body()).contains("BudgetGenerated");
            assertThat(reply.receiveCount()).isEqualTo(1);
        });
        transport.acknowledge(replies.get(0));

        assertThat(sqs.getQueueAttributes(r -> r.queueUrl(repliesQueue)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
            .attributes().values()).containsOnly("0");
    }

    @Test
    void missingQueueConfiguration_shouldFailWithTheProperty() {
        SqsMessageTransport unconfigured = new SqsMessageTransport(sqs, config(null, null));

        assertThatThrownBy(() -> unconfigured.send(Destination.EXECUTION_COMMANDS, "wo-1", UUID.randomUUID(), "{}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.messaging.queues.execution-commands");
    }

    private static String fifo(String name) {
        return sqs.createQueue(r -> r.queueName(name).attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
            .queueUrl();
    }

    private static MessagingConfig config(String billing, String replies) {
        return new MessagingConfig() {
            @Override public boolean enabled() { return true; }
            @Override public String region() { return LOCALSTACK.getRegion(); }
            @Override public Optional<URI> endpointOverride() { return Optional.of(LOCALSTACK.getEndpointOverride(Service.SQS)); }
            @Override public int maxMessages() { return 10; }
            @Override public int waitSeconds() { return 2; }
            @Override public int relayBatchSize() { return 50; }
            @Override public Queues queues() {
                return new Queues() {
                    @Override public Optional<String> billingCommands() { return Optional.ofNullable(billing); }
                    @Override public Optional<String> executionCommands() { return Optional.empty(); }
                    @Override public Optional<String> osReplies() { return Optional.ofNullable(replies); }
                };
            }
        };
    }
}
