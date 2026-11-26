# Spec 05 — Billing Service e integração com a Stripe (Pix, modo de teste)

> Atende **R1** (serviço de Orçamento e Pagamento) e **R3** (integração com provedor de pagamento — **Stripe**, que substituiu o Mercado Pago em 2026-11-26). ADR: [0023](../../docs/adr/0023-pagamento-stripe-atras-de-porta.md) (substitui o [0021](../../docs/adr/0021-pagamento-mercado-pago-atras-de-porta.md)).
> Repositório novo: `oficina-billing-service`. Banco: [02](02-persistencia-sql-nosql.md) §3. Mensagens: [03](03-mensageria-e-contratos.md). Saga: [04](04-saga-orquestrada.md).
> **Responsabilidade do usuário:** ativar o Pix na conta de teste da Stripe e fornecer a chave de API de teste e o segredo do webhook (§4.2).

## 1. Responsabilidades (PDF)

1. **Gerar e enviar orçamentos para aprovação** — ao receber `GenerateBudget`.
2. **Registrar e verificar pagamentos** — Pix via Stripe (PaymentIntent); confirmação por webhook **e** reconciliação por consulta.
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
| `POST` | `/webhooks/stripe` | **sem JWT**; assinatura | recebe notificações (§5) |
| `GET` | `/q/health/*`, `/q/metrics` | interno | probes e métricas |

As rotas `/admin/*`, `/me/*` e `/public/*` já existem como *proxy* no gateway; só `/webhooks/stripe` é rota nova ([12](12-infra-k8s-mensageria.md) §5).

## 4. Integração Stripe (Pix em modo de teste)

> **Mudança de provedor (2026-11-26):** o Mercado Pago foi substituído pela **Stripe** porque a autenticação no Mercado Pago não
> funcionou para o usuário. Motivação, alternativas e consequências: [ADR 0023](../../docs/adr/0023-pagamento-stripe-atras-de-porta.md)
> (substitui o 0021). A porta `PaymentGatewayPort` não muda; muda só o adapter. O adapter do Mercado Pago (já implementado)
> fica no repositório até o da Stripe passar nos mesmos testes de contrato e então é removido.

Fonte: documentação oficial da Stripe, lida pelo MCP em 2026-11-26. **Conta de teste** `Victor LTDA` (`country=BR`, `livemode=false`).

**Confirmado na documentação:**

- Pix é método de pagamento de **PaymentIntent** (moeda **BRL**, mínimo **R$ 0,50**, teto padrão ≈ US$ 3.000). Contas BR liquidam em BRL.
- Pix é **assíncrono por QR code**: o cliente paga no app do banco; o PaymentIntent fica `requires_action` até `succeeded`.
- **Expiração**: `payment_method_options.pix.expires_after_seconds` (60 a 1.209.600 s; padrão 4 h) **ou** `expires_at` — não os dois. A própria
  documentação manda **não confiar** no `expires_at` devolvido e usar o evento `payment_intent.payment_failed` como sinal de expiração.
- **Cancelamento** antes de expirar: `POST /v1/payment_intents/{id}/cancel`.
- **Teste (sandbox)**: CPF/CNPJ de teste `000.000.000-00`; o botão **Simulate scan** abre uma página hospedada da Stripe onde o teste é **autorizado ou
  expirado**; valores especiais em `billing_details.email` forçam outros cenários (a tabela não veio na consulta — ver S4).
- **Webhook**: cabeçalho `Stripe-Signature` com `t=<epoch>` e um ou mais `v1=<hmac>`; `signed_payload = t + "." + corpo cru`; HMAC-SHA256 com o
  segredo do endpoint (`whsec_…`); comparação em tempo constante; **tolerância de 5 min** contra replay (nunca 0); ignorar `v0` (só sandbox) e
  entradas desconhecidas; responder `2xx` antes de qualquer trabalho pesado. Segredos diferem entre endpoint do painel e `stripe listen` (CLI).
- **Estorno**: `POST /v1/refunds` por PaymentIntent/Charge; Pix aceita **total e parcial em até 90 dias**, conclui em minutos, **não pode ser
  cancelado**; sucesso na requisição **não prova** o crédito na conta do cliente — acompanhar o `Refund.status`.
- **Disputa** de Pix existe (MED, só fraude) e **não pode ser contestada**; fora do escopo da demo, mas vira `charge.dispute.created` (logar).

**Estado da conta de teste (lido pela API em 2026-11-26) — bloqueio:** `capabilities.pix_payments = inactive`, `charges_enabled = false`,
`details_submitted = false`, `disabled_reason = requirements.past_due`. **Sem ativar o Pix a criação do pagamento falha.** Ação do usuário em §4.2.

### 4.1 Mapeamento da porta `PaymentGatewayPort`

