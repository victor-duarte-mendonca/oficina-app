# Roteiro do vídeo — observações para a gravação

Anotações para ler durante a gravação (sessão de validação em produção de 2026-11-01). Não substituem o roteiro:
só registram o que **aparece na tela** e precisa de explicação.

## 1. Smoke test do `auth-lambda` aparece vermelho no pipeline

**Onde aparece:** Actions → `oficina-auth-lambda` → workflow *Deploy* → job `apply`, passo
**"Smoke test pelo gateway (ponta a ponta)"**, falha em
`POST /auth/token com CPF do cliente de teste não devolveu access_token`.

**O que dizer:** a infraestrutura foi aplicada com sucesso (`terraform apply` verde) e as verificações
anteriores do smoke passaram (health, 400 para CPF inválido, 401 para CPF inexistente com o RDS alcançado,
403 para token forjado, login e `/admin/clients` com 200). A falha é **throttling intencional**:
o smoke dispara uma rajada de 150 requisições em `POST /auth/token` para provar o limite por rota
(10 req/s, rajada de 20), e a chamada seguinte, 5 s depois, ainda recebeu **429** do gateway porque o balde
do API Gateway não é exato. O access log mostra o 429 sem latência de integração, ou seja, a requisição foi
barrada no gateway e nunca chegou à Lambda (que não registrou erro). É o controle contra enumeração de CPF
funcionando; o defeito era do teste, não do sistema.

**Evidência:** o mesmo fluxo foi validado depois pelo Postman (pastas 1, 2, 3 e 11, fluxo completo do cliente).

**Correção:** `oficina-auth-lambda#17` — o passo repete a chamada até 6 vezes, com 5 s entre as tentativas,
quando recebe 429. Fica valendo a partir do próximo deploy.

## 2. O `correlationId` do gateway não é o mesmo dos logs da app (nesta gravação)

**Onde aparece:** a resposta de `POST /auth/login` pelo gateway traz `apigw-requestid` (ex.: `GLLn_hudoAMEVcg=`)
e `X-Correlation-Id` (um UUID, gerado pela app). Os dois deveriam ser iguais.

**Causa:** o `CorrelationIdFilter` da app só aceitava `[A-Za-z0-9._:-]` e descartava o `requestId` do gateway,
que termina em `=`. Corrigido em `oficina-app#32` (aceita `=`, com teste); só vale depois de um novo deploy da app.

**O que mostrar na gravação:** o `correlationId` nos logs da app (`service.name = oficina-mecanica`) e o
`requestId` no access log do gateway (`service.name = oficina-prod`), cada um por si. Não afirmar que o mesmo
id liga os dois nesta sessão.
