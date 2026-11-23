package br.com.oficina.bdd;

import br.com.oficina.testsupport.SagaScenario;
import br.com.oficina.testsupport.SagaScenario.SentCommand;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import java.util.List;

/**
 * Estado de um cenário, compartilhado pelos passos da oficina e dos participantes. O {@code ObjectFactory}
 * padrão do Cucumber não injeta dependências; o mundo é criado no {@code @Before} e descartado no
 * {@code @After}, então nenhum cenário enxerga dados de outro.
 */
public final class SagaWorld {

    static final String BUDGET_ID = "orcamento-1";

    private static SagaWorld current;

    private final SagaScenario scenario = new SagaScenario();
    private int pixIssued;

    @Before
    public void newScenario() {
        current = new SagaWorld();
    }

    @After
    public void endScenario() {
        current = null;
    }

    static SagaWorld current() {
        if (current == null) {
            throw new IllegalStateException("Cenário não iniciado");
        }
        return current;
    }

    SagaScenario scenario() {
        return scenario;
    }

    /** Cada Pix emitido tem um id novo, como no Mercado Pago. */
    String issueNextPix() {
        pixIssued++;
        return currentPix();
    }

    String currentPix() {
        return "pix-" + pixIssued;
    }

    /** Último comando de um tipo que o OS enviou para esta OS (falha se nenhum foi enviado). */
    SentCommand lastCommandOfType(String type) {
        List<SentCommand> matching = scenario.sentCommands().stream()
            .filter(command -> command.type().equals(type))
            .toList();
        if (matching.isEmpty()) {
            throw new AssertionError("Nenhum " + type + " foi enviado; enviados: " + scenario.sentCommandTypes());
        }
        return matching.get(matching.size() - 1);
    }
}