| Operação da porta | Chamada Stripe | Observações |
|---|---|---|
| `createPixPayment(paymentId, amount, payer, idempotencyKey)` | `POST /v1/payment_intents` com `confirm=true`, `currency=brl`, `automatic_payment_methods[enabled]=true`, `automatic_payment_methods[allow_redirects]=never`, `payment_method_data[type]=pix`, `payment_method_data[billing_details]` (nome, e-mail, CPF/CNPJ se exigido), `payment_method_options[pix][expires_after_seconds]`, `metadata[paymentId, orderNumber, sagaId]`; cabeçalho `Idempotency-Key` | `amount` em **centavos** (`Money` → `long`); QR em `next_action.pix_display_qr_code` (`data` = copia e cola, `image_url_png`, `hosted_instructions_url`, `expires_at`) — **nomes confirmados (S2)** |
| `getPayment(providerId)` | `GET /v1/payment_intents/{id}` | `succeeded`→`APPROVED`; `requires_action`/`processing`→`PENDING`; `canceled` ou `requires_payment_method` com `last_payment_error`→`REJECTED`/`EXPIRED` (S5) |
| `refund(providerId, amount, idempotencyKey)` | `POST /v1/refunds` com `payment_intent`, `amount`; `Idempotency-Key` | `Refund.status`: `succeeded`→`REFUNDED`; `pending`→`REFUND_PENDING`; `failed`→`REFUND_FAILED` |
| cancelar Pix pendente (compensação) | `POST /v1/payment_intents/{id}/cancel` | só antes de `succeeded` |

`mp_payment_id` na tabela `payment` vira `provider_payment_id` (coluna e índice únicos mantêm a semântica; migração Flyway nova, sem apagar a V1).
Resposta do provedor mapeada para o `ProviderStatus` do domínio — o domínio **não** conhece o vocabulário da Stripe.

### 4.2 O que o usuário precisa fazer/fornecer

1. **Ativar o Pix** na conta de teste: Dashboard (modo teste/sandbox) → *Settings → Payment methods → Pix* (e completar o cadastro mínimo que a conta
   BR pedir; em sandbox aceitam-se dados fictícios). Conferir depois: `pix_payments = active`. O Claude relê a conta pelo MCP para validar.
2. **Chave de API de teste restrita** (`rk_test_…`, ou `sk_test_…` se preferir) com permissão de **escrita em PaymentIntents e Refunds** e **leitura**
   de eventos/webhook endpoints. **Não colar no chat**: colocar em SSM Parameter Store (`SecureString`) e no secret `STRIPE_API_KEY` do ambiente
   (mesmo padrão da licença do New Relic).
3. **Segredo de assinatura do webhook** (`whsec_…`) do endpoint criado — secret `STRIPE_WEBHOOK_SECRET`. Idem: não colar no chat.
4. Confirmar se aceita o **Stripe CLI** (`stripe listen --forward-to`) para o desenvolvimento local; no Lab o endpoint é o do API Gateway (§5).

### 4.3 Verificações pendentes (substituem V1–V6 do Mercado Pago)

| # | Ponto | Plano |
|---|---|---|
| S1 | ✅ **Pix funciona na conta de teste** (2026-11-26): `capabilities.pix_payments` aparece `inactive` na API, mas o sandbox aceitou o PaymentIntent Pix (forma de pagamento habilitada em *Settings → Payment methods*) | resolvido; não depender do campo `capabilities` |
| S2 | ✅ Confirmado com um PaymentIntent real de teste: `status=requires_action`; `next_action.pix_display_qr_code` = `data` (copia e cola), `expires_at`, `hosted_instructions_url`, `image_url_png`, `image_url_svg`; **CPF/CNPJ não foi exigido** (conta BR, só nome e e-mail); `amount` em centavos | resolvido. **Não enviar `payment_method_types`** (a API recusa): usar `automatic_payment_methods[enabled]=true` + `automatic_payment_methods[allow_redirects]=never` + `payment_method_data[type]=pix`; sem isso exige `return_url`. O intent lista `card` e `pix` em `payment_method_types` (o Pix é escolhido pelo `payment_method_data`) |
| S3 | **URL do webhook muda a cada sessão do Academy**; a Stripe cadastra a URL **no endpoint** (não por pagamento) | script de deploy atualiza a URL do endpoint pela API (`WebhookEndpoints`) — o `whsec_` não muda; a reconciliação (§5) cobre a janela sem webhook |
| S4 | **Automatizar** o Pix aprovado/expirado no sandbox sem abrir a página "Simulate scan" | testar os e-mails de cenário de `billing_details.email` e o *test clock*; plano B: `SimulatedPaymentGateway` (já existe) para CI/hml e a página hospedada para o vídeo |
| S5 | Status exato de Pix expirado (`payment_intent.payment_failed` × `canceled`) | tratar `payment_failed` + prazo local de `expires_at` como `EXPIRED` |
| S6 | Retenção da chave de idempotência da Stripe (a documentação lida não cita o prazo) | continuar derivando de `(paymentId, tentativa)` e **persistir antes** da chamada (V6 resolvida) |
| S7 | Versão da API fixada (`Stripe-Version`) | fixar no adapter; não depender do padrão da conta |

Biblioteca: avaliar `stripe-java` × cliente HTTP próprio (o adapter do MP usou `HttpClient` do JDK + JSON). A decisão vai no ADR 0023; critério:
tamanho/dependências da lib e compatibilidade com GraalVM/JDK 21.

