# Spec 05 — Billing Service e integração com o Mercado Pago

> Atende **R1** (serviço de Orçamento e Pagamento) e **R3** (integração com o Mercado Pago). ADR: [0021](../../docs/adr/0021-pagamento-mercado-pago-atras-de-porta.md).
> Repositório novo: `oficina-billing-service`. Banco: [02](02-persistencia-sql-nosql.md) §3. Mensagens: [03](03-mensageria-e-contratos.md). Saga: [04](04-saga-orquestrada.md).
> **Responsabilidade do usuário:** credenciais do Mercado Pago (aplicação, access token e segredo de webhook de **teste**), usuários de teste.

## 1. Responsabilidades (PDF)

1. **Gerar e enviar orçamentos para aprovação** — ao receber `GenerateBudget`.
2. **Registrar e verificar pagamentos** — Pix via Mercado Pago; confirmação por webhook **e** reconciliação por consulta.
3. **Atualizar o status da OS após o pagamento** — publicando `PaymentApproved` (o OS aplica a transição, [04](04-saga-orquestrada.md)).
4. Estornar (compensação) quando a Saga pedir.

## 2. Domínio (agregados e invariantes)

- **`Budget`**: `DRAFT → SENT → APPROVED | REJECTED | EXPIRED | VOIDED`. Total recalculado das linhas; imutável após `SENT`;
  decisão só em `SENT` e dentro do prazo; aprovação/recusa é **idempotente** (repetir a mesma decisão devolve o mesmo resultado;
  decisão contrária depois da primeira é erro de negócio).
- **Token de aprovação** (uso único, [`ApprovalToken`](../../src/main/java/br/com/oficina/domain/valueobject) reaproveitado do OS): gerado no
  `SENT`, enviado por e-mail em claro **uma vez**, persistido como hash, comparado em tempo constante, queimado na decisão.
- **`Payment`**: `PENDING → APPROVED | REJECTED | EXPIRED`; `APPROVED → REFUND_PENDING → REFUNDED | REFUND_FAILED`. Um pagamento
  ativo por orçamento (índice parcial). Valor do pagamento **= total do orçamento** (não aceita outro).
- `PaymentGatewayPort` (saída): `createPixPayment(...)`, `getPayment(id)`, `refund(id, amount, idempotencyKey)`.

## 3. API REST do Billing (via gateway; OpenAPI exportado)

| Método | Rota | Papel | Função |
|---|---|---|---|
| `GET` | `/admin/budgets/{orderNumber}` | ADMIN, MECHANIC | consulta orçamento e pagamento |
| `POST` | `/admin/budgets/{orderNumber}/approve` \| `/reject` | ADMIN | decisão do funcionário (cliente aprovou por telefone); `by=STAFF` |
| `POST` | `/public/budgets/{orderNumber}/approve` \| `/reject` | público + token | decisão pelo link do e-mail; corpo `{cpfCnpj?, token}`; 404 genérico se token inválido (não revela a existência) |
| `GET` | `/me/budgets/{orderNumber}` | CLIENT | orçamento próprio (404 se de outro cliente, como em [`MeResource`](../04-app-rbac-cliente.md)) |
| `POST` | `/me/budgets/{orderNumber}/approval` | CLIENT | `{decision: APPROVE\|REJECT}`; `by=CLIENT_JWT` |
| `GET` | `/me/budgets/{orderNumber}/payment` | CLIENT | Pix atual: `qrCode` (copia e cola), `qrCodeBase64`, `expiresAt`, `status` |
| `POST` | `/webhooks/mercadopago` | **sem JWT**; assinatura | recebe notificações (§5) |
| `GET` | `/q/health/*`, `/q/metrics` | interno | probes e métricas |

As rotas `/admin/*`, `/me/*` e `/public/*` já existem como *proxy* no gateway; só `/webhooks/mercadopago` é rota nova ([12](12-infra-k8s-mensageria.md) §5).

## 4. Integração Mercado Pago (o que a documentação confirma e o que falta confirmar)

