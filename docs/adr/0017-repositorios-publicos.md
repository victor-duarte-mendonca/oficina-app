# ADR 0017 — Repositórios públicos sob organização GitHub gratuita

- **Status:** Aceito (decisão fechada em 2026-09-09)
- **Origem:** [`specs/08-repositorios-cicd.md`](../../specs/08-repositorios-cicd.md) §3

## Contexto

R4 e R5 pedem quatro repositórios com `main` protegida, PR obrigatório e deploy automático por ambiente. A conta GitHub é do plano Free.

## Decisão

Os quatro repositórios (`oficina-app`, `oficina-auth-lambda`, `oficina-infra-k8s`, `oficina-infra-database`) são **públicos**, na
organização `victor-duarte-mendonca`. Rulesets em `main` e `homolog` (sem push direto, force-push ou exclusão; PR obrigatório; squash;
checks obrigatórios com branch atualizada; **sem bypass**) e Environments `homologacao` e `producao` (este com *required reviewer*).
Pré-condição aplicada no split: histórico limpo de credenciais (`k3s-key.pem`, `kubeconfig`, `terraform.tfstate`, `tfplan`) por
`git filter-repo`, com varredura antes do primeiro push.

## Consequências

- (+) No plano Free, rulesets, Environments com revisor e organization secrets **só existem em repositório público**; minutos de
  Actions são ilimitados.
- (+) O acesso do avaliador (`soat-architecture`) é direto.
- (−) Todo o código, workflows e specs são públicos: nenhum segredo pode existir no histórico (risco permanente; segredos só em
  GitHub Secrets). `test-keys/*.pem` são fixtures documentadas.
- (−) As aprovações obrigatórias estão em 0 (um único autor); a proteção vale por checks e PR, não por revisão humana.
- (−) Informação de infraestrutura (nomes de recurso, estrutura de rede) fica visível.

## Alternativas consideradas

- **Repositórios privados:** R5 exigiria plano pago (rulesets e Environments com revisor não existem em privado no Free) e os quatro
  repositórios dividiriam 2.000 min/mês de Actions, apertados com builds Maven, `terraform plan` por PR e scan de imagem.
- **Um monorepo:** viola R4.
- **Plano pago:** custo sem benefício além do que a visibilidade pública já entrega.
