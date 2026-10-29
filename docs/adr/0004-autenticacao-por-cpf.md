# ADR 0004 — Autenticação de cliente por CPF, sem senha, com JWT de curta duração

- **Status:** Aceito
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §2 e §7; discussão completa em [RFC 0003](../rfc/0003-estrategia-de-autenticacao.md)

## Contexto

O requisito R3 pede uma Function Serverless que valide o CPF, consulte existência e status do cliente na base e devolva um JWT.
O cliente da oficina não tem cadastro com senha; o CPF é o identificador que ele já informa na recepção.

## Decisão

`POST /auth/token` recebe `{ "cpf": ... }` (com ou sem máscara), valida os dígitos, consulta o cliente **ACTIVE** e devolve um
JWT RS256 de **1 hora** com `groups: ["CLIENT"]`, `clientId`, `jti` e o CPF **mascarado** (`529******25`). Respostas:
400 (CPF inválido), 401 **idêntica** para inexistente, `INACTIVE` e `BLOCKED` (a distinção só existe no log estruturado), 429
vindo do throttling do gateway, 500 com `correlationId`.

## Consequências

- (+) Fluxo sem cadastro prévio de senha, coerente com o domínio.
- (+) 401 indistinguível impede usar o endpoint como oráculo de "este CPF é cliente da oficina?".
- (−) **O CPF é um fator fraco** (dado semi-público): quem souber o CPF de um cliente ativo obtém um token dele. Mitigações:
  throttling (10 rps, burst 20 em `/auth/token`), TTL curto, escopo restrito do papel `CLIENT` (só o próprio recurso, 404 e não
  403 para o alheio), papel de banco somente-leitura e log do motivo real.
- (−) Sem refresh token, sem revogação (`jti` presente para permitir denylist no futuro) e sem segundo fator.

## Alternativas consideradas

- **Usuário e senha para o cliente:** mais forte, mas exige cadastro/recuperação de senha que o domínio não tem.
- **OAuth2/OIDC (Cognito):** o caminho de evolução natural; custo e complexidade de configuração fora do escopo, e o JWT authorizer
  nativo do gateway exigiria domínio HTTPS (ADR 0007).
- **CPF + OTP por SMS/e-mail:** eleva o fator, mas custa (SNS/SES) e não é pedido.
