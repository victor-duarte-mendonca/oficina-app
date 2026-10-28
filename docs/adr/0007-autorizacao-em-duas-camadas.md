# ADR 0007 — Autorização em duas camadas (Lambda authorizer + RBAC na aplicação)

- **Status:** Aceito (decisão fechada em 2026-09-09)
- **Origem:** [`specs/03-api-gateway.md`](../../specs/03-api-gateway.md) §4; [`specs/04-app-rbac-cliente.md`](../../specs/04-app-rbac-cliente.md)

## Contexto

O PDF diz que a Function devolve "um token (JWT) válido para consumo das APIs protegidas"; validar o token só na aplicação
atenderia o texto. Mas o gateway seria então apenas roteamento, e "proteger rotas sensíveis" ficaria atendido pela metade.

## Decisão

**Camada 1 — Lambda authorizer no gateway** (`REQUEST`, *simple response*, `identitySource = Authorization`, cache de 300 s) nas
rotas `/admin/*` e `/me/*`. Valida assinatura RS256 com a **chave pública**, `iss`, `exp` e a presença de um `groups` conhecido.
Roda **fora da VPC**: não toca no banco, não consome ENI e não paga cold start de VPC. Header ausente → 401; token recusado → 403.

**Camada 2 — a aplicação revalida o JWT** (SmallRye, `@RolesAllowed`) e decide *o que pode fazer*: papel e **posse do recurso**
(`OwnershipGuard`; 404, não 403, para recurso de outro cliente, para não revelar que ele existe). Um `GatewayKeyFilter` rejeita com
403 qualquer requisição a `/admin/*` ou `/me/*` sem o `X-Gateway-Key` correto, comparado em tempo constante.

Nenhuma camada confia na outra: quem alcançar a EC2 por outro caminho ainda precisa de um JWT válido e da chave do gateway.

## Consequências

- (+) Tráfego sem token válido **nunca chega ao cluster** (visível no access log: `authorizer.error` preenchido e
  `integrationLatency` vazio).
- (+) O requisito "proteger rotas" é verdadeiro no perímetro, não só na aplicação.
- (−) **Novo modo de falha:** se o authorizer cair, todas as rotas protegidas param. Contido por ele ser minúsculo, sem rede, sem
  banco e sem segredo (só a chave pública).
- (−) Cache de 300 s: um token revogado/bloqueado continua aceito pelo gateway até o cache expirar (e não há revogação, ADR 0004).
- (−) Anônimo sem token recebe **401** no filtro da aplicação, não 403 (achado de implementação; decisão em aberto sobre unificar).

## Alternativas consideradas

- **JWT authorizer nativo do HTTP API:** mais simples e gratuito, mas exige um emissor OIDC por HTTPS com
  `/.well-known/openid-configuration` e JWKS. O laboratório não tem domínio nem certificado. É o caminho natural quando houver
  domínio real.
- **Só a aplicação valida:** o gateway vira proxy puro e o requisito fica atendido só na letra.
- **Authorizer dentro da VPC:** consumiria ENI e cold start de VPC em cada requisição não cacheada, sem precisar do banco.
