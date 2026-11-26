# Spec 08 — Testes unitários e cobertura ≥ 80% por serviço

> Atende **R8** (testes unitários em todos os serviços) e **R10** (cobertura mínima de 80% **por serviço**). BDD em [09](09-bdd.md); Sonar em [10](10-qualidade-sonar.md).

## 1. Pirâmide por serviço

| Nível | O que cobre | Ferramenta | Roda em |
|---|---|---|---|
| Unidade (domínio, casos de uso, orquestrador) | invariantes, transições, ramos de compensação | JUnit 5 + AssertJ + Mockito; sem Quarkus | todo PR (`mvn verify`) |
| Componente (adapter + infra real) | JPA/Flyway, DynamoDB, SQS, REST | `@QuarkusTest` + Testcontainers (PostgreSQL 16; LocalStack/Floci p/ SQS e DynamoDB) | todo PR |
| Contrato | schemas das mensagens; OpenAPI; adapter da Stripe (WireMock) | JSON Schema validator; WireMock | todo PR |
| Arquitetura | domínio sem framework; sem import entre serviços | ArchUnit | todo PR |
| BDD de fluxo | Saga completa e compensações | Cucumber ([09](09-bdd.md)) | todo PR (rápido, em memória) + sessão AWS (real) |
| Segurança/RBAC | rota × papel (matriz) | `@TestSecurity`/JWT gerado | todo PR |

Proporção-alvo: unidade ≫ componente > contrato > BDD. Testes **determinísticos**: relógio injetado (`Clock`), sem `sleep`,
sem rede externa (Stripe sempre atrás de fake/WireMock), IDs controlados.

## 2. Gate de cobertura (R10)

Hoje o gate da Fase 3 mede 80% **por pacote crítico** (`pom.xml`, regra `PACKAGE`). Para R10 ("por serviço"):

- Adicionar a regra `BUNDLE`, `LINE` ≥ **0,80** e `BRANCH` ≥ **0,70** sobre o módulo inteiro, nos **três** serviços.
- Manter a regra por pacote nos pacotes críticos (domínio, aplicação, orquestrador, consumidores).
- Exclusões **justificadas e listadas no `pom.xml`**: classes de `main`/configuração, DTOs/records sem lógica, entidades JPA
  geradas, *getters*; **proibido** excluir adapters com ramificação para "fechar o número".
- Relatório publicado como artefato do CI e **resumo da cobertura no README** (badge + tabela), atendendo à evidência pedida
  no PDF. Cobertura calculada sobre `jacoco-merged.exec` (unidade + componente), como já ocorre.

**Meta interna:** ≥ 85% de linhas no domínio e na aplicação de cada serviço (folga sobre o mínimo).

## 3. Testes que a Fase 4 exige especificamente

| Alvo | Casos mínimos |
|---|---|
| Orquestrador (OS) | uma asserção por linha do §4 de [04](04-saga-orquestrada.md): sucesso, falha, timeout, duplicado, fora de ordem, `ORPHAN_PAYMENT` |
| `Budget`/`Payment` (Billing) | transições, idempotência de decisão, token (válido, usado, adulterado), 1 pagamento ativo, valor ≠ total rejeitado |
| Webhook | assinatura inválida, `id` do PaymentIntent divergente, `event.id` duplicado, desconhecido, tolerância de tempo excedida, Stripe fora do ar |
| `ExecutionTask` | transições, concorrência, idempotência, cancelamento em cada estado |
| Outbox/Inbox | falha entre `commit` e publicação; falha entre processar e `DeleteMessage`; mensagem venenosa → DLQ |
| Migração V7 | aplicada sobre dados da V6 populados; rollback de versão da app |
| Segurança | matriz RBAC dos 3 serviços; `CLIENT` isolado; `X-Gateway-Key`; webhook sem JWT mas com assinatura |

## 4. Evidências (entregável)

- Relatório JaCoCo (HTML) como artefato do workflow e `docs/cobertura.md` em cada repo, com data, commit e números.
- Execução do CI verde em cada repositório (link no README).

## 5. Critérios de aceite

- [ ] `mvn verify` falha se `BUNDLE LINE < 0,80` em qualquer um dos três serviços (verificado derrubando propositalmente um teste).
- [ ] Nenhum teste depende de ordem, relógio real, rede externa ou credencial.
- [ ] Tempo do `mvn verify` por serviço ≤ 8 min no runner (senão separar componente em job paralelo).
- [ ] Tabela de cobertura por serviço no README e no PDF.

## 6. Fora de escopo

Teste de carga (a demonstração do HPA da Fase 3 permanece manual); testes de mutação como gate (foram usados pontualmente na Fase 3 e podem ser repetidos no orquestrador, sem bloquear o CI).
