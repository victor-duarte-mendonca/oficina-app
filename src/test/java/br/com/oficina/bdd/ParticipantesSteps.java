package br.com.oficina.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.testsupport.SagaScenario;
import br.com.oficina.testsupport.SagaScenario.SentCommand;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import java.math.BigDecimal;
import java.util.Map;

/**
 * O papel do Billing e do Execution no cenário: conferem o comando que o OS enviou ("o Billing recebe
 * ...") e devolvem a resposta do contrato ("o Billing gera ..."). Falhas são respostas de falha do
 * contrato — nenhum mock espalhado, nenhum detalhe de fila no texto do cenário.
 */
public class ParticipantesSteps {

    private SagaWorld world() {
        return SagaWorld.current();
    }

    private SagaScenario scenario() {
        return world().scenario();
    }

    // ===== Billing: orçamento =====

    @E("o Billing recebe o pedido de orçamento de R$ {dinheiro}")
    public void billingRecebePedidoDeOrcamento(BigDecimal total) {
        SentCommand pedido = world().lastCommandOfType("GenerateBudget");
        assertThat(Dinheiro.doContrato(pedido.text("total"))).isEqualByComparingTo(total);
        assertThat(pedido.payload().get("items")).isNotEmpty();
    }

    @Quando("o Billing gera o orçamento")
    public void billingGeraOrcamento() {
        world().lastCommandOfType("GenerateBudget");
        scenario().reply("BudgetGenerated", Map.of("budgetId", SagaWorld.BUDGET_ID, "total", "329.80",
            "expiresAt", "2026-11-26T14:00:00Z"));
    }

    @E("o cliente aprova o orçamento")
    public void clienteAprova() {
        scenario().reply("BudgetApproved", Map.of("budgetId", SagaWorld.BUDGET_ID, "by", "CLIENT_TOKEN"));
    }

    @E("o cliente recusa o orçamento")
    public void clienteRecusa() {
        scenario().reply("BudgetRejected", Map.of("budgetId", SagaWorld.BUDGET_ID, "by", "CLIENT_JWT"));
    }

    @Entao("o Billing recebe o pedido para anular o orçamento")
    public void billingRecebeAnulacao() {
        assertThat(scenario().lastCommand().type()).isEqualTo("VoidBudget");
        assertThat(scenario().lastCommand().text("budgetId")).isEqualTo(SagaWorld.BUDGET_ID);
    }

    @Quando("o Billing confirma a anulação do orçamento")
    public void billingConfirmaAnulacao() {
        scenario().reply("BudgetVoided", Map.of("budgetId", SagaWorld.BUDGET_ID));
    }

    // ===== Billing: pagamento =====

    @Entao("o Billing recebe a cobrança de R$ {dinheiro}")
    public void billingRecebeCobranca(BigDecimal valor) {
        SentCommand cobranca = world().lastCommandOfType("RequestPayment");
        assertThat(Dinheiro.doContrato(cobranca.text("amount"))).isEqualByComparingTo(valor);
    }

    @Entao("o Billing recebe uma nova cobrança, tentativa {int}")
    public void billingRecebeNovaCobranca(int tentativa) {
        assertThat(scenario().lastCommand().type()).isEqualTo("RequestPayment");
        assertThat(scenario().lastCommand().payload().get("attempt").asInt()).isEqualTo(tentativa);
    }

    @Quando("o Billing emite o Pix")
    public void billingEmitePix() {
        scenario().reply("PaymentRequested", Map.of("paymentId", world().issueNextPix(),
            "expiresAt", "2026-11-23T15:00:00Z"));
    }

    @Quando("o pagamento é aprovado")
    public void pagamentoAprovado() {
        scenario().reply("PaymentApproved", Map.of("paymentId", world().currentPix(), "amount", "329.80"));
    }

    @Quando("o pagamento é recusado pelo banco")
    public void pagamentoRecusado() {
        scenario().reply("PaymentRejected", Map.of("paymentId", world().currentPix(), "detail", "rejected_by_bank"));
    }

    @Entao("o Billing recebe o pedido de estorno")
    public void billingRecebeEstorno() {
        assertThat(scenario().lastCommand().type()).isEqualTo("RefundPayment");
        assertThat(scenario().lastCommand().text("paymentId")).isEqualTo(world().currentPix());
    }

    @Quando("o Billing confirma o estorno")
    public void billingConfirmaEstorno() {
        scenario().reply("PaymentRefunded", Map.of("paymentId", world().currentPix()));
    }

    @E("o Billing recusa o estorno {int} vezes por {string}")
    public void billingRecusaEstorno(int vezes, String motivo) {
        for (int i = 0; i < vezes; i++) {
            scenario().reply("PaymentRefundFailed", Map.of("paymentId", world().currentPix(), "detail", motivo));
        }
    }

    // ===== Execution =====

    @Entao("o Execution recebe a OS para a fila de execução")
    public void executionRecebeOs() {
        SentCommand pedido = world().lastCommandOfType("EnqueueExecution");
        assertThat(pedido.payload().get("vehicle").get("plate").asText()).isNotBlank();
    }

    @Quando("o Execution coloca a OS na fila")
    public void executionEnfileira() {
        scenario().reply("ExecutionEnqueued", Map.of("position", 1));
    }

    @Quando("o mecânico inicia a execução")
    public void mecanicoInicia() {
        scenario().reply("ExecutionStarted", Map.of("assignee", "mecanico"));
    }

    @Quando("o mecânico conclui a execução")
    public void mecanicoConclui() {
        scenario().reply("ExecutionFinished", Map.of("summary", "pastilhas trocadas"));
    }

    @Quando("a execução falha por {string}")
    public void execucaoFalha(String motivo) {
        scenario().reply("ExecutionFailed", Map.of("reason", motivo));
    }
}
