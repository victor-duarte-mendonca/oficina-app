# RFC 0003 — Estratégia de autenticação

- **Status:** Aprovado (recomendação adotada)
- **Decisões derivadas:** [ADR 0004](../adr/0004-autenticacao-por-cpf.md), [0005](../adr/0005-lambda-na-vpc-somente-leitura.md),
  [0006](../adr/0006-emissor-unico-de-jwt.md), [0007](../adr/0007-autorizacao-em-duas-camadas.md)

## 1. Problema

O cliente da oficina precisa acessar as próprias ordens de serviço. O PDF pede uma Function Serverless que valide o CPF, consulte o
status do cliente e devolva um JWT. Funcionários (`ADMIN`, `MECHANIC`) já se autenticam por usuário e senha. É preciso decidir como o
**cliente** se autentica e o que isso custa em segurança.

## 2. Opções

| Opção | Fator | Prós | Contras |
|---|---|---|---|
| **A. CPF sem senha → JWT curto** | só o CPF | sem cadastro de senha; coerente com o domínio; atende literalmente R3 | **fator fraco** (dado semi-público); sem recuperação nem revogação |
| B. Usuário e senha | senha | fator conhecido | exige cadastro, recuperação e armazenamento de senha de cliente que o domínio não tem |
| C. OAuth2/OIDC (Cognito) | senha/MFA/social | padrão de mercado; MFA; refresh e revogação | configuração e custo; o JWT authorizer nativo do gateway exigiria domínio HTTPS que o laboratório não tem |
| D. CPF + OTP (SMS/e-mail) | CPF + posse | eleva o fator | SNS/SES custam e não é pedido |

## 3. Recomendação

**Opção A**, com mitigações explícitas para o risco do CPF como credencial:

| Risco | Mitigação |
|---|---|
| Enumeração de CPFs (oráculo "é cliente?") | 401 **indistinguível** (inexistente, `INACTIVE`, `BLOCKED`), tempo de resposta aproximadamente constante (não retorna antes da consulta); o motivo real só no log |
| Força bruta / varredura | throttling no gateway: 10 rps, burst 20 em `POST /auth/token`; `reserved_concurrent_executions` na função; alerta `oficina_auth_denied_total` > 50 em 5 min |
| Token roubado | `exp` de 1 h; CPF **mascarado** no claim (o token trafega em logs); `jti` presente para denylist futura |
| Abuso após autenticar | papel `CLIENT` restrito ao **próprio** recurso (`clientId` do token, nunca do path); 404, não 403, para recurso alheio |
| Comprometimento da função | papel de banco somente-leitura em 5 colunas; função sem internet; segredos só em variáveis de ambiente (ADR 0012) |
| CPF inválido | validação de dígitos e das 10 sequências repetidas antes de tocar o banco (400) |

Emissão e validação: JWT RS256, **mesmo par de chaves e mesmo `iss`** da aplicação (ADR 0006); validado no gateway (authorizer) e de
novo na aplicação (ADR 0007).

## 4. Riscos e pontos em aberto

- Sem revogação nem refresh: um token vale até expirar (1 h); o cache do authorizer (300 s) estende isso no gateway.
- Quem souber o CPF de um cliente ativo e passar pelo throttling obtém um token dele. Aceitável para este laboratório; não para dados
  sensíveis em produção.

## 5. Caminho de evolução

1. **CPF + OTP** (opção D) se o risco crescer, mantendo o mesmo contrato de token.
2. **OIDC/Cognito** (opção C) quando houver domínio e certificado: troca o authorizer por JWT authorizer nativo e traz refresh,
   revogação e MFA.
3. Denylist de `jti` (já previsto no claim) para revogação pontual.

## 6. Contestação

A principal objeção é a força do fator. A recomendação a aceita deliberadamente por ser a exigência literal do desafio e por limitar o
dano (escopo do papel, TTL, throttling, observabilidade do abuso) em vez de fingir que o CPF é um segredo.