Lido em 2026-11-22 na documentação oficial (pt-BR). **Confirmado:** `POST https://api.mercadopago.com/v1/payments` com
`Authorization: Bearer`, `X-Idempotency-Key` (obrigatório para evitar cobrança duplicada), corpo com `transaction_amount`,
`payment_method_id: "pix"`, `payer.email`; resposta com `id`, `status: pending` e `point_of_interaction.transaction_data.qr_code`
/ `qr_code_base64` / `ticket_url`; expiração padrão **24 h**, configurável (`date_of_expiration`, 30 min a 30 dias);
notificação **configurável por pagamento** (prioridade sobre a URL global); webhook com corpo `{action, type:"payment", data.id}`,
resposta `200/201` em até 22 s, reenvio a cada 15 min; consulta `GET /v1/payments/{id}`; estorno `POST /v1/payments/{id}/refunds`
(Pix permitido, até 180 dias, exige saldo; erro padrão `400`); cancelamento só para `pending`/`in_process`.

**Pendências de verificação** (marcar ✅ na F4 com as credenciais do usuário; nenhuma bloqueia a modelagem porque o gateway é uma porta):

| # | Ponto | Risco / plano |
|---|---|---|
| V1 | A **Payments API está marcada como legada** ("sem novas funcionalidades"); a doc recomenda **Orders API** para integrações novas | Decidir na F4: Payments (mais simples, documentada para Pix e webhook) × Orders. Isolado no adapter; trocar não toca o domínio. Registrar no ADR 0021 |
| V2 | Nome literal do campo da notificação por pagamento (`notification_url`) | confirmar na referência da API (a URL consultada devolveu 404) |
| V3 | **Manifesto e algoritmo da assinatura** `x-signature` (`ts`, `v1`; uso de `x-request-id` e `data.id`) | a doc lida só cita o SDK; ler a aba "sem SDK" antes de implementar. Sem confirmar, **não** confiar na assinatura — a defesa de fundo é a §5 passo 3 (reconsulta) |
| V4 | **Como simular um Pix aprovado/recusado no sandbox** | não encontrado na doc. Plano B: `PaymentGateway` fake com endpoint de simulação ativo **só** nos perfis `dev`/`hml`; a demo em produção usa a conta de teste e a simulação de notificação do painel |
| V5 | Status/`status_detail` de um Pix que expira | tratar `cancelled` (qualquer detalhe) + `expired` + prazo local de `expires_at` como `EXPIRED` |
| V6 | Prazo de validade da chave de idempotência | derivar a chave de `(paymentId local, tentativa)` e **persistir antes** de chamar a API |

Credenciais: Access Token de teste em **SSM Parameter Store** (SecureString), lido na subida (padrão da licença do New Relic); `MP_WEBHOOK_SECRET` idem. **Nunca** em repositório, log ou resposta de erro; mascarar nos logs.

## 5. Webhook (rota pública, a mais sensível do sistema)

1. Rate limit no gateway (throttling dedicado à rota) e corpo limitado a 16 KB.
2. Verificar a assinatura (V3). Falhou → `401`, sem corpo útil, contador `billing_webhook_invalid_signature_total`.
3. **Não confiar no corpo**: extrair só `data.id`, chamar `GET /v1/payments/{id}` e agir sobre o **status devolvido pela API**.
4. Localizar `payment` por `mp_payment_id`; desconhecido → `200` e ignora (evita reenvio infinito) com log.
5. Aplicar a transição de forma idempotente; gravar `payment_event`; publicar `PaymentApproved|Rejected|Expired` via outbox.
6. Responder `200` rápido (< 22 s); trabalho pesado depois do `commit`.

**Reconciliação**: *job* a cada 60 s consulta na API os pagamentos `PENDING` com mais de 2 min (webhook perdido ou rede do Lab sem
IP público estável) — a Saga não depende exclusivamente do webhook.

## 6. Resiliência da chamada ao Mercado Pago

*Timeouts* de conexão 3 s / leitura 10 s; *retry* com *backoff* só em erro de rede/5xx e **sempre** com a mesma `X-Idempotency-Key`;
*circuit breaker* (SmallRye Fault Tolerance) com `PaymentRequestFailed` quando aberto; nunca tentar de novo uma criação sem a chave.

## 6.1 Implementação (2026-11-23, repositório local `../oficina-billing-service`)

