# ADR 0023 — Stripe (Pix, modo de teste) atrás da porta `PaymentGateway`

- **Status:** Proposto
- **Substitui:** [ADR 0021](0021-pagamento-mercado-pago-atras-de-porta.md) (Mercado Pago)
- **Origem:** [`specs/fase4/05-billing-service-mercado-pago.md`](../../specs/fase4/05-billing-service-mercado-pago.md) §4

## Contexto

O PDF da Fase 4 pede integração com um provedor de pagamento (Mercado Pago como exemplo). A autenticação no Mercado Pago não funcionou
para o usuário, o que bloqueava o sandbox. A conta de teste da Stripe (`Victor LTDA`, país BR, `livemode=false`) está acessível pelo
MCP da Stripe. A arquitetura já isolava o provedor na porta `PaymentGatewayPort`; a infraestrutura segue efêmera (a URL do API Gateway
muda a cada sessão do Academy).

## Decisão

Trocar o adapter para a **Stripe**, com **Pix via PaymentIntent em BRL** no modo de teste. O domínio, a Saga, o outbox e os contratos
não mudam. O webhook (`POST /webhooks/stripe`) valida `Stripe-Signature` (HMAC-SHA256 sobre `t.corpo`, tolerância 5 min) **e reconsulta**
o PaymentIntent na API antes de agir; a reconciliação periódica continua. Estorno via `POST /v1/refunds`. Idempotência por
`Idempotency-Key` derivada de `(paymentId, tentativa)` e persistida antes da chamada.

A Stripe registra a URL do webhook **no endpoint**, não por pagamento (diferença em relação ao Mercado Pago): o deploy de cada sessão
atualiza a URL do endpoint pela API; o segredo `whsec_` não muda.

## Consequências

- (+) Sandbox acessível e documentado (página "Simulate scan" autoriza/expira o Pix); assinatura de webhook totalmente documentada
  (fecha a pendência V3 do Mercado Pago).
- (+) Troca isolada no adapter; o adapter simulado continua sendo o caminho de CI e homologação.
- (−) Pré-requisito do usuário: ativar `pix_payments` na conta (hoje `inactive`, `charges_enabled=false`).
- (−) Pix na Stripe é pensado para fluxo com Stripe.js/QR; confirmar no servidor exige validar os campos de `next_action` (S2).
- (−) Automatizar "Pix pago" no sandbox ainda não está comprovado (S4); o vídeo pode usar a página hospedada.
- (−) Cobrança real de Pix exigiria revisar elegibilidade da conta e do ramo de atividade; **fora do escopo** (apenas teste).

## Alternativas consideradas

- **Manter Mercado Pago:** bloqueado por autenticação; sem credencial não há sandbox.
- **Provedor só simulado:** atende aos testes, mas não demonstra integração real exigida pelo desafio.
- **Stripe Checkout Session / Payment Links:** menos código, mas redireciona o cliente e esconde o QR; o fluxo do Billing precisa do QR
  para a área do cliente.
- **Pix Automático (mandatos):** recorrência não é requisito.
