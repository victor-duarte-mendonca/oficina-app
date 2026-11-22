# Spec 01 — Microsserviços, fronteiras e propriedade de dados

> Atende **R1** (≥ 3 microsserviços independentes) e **R5** (nenhum serviço acessa o banco de outro).
> ADR de apoio: [0018](../../docs/adr/0018-divisao-em-tres-microsservicos-e-bancos.md).

## 1. Critério de divisão

Um serviço por **capacidade de negócio com ciclo de vida e invariantes próprios** (não por camada técnica):

| Serviço | Capacidade | Invariante que ele protege |
|---|---|---|
| **OS Service** | cadastro (clientes, veículos, catálogo), ciclo de vida e histórico da OS, orquestração da Saga | a OS só muda de status por transições válidas; estoque de peças coerente com as linhas da OS |
| **Billing Service** | orçamento, aprovação, pagamento (Mercado Pago), estorno | um orçamento aprovado gera no máximo **um** pagamento ativo; dinheiro nunca é cobrado/estornado em duplicidade |
| **Execution Service** | fila de execução, acompanhamento de diagnóstico e reparo | uma OS ocupa no máximo um item ativo na fila; só finaliza o que foi iniciado |

## 2. Repositórios e nomes (D4 — confirmar)

| Serviço | Repositório | Pacote base | Observação |
|---|---|---|---|
| OS | `oficina-app` (mantém o nome; documentado como "OS Service") | `br.com.oficina` | renomear custa links, workflows e secrets; o README explica a equivalência |
| Billing | `oficina-billing-service` (novo) | `br.com.oficina.billing` | |
| Execution | `oficina-execution-service` (novo) | `br.com.oficina.execution` | |

Os repositórios de infraestrutura (`oficina-infra-k8s`, `oficina-infra-database`) e o `oficina-auth-lambda` (gateway/authorizer)
permanecem e **recebem módulos novos** (ver [12](12-infra-k8s-mensageria.md)); o cluster é plataforma compartilhada, o que não
contraria R1: cada serviço tem repositório, pipeline, manifestos, banco e filas próprios.

## 3. Matriz de propriedade de dados

| Dado | Dono (escreve) | Quem precisa dele | Como chega (nunca por acesso ao banco) |
|---|---|---|---|
| Cliente, veículo, catálogo de peças/serviços, estoque | OS | Billing (nome, e-mail, itens, preços), Execution (veículo, itens) | **snapshot** dentro do comando (`GenerateBudget`, `EnqueueExecution`) |
| OS e histórico de status | OS | todos (consulta) | eventos de resposta; consulta pública por REST do OS |
| Orçamento, token de aprovação, pagamento, estorno | Billing | OS (resultado) | eventos `Budget*`, `Payment*` |
| Item de fila, andamento, notas técnicas | Execution | OS (resultado e histórico) | eventos `Execution*` |
| Saga (`saga_instance`) | OS | — | privado do orquestrador |

Regra de ouro: **um serviço só lê os próprios dados**. Credenciais de banco/tabela são distintas por serviço; o RDS do Billing
não é alcançável pelo security group do OS e vice-versa (ver [12](12-infra-k8s-mensageria.md) §4).

## 4. Comunicação (resumo; detalhes em [03](03-mensageria-e-contratos.md))

- **Assíncrona (SQS)**: todo o fluxo transacional da Saga (comandos e respostas).
- **Síncrona (REST)**: apenas (a) cliente/funcionário → gateway → serviço; (b) o webhook do Mercado Pago → Billing;
  (c) consulta pública de status no OS. **Nenhuma chamada REST entre serviços no caminho da Saga**: acoplamento temporal e
  falha em cascata são exatamente o que a Saga existe para evitar.

## 5. Autenticação e autorização entre serviços

- Usuário final: o **mesmo JWT RS256** da Fase 3 (emissor único, [ADR 0006](../../docs/adr/0006-emissor-unico-de-jwt.md)); cada
  serviço valida com a chave pública (`mp.jwt.verify.publickey.location`) e aplica o RBAC dos seus endpoints.
- Cabeçalho `X-Gateway-Key` (filtro da Fase 3) replicado em Billing e Execution para tudo que não seja webhook.
- Serviço → serviço: **sem token**; o canal é a fila, protegido por IAM (cada serviço só tem `sqs:SendMessage` nas filas dos
  outros e `sqs:ReceiveMessage/DeleteMessage` na sua). No Academy o `LabRole` é único: a segregação por papel IAM **não é
  possível**; registrar essa limitação no README (segregação por convenção e por rede, não por IAM) — ver [12](12-infra-k8s-mensageria.md) §6.

## 6. Arquitetura interna de cada serviço

Hexagonal como no OS (`domain` sem framework; `application` com ports `in`/`out`; `infrastructure` com adapters). Os adapters novos
são **portas de saída**: `MessagePublisherPort`, `PaymentGatewayPort`, `ExecutionQueueRepositoryPort`. Proporcionalidade (CLAUDE.md
global): Billing e Execution são pequenos; DDD tático só onde há invariante (agregados `Budget`, `Payment`, `ExecutionTask`).

## 7. Critérios de aceite

- [ ] 3 repositórios, cada um com `Dockerfile`, `k8s/`, pipeline e README próprios.
- [ ] Teste de arquitetura (ArchUnit) em cada serviço: domínio sem dependência de framework; nenhum import entre serviços.
- [ ] Nenhum serviço referencia host/credencial do banco de outro (verificável nos manifestos e no Terraform).
- [ ] Matriz do §3 reproduzida no README de arquitetura de cada serviço.

## 8. Fora de escopo

Quarto serviço (catálogo/estoque); service mesh; mTLS entre serviços; renomear `oficina-app`.
