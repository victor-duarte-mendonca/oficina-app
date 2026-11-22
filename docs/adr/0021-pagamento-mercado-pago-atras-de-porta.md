# ADR 0021 — Mercado Pago atrás de uma porta `PaymentGateway`

- **Status:** Proposto
- **Origem:** [`specs/fase4/00-visao-geral.md`](../../specs/fase4/00-visao-geral.md) §2 (R3)

## Contexto

O PDF exige integração com o Mercado Pago para pagamentos. A infraestrutura é efêmera: a URL do API Gateway muda a cada sessão
do Academy, e testes de CI não podem depender de rede externa.

## Decisão

O domínio do Billing depende de uma porta `PaymentGateway` (criar cobrança, consultar, estornar). O adapter Mercado Pago usa
**Pix em sandbox**, com `X-Idempotency-Key` por tentativa e `notification_url` informada na criação do pagamento (a confirmar na
documentação vigente), o que dispensa reconfigurar a URL no painel a cada sessão. O webhook entra pelo gateway **sem JWT**, mas
valida a assinatura (`x-signature`) e reconsulta o pagamento na API antes de agir. Testes usam um fake (e WireMock para o adapter).
Credenciais ficam fora do repositório (SSM/secret), fornecidas pelo usuário.

## Consequências

- (+) Domínio testável sem rede; troca de provedor isolada no adapter.
- (+) Webhook não confia no corpo recebido: reconsulta e valida assinatura.
- (−) Rota pública sem JWT exige rate limit e validação rigorosa.
- (−) Comportamento do sandbox pode divergir da produção.

## Alternativas consideradas

- **Checkout Pro (preferência + redirecionamento):** mais telas e fluxo de retorno, sem ganho para a demonstração.
- **Cartão no sandbox:** exige tratamento de dados sensíveis/tokenização no cliente; Pix é mais simples e suficiente.
