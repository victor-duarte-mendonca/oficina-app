package br.com.oficina.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.oficina.application.dto.StatusHistoryEntryDto;
import br.com.oficina.application.dto.WorkOrderStatusLabel;
import br.com.oficina.testsupport.SagaScenario;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.E;
import io.cucumber.java.pt.Entao;
import io.cucumber.java.pt.Quando;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** O que a oficina faz e vê: abrir e conduzir a OS, cancelar, destravar a Saga e conferir o resultado. */
public class OficinaSteps {

    private SagaScenario scenario() {
        return SagaWorld.current().scenario();
    }

    // ===== Preparação =====

    @Dado("uma OS em diagnóstico com um serviço de R$ {dinheiro} e {int} peças de R$ {dinheiro} cada, com {int} peças em estoque")
    public void osEmDiagnostico(BigDecimal servico, int pecas, BigDecimal precoPeca, int estoque) {
        scenario().openWorkOrderInDiagnosis(servico, pecas, precoPeca, estoque);
    }

    @Dado("uma OS com o orçamento aprovado e o Pix emitido")
    public void osComPixEmitido() {
        scenario().sendForApproval();
        scenario().reply("BudgetGenerated", Map.of("budgetId", SagaWorld.BUDGET_ID, "total", "329.80",
            "expiresAt", "2026-11-26T14:00:00Z"));
        scenario().reply("BudgetApproved", Map.of("budgetId", SagaWorld.BUDGET_ID, "by", "CLIENT_TOKEN"));
        scenario().reply("PaymentRequested", Map.of("paymentId", SagaWorld.current().issueNextPix(),
            "expiresAt", "2026-11-23T15:00:00Z"));
    }

    @Dado("uma OS paga e em execução")
    public void osPagaEmExecucao() {
        osComPixEmitido();
        scenario().reply("PaymentApproved", Map.of("paymentId", SagaWorld.current().currentPix(), "amount", "329.80"));
        scenario().reply("ExecutionEnqueued", Map.of("position", 1));
        scenario().reply("ExecutionStarted", Map.of("assignee", "mecanico"));
    }

    // ===== Ações da oficina =====

    @Quando("o mecânico envia a OS para aprovação")
    public void enviaParaAprovacao() {
        scenario().sendForApproval();
    }

    @Quando("a oficina cancela a OS")
    public void cancelaAOs() {
        scenario().cancel();
    }

    @Quando("a oficina pede uma nova tentativa de compensação")
    public void novaTentativaDeCompensacao() {
        scenario().retryCompensation();
    }

    // ===== O que a oficina vê =====

    @Entao("a OS fica {string}")
    public void aOsFica(String status) {
        assertThat(WorkOrderStatusLabel.of(scenario().status())).isEqualTo(status);
    }

    @E("a OS continua {string} até o dinheiro ser devolvido")
    public void aOsContinua(String status) {
        aOsFica(status);
    }

    @E("o estoque volta a ter {int} peças")
    public void estoqueVolta(int pecas) {
        assertThat(scenario().stock()).isEqualTo(pecas);
    }

    @E("a Saga termina concluída")
    public void sagaConcluida() {
        assertThat(scenario().saga().state()).isEqualTo("COMPLETED");
        assertThat(scenario().saga().pendingCompensations()).isEmpty();
    }

    @E("a Saga termina cancelada")
    public void sagaCancelada() {
        assertThat(scenario().saga().state()).isEqualTo("CANCELLED");
        assertThat(scenario().saga().pendingCompensations()).isEmpty();
    }

    @Entao("a Saga aguarda intervenção da oficina")
    public void sagaAguardaIntervencao() {
        assertThat(scenario().saga().state()).isEqualTo("COMPENSATION_PENDING");
        assertThat(scenario().saga().pendingCompensations()).startsWith("REFUND_PAYMENT");
    }

    @E("o histórico registra as etapas {string}")
    public void historicoRegistraAsEtapas(String etapas) {
        List<String> esperadas = Arrays.stream(etapas.split(",")).map(String::trim).toList();
        assertThat(scenario().history()).extracting(StatusHistoryEntryDto::toStatusLabel)
            .containsExactlyElementsOf(esperadas);
    }

    @E("o histórico registra o motivo {string}")
    public void historicoRegistraOMotivo(String motivo) {
        assertThat(scenario().history()).last()
            .satisfies(entry -> assertThat(entry.reason()).contains(motivo));
    }

    @E("nenhuma cobrança foi solicitada ao cliente")
    public void nenhumaCobranca() {
        assertThat(scenario().sentCommandTypes()).doesNotContain("RequestPayment");
    }
}
