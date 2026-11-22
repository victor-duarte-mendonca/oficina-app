# ADR 0018 — Divisão em três microsserviços, com PostgreSQL e DynamoDB

- **Status:** Proposto
- **Origem:** [`specs/fase4/00-visao-geral.md`](../../specs/fase4/00-visao-geral.md) §2 (R1, R2, R5)

## Contexto

A Fase 4 exige ≥ 3 microsserviços independentes, cada um com repositório, infraestrutura e banco próprios, com pelo menos um
banco SQL e um NoSQL, e proíbe acesso direto ao banco de outro serviço.

## Decisão

| Serviço | Responsabilidade | Banco |
|---|---|---|
| **OS Service** (`oficina-app` refatorado) | clientes, veículos, catálogo, ciclo de vida e histórico da OS, orquestrador da Saga | PostgreSQL (RDS atual) |
| **Billing Service** | orçamento, aprovação, pagamento (Mercado Pago), estorno | PostgreSQL próprio (ACID para dinheiro) |
| **Execution Service** | fila de execução, progresso de diagnóstico e reparo | DynamoDB (documento/chave, sem custo ocioso, sem memória no nó) |

Dados de outro contexto trafegam em mensagens (snapshot dos itens e preços no pedido de orçamento), nunca por consulta ao banco
alheio. O catálogo de peças e serviços permanece no OS Service.

## Consequências

- (+) Fronteiras alinhadas aos contextos de negócio; os dois tipos de banco atendem ao PDF com justificativa técnica.
- (+) DynamoDB não disputa memória com os pods do nó único.
- (−) Um RDS a mais (custo e tempo de sessão); consistência entre serviços passa a ser eventual (ver ADR 0019).
- (−) Três pipelines, três imagens, três conjuntos de manifestos para manter.

## Alternativas consideradas

- **Quarto serviço (catálogo/estoque):** mais fiel a DDD, mas aumenta o risco sem exigência do PDF.
- **MongoDB no cluster:** o nó já opera a ~65% de memória.
- **Billing como *database* separado na instância RDS atual:** mais barato, mas enfraquece "banco e infraestrutura próprios".

Quando outra abordagem seria melhor: se a equipe for de uma pessoa e o prazo apertar, Execução e Billing podem dividir um banco
físico com *databases* e credenciais separados, mantendo a regra de não acesso cruzado.
