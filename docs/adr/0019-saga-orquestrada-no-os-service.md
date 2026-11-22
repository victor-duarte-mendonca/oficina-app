# ADR 0019 — Saga orquestrada, com orquestrador no OS Service

- **Status:** Proposto
- **Origem:** [`specs/fase4/00-visao-geral.md`](../../specs/fase4/00-visao-geral.md) §3 (R6, R7)

## Contexto

O fluxo OS → orçamento → aprovação → pagamento → execução cruza três serviços e precisa de compensação em qualquer etapa. O PDF
aceita Saga orquestrada ou coreografada e exige justificar a escolha no README.

## Decisão

**Saga orquestrada.** O orquestrador vive no OS Service, que já é dono do agregado `WorkOrder` e dos seus status. Ele persiste
`saga_instance` (estado e log de passos), envia comandos às filas dos participantes e reage às respostas. Garantias:

- **Outbox:** a mudança de estado e a mensagem saem na mesma transação local.
- **Consumidores idempotentes:** tabela/chave de deduplicação por `messageId`.
- **Timeouts** por passo, retentativas limitadas e DLQ.
- **Compensações** explícitas por passo (tabela em `specs/fase4/00-visao-geral.md` §3); estorno com falha leva a
  `COMPENSATION_PENDING` com alerta.

## Consequências

- (+) O fluxo e as compensações ficam num só lugar: legível, testável com BDD e demonstrável no vídeo.
- (+) Rastreamento mais simples (um `sagaId` conduzido por um único ator).
- (−) O OS Service concentra lógica de coordenação e passa a conhecer os comandos dos participantes (acoplamento de contrato).
- (−) Ponto central: se o orquestrador cair, as sagas param até o retorno (estado persistido, retomada por timeout/reprocesso).

## Alternativas consideradas

- **Coreografia:** menos acoplamento, mas com 5+ passos e compensações em cadeia o fluxo fica espalhado, difícil de depurar e de
  mostrar. Seria preferível com muitos participantes independentes e fluxos curtos.
- **Orquestrador como quarto serviço:** isolamento maior, porém mais um repositório, banco e pipeline sem ganho para este escopo.
