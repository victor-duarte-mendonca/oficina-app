# ADR 0022 — Credenciais AWS dos pods via IMDS restrito por NetworkPolicy

- **Status:** Proposto
- **Origem:** [`specs/fase4/12-infra-k8s-mensageria.md`](../../specs/fase4/12-infra-k8s-mensageria.md) §6

## Contexto

O `http_put_response_hop_limit = 1` da EC2 do k3s impede de propósito que pods alcancem o IMDS (defesa contra SSRF). Na Fase 4 os
serviços Billing e Execution precisam chamar SQS e DynamoDB com credenciais AWS, e o Academy não oferece IRSA/EKS Pod Identity nem
credenciais IAM persistentes (as da sessão expiram).

## Decisão (proposta)

`hop_limit = 2` **e** `NetworkPolicy` de *egress* que libera `169.254.169.254/32` **apenas** aos pods dos três serviços
(`app` ∈ {`oficina-os`, `oficina-billing`, `oficina-execution`}); todos os demais pods (Traefik, agentes) permanecem sem acesso.
IMDSv2 continua obrigatório. Teste de aceite: pod avulso não alcança o IMDS; pod de serviço faz `SendMessage`.

## Consequências

- (+) Credenciais renovadas automaticamente, sem segredo em Secret ou repositório.
- (−) Qualquer vulnerabilidade de SSRF num dos três serviços expõe o `LabRole` inteiro; não há segregação IAM por serviço (limitação do Academy).
- (−) Depende de a `NetworkPolicy` ser efetivamente aplicada pelo k3s (verificar no spike da F2).

## Alternativas consideradas

- **Credenciais da sessão copiadas para Secret:** expiram em horas; manual; vazamento de token.
- **Sidecar/proxy de credenciais:** complexidade desproporcional.
- Em AWS real: IRSA/EKS Pod Identity com um papel por serviço (seria a escolha correta fora do Academy).
