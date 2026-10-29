# ADR 0015 — Homologação como stack separada por workspace, subindo sob demanda

- **Status:** Aceito (decisão fechada em 2026-09-09)
- **Origem:** [`specs/06-infra-k8s-terraform.md`](../../specs/06-infra-k8s-terraform.md) §4

## Contexto

R5 exige deploy automático de homologação e produção. Os dois ambientes precisam existir como alvo de pipeline, mas o orçamento é de
~US$48 e a conta é reciclada entre sessões.

## Decisão

Workspaces Terraform **`hml` e `prod` como stacks fisicamente separadas** (EC2, RDS, gateway e Lambdas próprios), em todos os
repositórios de infra. Branch `homolog` → workspace `hml` (Environment `homologacao`); branch `main` → `prod` (Environment
`producao`, com *required reviewer*). `hml` é menor (`t3.small`, HPA 1→2) e **sobe sob demanda**: só quando há mudança de
infraestrutura ou de schema a ensaiar (security group, `user_data`, parameter group, migration). A pipeline é automática; o controle
de custo é **quando se faz merge em `homolog`**.

## Consequências

- (+) Ensaia o que só uma stack separada ensaia: mudança de Terraform, de `user_data` e de parameter group antes de produção.
- (+) Custo de separação baixo: ≈ US$0,18 por sessão de 4 h.
- (−) `hml` (2 GiB) não comporta o `nri-bundle` completo: roda sem o prometheus-agent; para validar dashboards pode virar `t3.medium` na
  sessão (`instance_type_override`).
- (−) Mais bootstrap por sessão (~13 min, o RDS domina) quando se ensaia antes de promover.
- (−) Dois conjuntos de secrets por Environment e dois states por repositório.

## Alternativas consideradas (recusadas)

1. **Namespace no mesmo cluster:** não ensaia infraestrutura (que é a única razão de existir homologação aqui); o Ingress não define
   `host`, então dois Ingress com `/` no mesmo Traefik colidem; disputa o node único e obrigaria a reduzir o `maxReplicas` de
   produção, degradando a demonstração de escala; mesmo RDS, mesmo teto de conexões e `kubectl get secrets -A` enxergaria os dois
   ambientes.
2. **Só produção:** sem ensaio, mudança de infra iria direto para produção.
3. **`hml` sempre ligado:** custo e esquecimento (o RDS não para com o "End Lab").
