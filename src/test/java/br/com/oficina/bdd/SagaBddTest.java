package br.com.oficina.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.testsupport.SagaEnabledProfile;
import io.cucumber.core.cli.Main;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Executa os cenários BDD da Saga (spec 09, R9) com a aplicação real no ar: o Cucumber roda por dentro
 * deste {@code @QuarkusTest}, então os passos usam os casos de uso, o PostgreSQL do teste e o mesmo
 * perfil dos testes de integração. Cenário com passo indefinido, pendente ou falho reprova o
 * {@code mvn verify}. Relatórios em {@code target/cucumber-reports/}.
 */
@QuarkusTest
@TestProfile(SagaEnabledProfile.class)
class SagaBddTest {

    @Test
    void cicloDaOrdemDeServico() {
        byte exitStatus = Main.run(new String[] {
            "--glue", "br.com.oficina.bdd",
            "--plugin", "pretty",
            "--plugin", "html:target/cucumber-reports/saga.html",
            "--plugin", "json:target/cucumber-reports/saga.json",
            "--monochrome",
            "classpath:features"
        }, Thread.currentThread().getContextClassLoader());

        assertThat(exitStatus).as("cenários BDD da Saga (ver target/cucumber-reports/saga.html)").isZero();
    }
}
