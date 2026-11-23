package br.com.oficina.infrastructure.messaging;

import br.com.oficina.infrastructure.configuration.MessagingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/**
 * Cliente SQS. Credenciais pela cadeia padrão do SDK: no k3s, o IMDS da EC2 (LabRole, ADR 0022); em
 * dev/teste, as variáveis {@code AWS_*} apontando para o LocalStack. Nenhuma chave é configurada aqui.
 *
 * <p>Bean {@code @ApplicationScoped}: o cliente só é construído no primeiro uso — com a mensageria
 * desligada nada chega a tocar na AWS.</p>
 */
@ApplicationScoped
public class SqsClientProducer {

    @Produces
    @ApplicationScoped
    SqsClient sqsClient(MessagingConfig config) {
        SqsClientBuilder builder = SqsClient.builder()
            .region(Region.of(config.region()))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .httpClient(UrlConnectionHttpClient.create());
        config.endpointOverride().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    void close(@Disposes SqsClient client) {
        client.close();
    }
}
