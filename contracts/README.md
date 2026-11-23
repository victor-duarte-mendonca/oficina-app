# Contratos de mensagens da Saga

Especificação: [`specs/fase4/03-mensageria-e-contratos.md`](../specs/fase4/03-mensageria-e-contratos.md).

| Arquivo | Produtor | Conteúdo |
|---|---|---|
| `envelope.schema.json` | todos | cabeçalho comum (`messageId`, `type`, `version`, `kind`, `sagaId`, `correlationId`, `traceparent`, ...) e tipos compartilhados |
| `os-commands.schema.json` | OS Service | comandos da Saga e o informativo `DiagnosisStarted` |
| `billing-events.schema.json` | Billing Service | respostas de orçamento, pagamento e estorno |
| `execution-events.schema.json` | Execution Service | respostas da fila de execução e o informativo `ExecutionProgress` |
| `examples/` | — | uma mensagem válida por tipo de evento e casos inválidos com o motivo |

Regras:

- **Valores monetários** são strings com duas casas (`"450.00"`), nunca número em ponto flutuante.
- **Evolução aditiva**: campo novo é opcional; remover ou renomear exige `version` nova e um período em que as duas versões
  convivem. O consumidor ignora campos desconhecidos; o produtor valida as próprias mensagens contra o schema (estrito).
- **Sem dados sensíveis**: nem CPF/CNPJ, nem token de aprovação, nem credenciais. O `examples/invalid-messages.json` tem um
  caso que prova que um campo fora do envelope (`cpf`) é recusado.
- **Dono do schema é o produtor.** Enquanto os repositórios do Billing e do Execution não existem, o catálogo completo mora aqui;
  quando forem criados, cada um passa a versionar o seu arquivo e este repositório mantém uma cópia verificada no CI.

Os testes `ContractSchemaTest` (schemas e exemplos) e `SagaCommandContractTest` (mensagens geradas pelo código) validam este
diretório a cada `mvn verify`.
