package br.com.oficina.testsupport;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Aplicação com a Saga ligada e a mensageria desligada: os comandos ficam no outbox e as respostas são
 * injetadas pela porta de entrada do consumidor. Um único perfil compartilhado evita reinicializar o
 * Quarkus para cada classe de teste da Fase 4.
 */
public class SagaEnabledProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("app.saga.enabled", "true");
    }
}