- Quarkus 3.15.7 / Java 21, hexagonal: `domain` e `application` em Java puro (montados por `ApplicationWiring`). Agregados
  `Budget` e `Payment` com eventos registrados e `Snapshot` (Memento) para persistir; `Money` em `BigDecimal` com 2 casas.
- **Strategy** por comando (`GenerateBudget`, `RequestPayment`, `VoidBudget`, `RefundPayment`) e por provedor
  (`PAYMENT_PROVIDER=simulated|mercadopago`). `RequestPayment` e `RefundPayment` em três passos (transação → provedor fora
  de transação → transação), com a chave de idempotência gravada antes da chamada (V6 resolvida assim).
- Diferenças em relação ao rascunho: sem `DRAFT` persistido (o orçamento nasce `SENT`); `Payment` ganhou `REQUESTED`
  (intenção gravada antes do provedor) e `FAILED` (provedor recusou/indisponível); qualquer orçamento pode ir a `VOIDED`
  (a compensação sempre confirma); pagamento aprovado depois de `EXPIRED`/`REJECTED` volta a `APPROVED` e é publicado
  (órfão, a Saga estorna); Pix pendente além do prazo é expirado pela reconciliação mesmo sem resposta do provedor; pedido
  sem resposta do provedor por 5 min é encerrado como `PaymentRequestFailed`.
- `PaymentGatewayPort` com **2** implementações em vez de 3: Mercado Pago e um simulado que é ao mesmo tempo determinístico
  (testes) e simulável (`POST /admin/payments/{id}/simulate`, só com `PAYMENT_SIMULATION_ENABLED=true`, ligado no overlay hml).
- Webhook: assinatura HMAC-SHA256 com o manifesto `id:…;request-id:…;ts:…;` (**V3 ainda não confirmada** — a documentação
  lida confirma só o formato `ts`/`v1`), *fail closed* com o provedor real; reconsulta sempre.
- Contrato do adapter: servidor HTTP local (JDK) no formato documentado, em vez de WireMock gravado — falta regravar com o
  sandbox. `gitleaks` no CI (job `secret-scan`).
- **Testes:** 138 (domínio, casos de uso em memória, contrato, adapter MP, webhook, PostgreSQL real com concorrência,
  LocalStack, API com JWT real); cobertura 94,7% linhas / 85,3% ramos. Smoke `docker compose`: comando na fila →
  `BudgetGenerated` → aprovação pelo link → `PaymentRequested`, com `correlationId`/`causationId` preservados.
- **Bug achado nos testes com PostgreSQL:** o Hibernate do Quarkus só rastreia alterações feitas pelos métodos da própria
  entidade; atribuições de campo feitas pelo repositório não eram gravadas (o mapeamento passou para dentro das entidades).
- **Pendente:** credenciais de teste do Mercado Pago (usuário) e V1–V5; criação do repositório no GitHub (usuário); RDS (D3)
  e filas (spec 12). O e-mail de orçamento duplicado no OS foi corrigido no PR #38.

## 7. Critérios de aceite

- [ ] `PaymentGatewayPort` com 3 implementações: Mercado Pago, fake determinístico (testes) e fake simulável (`dev`/`hml`).
- [ ] Testes de domínio: transições válidas/ inválidas de `Budget` e `Payment`; invariante de um pagamento ativo (teste de concorrência).
- [ ] Teste do webhook: assinatura inválida, corpo adulterado com `data.id` de outro pagamento (a reconsulta prevalece), duplicado, desconhecido.
- [ ] Teste de contrato do adapter com WireMock gravado das respostas reais do sandbox (sem credencial no repositório).
- [ ] Pagamento Pix de teste ponta a ponta no sandbox, com `PaymentApproved` chegando ao OS (evidência para o vídeo).
- [ ] Estorno de teste com `RefundPayment` → `PaymentRefunded`; falha simulada → `PaymentRefundFailed`.
- [ ] Nenhum segredo em log (`grep` nos logs da sessão) e nenhuma credencial no repositório (varredura com `gitleaks` no CI).

## 8. Fora de escopo

Cartão de crédito/débito, boleto, assinaturas; conciliação financeira/contábil; notas fiscais; múltiplas moedas; Checkout Pro.
