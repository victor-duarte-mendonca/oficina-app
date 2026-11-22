# ADR 0020 — Mensageria assíncrona com SQS FIFO e DLQ

- **Status:** Proposto
- **Origem:** [`specs/fase4/00-visao-geral.md`](../../specs/fase4/00-visao-geral.md) §2 (R4, R14)
- **Relação:** substitui parcialmente o [ADR 0008](0008-comunicacao-sincrona-rest.md) (que valia para a Fase 3)

## Contexto

A Saga precisa de comunicação assíncrona entre serviços. O cluster é um k3s de nó único (`t3.medium`) já a ~65% de memória, e o
orçamento do Academy é limitado.

## Decisão

**Amazon SQS FIFO**, uma fila de entrada por serviço mais uma fila de respostas do orquestrador, cada uma com DLQ.
`MessageGroupId = workOrderId` preserva a ordem por OS. REST síncrono permanece para o tráfego cliente → gateway → serviço e para
consultas pontuais. Local/CI: LocalStack ou ElasticMQ via Docker. `traceparent` e `correlationId` viajam como atributos da mensagem.

## Consequências

- (+) Gerenciado, sem pod, sem memória no nó e sem custo ocioso relevante.
- (+) Ordem por OS e deduplicação por `MessageDeduplicationId`.
- (−) Entrega ainda é *at-least-once* na prática: consumidores idempotentes continuam obrigatórios (ADR 0019).
- (−) Vazão por grupo limitada e sem *fan-out* nativo (SNS seria adicionado se surgir um segundo consumidor).
- (−) Dependência de permissão de SQS no `LabRole` do Academy (a validar na F2).

## Alternativas consideradas

- **RabbitMQ no k3s:** melhor experiência local e roteamento rico, mas consome memória do nó único, exige persistência e vira
  ponto único de falha. Seria melhor com cluster maior ou necessidade de roteamento complexo.
- **Kafka:** log de eventos e reprocesso, mas operação pesada demais para este escopo.
