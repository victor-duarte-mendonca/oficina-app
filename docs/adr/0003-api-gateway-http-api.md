# ADR 0003 — AWS API Gateway HTTP API como gateway de borda

- **Status:** Aceito
- **Origem:** [`specs/03-api-gateway.md`](../../specs/03-api-gateway.md) §2 e §5

## Contexto

O requisito R1 pede um API Gateway para controle e roteamento. Ele precisa integrar uma função Lambda (autenticação) e o
cluster k3s (rotas `/admin`, `/me`, `/public`) e não pode ter custo por hora relevante num orçamento de ~US$48.

## Decisão

Usar o **API Gateway HTTP API (v2)**, uma API por ambiente (`oficina-hml`, `oficina-prod`). Sete rotas: `POST /auth/token`
(Lambda, `AWS_PROXY`), `POST /auth/login`, `ANY /admin/{proxy+}`, `ANY /me/{proxy+}`, `GET /me`, `GET /health` e
`GET /public/{proxy+}` (`HTTP_PROXY` para o EIP do cluster). `/q/metrics`, `/q/health/live`, `/swagger-ui` e `/openapi` **não**
são expostas. O gateway injeta `X-Gateway-Key` e `X-Correlation-Id` (sobrescritos, o cliente não os forja) e aplica
throttling por rota (mais restrito em `POST /auth/token`).

## Consequências

- (+) Cobrança por uso (US$1,00/milhão de requisições), **US$0 quando ninguém chama**.
- (+) Integração nativa com Lambda e com HTTP; access log em JSON no CloudWatch.
- (−) **Sem TLS entre o gateway e o cluster** (HTTP na porta 80): sem domínio não há certificado válido, e certificado
  autoassinado com `insecureSkipVerify` *aparenta* proteção sem dá-la. Mitigação: o security group do cluster aceita só as
  faixas IP do API Gateway (+ o `/32` do aluno) e a aplicação exige `X-Gateway-Key`. Caminho de produção: domínio + ACM no
  gateway e cert-manager no Traefik.
- (−) O HTTP API **não propaga contexto W3C de trace**: o trace OTLP começa na aplicação e o gateway se liga por
  correlação de logs (`X-Correlation-Id`), não como span.
- (−) Recursos que não usamos (modelos, validação de request, WAF integrado) existem só no REST API.

## Alternativas consideradas

| Opção | Por que não |
|---|---|
| API Gateway REST API (v1) | US$3,50/milhão, 3,5× mais caro por recursos não usados |
| Kong | Exige EC2 dedicada (~US$15/mês) e Postgres próprio; custo e operação sem contrapartida |
| Traefik do k3s | Não integra o Lambda de forma limpa e morre com o node |
| ALB + Lambda target | ~US$16/mês fixos |
