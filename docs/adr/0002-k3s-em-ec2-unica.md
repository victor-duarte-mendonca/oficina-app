# ADR 0002 — k3s em EC2 única em vez de EKS gerenciado

- **Status:** Aceito
- **Origem:** [`specs/00-visao-geral.md`](../../specs/00-visao-geral.md) §3 e §5; [`specs/06-infra-k8s-terraform.md`](../../specs/06-infra-k8s-terraform.md)

## Contexto

A fase exige um cluster Kubernetes escalável. O crédito do AWS Academy é de ~US$48, único para o curso, sem recarga, e o
"End Lab" **só para instâncias EC2**: qualquer outro recurso continua existindo e cobrando.

## Decisão

Manter **k3s single-node em uma EC2** (`t3.medium` em produção, `t3.small` em homologação), instalado pelo `user_data` e
acessível pela API (6443) **somente por túnel SSM**, nunca exposta. A escalabilidade é horizontal de pods (HPA, ADR 0009)
dentro do node.

## Consequências

- (+) Custo de ~US$0,04/h (EC2) e **para com o "End Lab"**; o control plane do EKS custa US$0,10/h (~US$73/mês) e não para.
- (+) O que a avaliação pede (Deployment, HPA, Ingress, probes, NetworkPolicy) é Kubernetes real e idêntico ao do EKS.
- (−) **Sem alta disponibilidade**: um node, um ponto de falha; atualização do k3s e do node derrubam o serviço.
- (−) Escala de pods limitada pela memória do node (4 GiB em prod): o teto do HPA é 4 réplicas e a observabilidade (ADR 0010)
  disputa o mesmo node. Em `hml` (2 GiB) os agentes rodam reduzidos.
- (−) Cluster descartável: o estado do etcd e os Secrets nascem a cada sessão (o deploy recria os Secrets).

## Alternativas consideradas

- **EKS gerenciado:** padrão de mercado, mas US$73/mês fixos consumiriam o orçamento em ~20 dias e não param com o "End Lab".
- **Kubernetes autogerenciado (kubeadm) em várias EC2:** mais próximo de produção real, porém multiplica custo e tempo de
  bootstrap por sessão sem requisito que o justifique.
- **ECS/Fargate:** não é Kubernetes; a fase pede cluster K8s.

Quando outra abordagem seria melhor: com orçamento de produção e requisito de disponibilidade, EKS em múltiplas AZs.
