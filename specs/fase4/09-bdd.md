# Spec 09 — BDD do fluxo completo da OS

> Atende **R9** (pelo menos um fluxo completo testado com BDD). Cenários derivados de [04](04-saga-orquestrada.md) §6. Estratégia geral em [08](08-testes-e-cobertura.md).

## 1. Ferramenta e formato

**Cucumber-JVM** (`io.cucumber:cucumber-java` + `cucumber-junit-platform-engine`), cenários em **Gherkin em português**
(`# language: pt`), `.feature` versionados em `src/test/resources/features/`. Reporte HTML/JSON como artefato do CI.

## 2. Onde roda (decisão)

Um fluxo "completo" envolve os três serviços. Para ser executável em **todo PR** sem cluster, o BDD principal vive no
`oficina-app` (dono do orquestrador) e roda com **participantes em memória**:

- Broker substituído por uma implementação **em memória** das portas de mensageria (mesmo contrato, `MessageConsumerPort`/`OutboxPort`).
- **Billing e Execution reais** não sobem; entram *stubs de participante* que respondem segundo o contrato do catálogo
  [03](03-mensageria-e-contratos.md) (e podem ser programados para falhar).
- PostgreSQL real (Testcontainers) para o OS — a persistência da Saga é parte do que se testa.

Camadas adicionais (não substituem a anterior):

| Nível | Onde | Quando |
|---|---|---|
| **BDD de fluxo** (participantes em memória) | `oficina-app/src/test/.../bdd` | todo PR |
| **BDD de serviço** (Billing: orçamento→pagamento com MP fake; Execution: fila→conclusão) | cada repo, `features/` | todo PR |
| **BDD ponta a ponta real** (3 serviços + SQS/LocalStack no `docker compose`) | `oficina-app/e2e/` (profile Maven `e2e`) | manual/noturno e antes da sessão AWS |
| Smoke na AWS (mesmos cenários S1/S6 via gateway) | script de smoke + Postman | sessão AWS |

## 3. Features obrigatórias

```gherkin
# language: pt
Funcionalidade: Ciclo de vida da ordem de serviço com orçamento, pagamento e execução

  Cenário: Fluxo completo com pagamento aprovado e execução concluída (S1)
    Dado uma OS em diagnóstico com 1 serviço e 2 peças, total de R$ 450,00
    Quando o mecânico envia a OS para aprovação
    Então a OS fica "AWAITING_APPROVAL" e um orçamento de R$ 450,00 é gerado
    Quando o cliente aprova o orçamento
    Então a OS fica "AWAITING_PAYMENT" e um Pix de R$ 450,00 é solicitado
    Quando o pagamento é aprovado
    Então a OS fica "AWAITING_EXECUTION"
    Quando o mecânico inicia e conclui a execução
    Então a OS fica "FINISHED", a Saga fica "COMPLETED" e o histórico registra todas as transições

  Cenário: Falha na execução após o pagamento aciona estorno (S6)
    Dado uma OS paga e aguardando execução
    Quando a execução falha por "peça indisponível"
    Então o pagamento é estornado, o orçamento é anulado, o estoque é restaurado e a OS fica "CANCELLED"

  Cenário: Cliente recusa o orçamento (S2)
    ...
  Esquema do Cenário: Pagamento recusado esgota as tentativas (S5)
    ...
  Cenário: Estorno falha e exige intervenção (S7)
    ...
```

Mínimo obrigatório para o PDF: **S1, S2, S5, S6, S7** (feliz + as quatro compensações mais distintas). S3, S4, S8–S11 como
testes de componente/unidade ou cenários extras conforme o tempo.

## 4. Regras

- Passos escritos na linguagem do negócio; **nenhum** passo menciona SQS, tabela ou classe.
- *Steps* reutilizáveis, estado em um `ScenarioContext` isolado por cenário (banco limpo por cenário, `Clock` fixo).
- Falhas injetadas pelos *stubs* (`Quando o Billing falha ao gerar o orçamento`), não por mocks espalhados.
- O mesmo vocabulário alimenta o roteiro do vídeo (responsabilidade do usuário): S1 e S6 são as demonstrações.

## 4.1 Implementação (2026-11-23)

- **Cucumber-JVM 7.34.7** (`cucumber-java`), executado **por dentro** de um `@QuarkusTest` (`SagaBddTest` chama
  `io.cucumber.core.cli.Main`), em vez do engine JUnit Platform do Cucumber: os passos usam a aplicação real (casos de uso,
  PostgreSQL do teste, perfil `SagaEnabledProfile`) sem depender da extensão Quarkiverse de Cucumber.
- Feature única `src/test/resources/features/saga/ciclo-da-ordem-de-servico.feature` com **S1, S2, S5, S6 e S7** (5 cenários, 74 passos).
- Passos por papel: `OficinaSteps` (ações e o que a oficina vê) e `ParticipantesSteps` (Billing e Execution simulados: conferem o
  comando enviado e respondem pelo contrato). Estado por cenário em `SagaWorld` (criado/limpo por hooks, já que o `ObjectFactory`
  padrão não injeta dependências). Valores em reais com o tipo `{dinheiro}` (`R$ 329,80`).
- O mesmo `SagaScenario` (fixture em `testsupport/`) é usado pelo `SagaFlowIntegrationTest`, que mantém as verificações técnicas
  (duplicidade, timeout, tipo desconhecido).
- Relatórios em `target/cucumber-reports/saga.{html,json}`. **Verificado** que um passo indefinido e uma expectativa errada
  reprovam o `mvn verify` (`1 undefined, 1 failed`).
- **Pendente (fora do OS):** BDD de serviço no Billing e no Execution; BDD ponta a ponta com os 3 serviços e LocalStack no
  `docker compose`; os mesmos cenários via gateway na sessão AWS.

## 5. Critérios de aceite

- [ ] `mvn verify` executa os `.feature` e **falha** se um cenário falhar ou ficar `undefined`/`pending`.
- [ ] S1, S2, S5, S6 e S7 verdes no CI; relatório Cucumber publicado.
- [ ] Os cenários contam para a cobertura do serviço ([08](08-testes-e-cobertura.md)).
- [ ] README descreve como executar o BDD localmente e aponta para o relatório (evidência do PDF).

## 6. Fora de escopo

BDD de interface; testes de aceitação contratados por terceiros; geração automática de `.feature` a partir do Postman.