Credenciais: chave e segredo do webhook em **SSM Parameter Store** (SecureString), lidos na subida. **Nunca** em repositório, log ou resposta
de erro; mascarar nos logs (`sk_`/`rk_`/`whsec_`). `gitleaks` no CI já cobre os prefixos da Stripe.

## 5. Webhook (rota pública, a mais sensível do sistema)

Rota: `POST /webhooks/stripe` (substitui `/webhooks/mercadopago`).

1. Rate limit no gateway (throttling dedicado à rota) e corpo limitado a 16 KB.
2. Verificar `Stripe-Signature` **sobre o corpo cru** (nenhum *parser* antes): `t` + `v1`, HMAC-SHA256, tempo constante, tolerância 5 min,
   ignorar `v0`/debug. Falhou → `401`, sem corpo útil, contador `billing_webhook_invalid_signature_total`.
3. **Não confiar no corpo**: extrair só o `id` do PaymentIntent do evento, chamar `GET /v1/payment_intents/{id}` e agir sobre o **status devolvido pela API**.
4. Eventos tratados: `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.canceled`, `refund.updated`/`charge.refunded`,
   `charge.dispute.created` (só log/alerta). Os demais → `200` e ignora. Deduplicar pelo `event.id` (a Stripe reenvia).
5. Localizar `payment` por `provider_payment_id` (ou `metadata.paymentId`); desconhecido → `200` e ignora com log.
6. Aplicar a transição de forma idempotente; gravar `payment_event`; publicar `PaymentApproved|Rejected|Expired` via outbox.
7. Responder `2xx` rápido; trabalho pesado depois do `commit`.

**Reconciliação**: *job* a cada 60 s consulta na API os pagamentos `PENDING` com mais de 2 min (webhook perdido, URL do Lab desatualizada) —
a Saga não depende exclusivamente do webhook.

## 6. Resiliência da chamada à Stripe

*Timeouts* de conexão 3 s / leitura 10 s; *retry* com *backoff* só em erro de rede/5xx/429 e **sempre** com a mesma `Idempotency-Key`;
*circuit breaker* (SmallRye Fault Tolerance) com `PaymentRequestFailed` quando aberto; nunca tentar de novo uma criação sem a chave.
A Stripe devolve `Stripe-Should-Retry`/`Retry-After` em alguns erros: respeitar quando presentes.

## 6.1 Implementação do rascunho com Mercado Pago (2026-11-23, histórico — o adapter da Stripe a refaz, ver §8.1)

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
- **Pendente (agora Stripe):** S1–S7 (§4.3) e as credenciais da Stripe (§4.2); criação do repositório no GitHub (usuário); RDS (D3)
  e filas (spec 12). O e-mail de orçamento duplicado no OS foi corrigido no PR #38.

## 7. Critérios de aceite

- [ ] `PaymentGatewayPort` com 3 implementações: Stripe, fake determinístico (testes) e fake simulável (`dev`/`hml`).
- [ ] Testes de domínio: transições válidas/ inválidas de `Budget` e `Payment`; invariante de um pagamento ativo (teste de concorrência).
- [ ] Teste do webhook: assinatura inválida, corpo adulterado com `data.id` de outro pagamento (a reconsulta prevalece), duplicado, desconhecido.
- [ ] Teste de contrato do adapter com WireMock gravado das respostas reais do modo de teste da Stripe (sem credencial no repositório).
- [ ] Pagamento Pix de teste ponta a ponta na Stripe (modo de teste, "Simulate scan"), com `PaymentApproved` chegando ao OS (evidência para o vídeo).
- [ ] Estorno de teste com `RefundPayment` → `PaymentRefunded`; falha simulada → `PaymentRefundFailed`.
- [ ] Nenhum segredo em log (`grep` nos logs da sessão) e nenhuma credencial no repositório (varredura com `gitleaks` no CI).

## 8. Fora de escopo

Cartão de crédito/débito, boleto, Pix Automático (mandatos), assinaturas, Stripe Connect; conciliação financeira/contábil; notas fiscais; múltiplas moedas; Checkout Session/Payment Links da Stripe (usamos PaymentIntent direto).

## 8.1 Trabalho de código decorrente da troca (repositório `oficina-billing-service`)

1. `StripePaymentGateway` + `StripeApi` (cliente HTTP) + `StripeSignature`/verificador de webhook; `PAYMENT_PROVIDER=simulated|stripe`.
2. `StripeWebhookResource` em `/webhooks/stripe`; remover `MercadoPago*` quando os testes de contrato da Stripe passarem.
3. Migração Flyway: `mp_payment_id` → `provider_payment_id`; `MP_*` → `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_API_VERSION`.
4. Métricas: `billing_mp_request_seconds` → `billing_provider_request_seconds` (label `provider`); alerta da spec 13 acompanha.
5. Egress do pod para `api.stripe.com` (NetworkPolicy, spec 12) e rota `/webhooks/stripe` no gateway.
