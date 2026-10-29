# Orçamento AWS Academy

> Regra do [runbook](RUNBOOK.md): conferir o crédito no painel do Academy **ao início e ao fim** de cada sessão e registrar aqui.
> Consumo de uma sessão acima de **US$1,00** = recurso fora do previsto (investigar antes da próxima).
> Abaixo de **US$15** de saldo, `hml` deixa de subir.

Crédito inicial: **~US$48** (painel do Academy fala em US$50), único para o curso.

## Custo de referência (sessão de 4 h, produção inteira)

| Recurso | Preço | 4 h |
|---|---|---|
| EC2 `t3.medium` (k3s) | US$0,0416/h | US$0,166 |
| RDS `db.t4g.micro` | US$0,016/h | US$0,064 |
| Storage RDS gp3 20 GB | US$0,115/GB-mês | US$0,013 |
| IPv4 público / EIP | US$0,005/h | US$0,020 |
| API Gateway HTTP API | US$1,00/milhão de req | < US$0,01 |
| Lambda (invocações da demo) | US$0,20/milhão + GB-s | < US$0,01 |
| ECR + S3 (state) + CloudWatch Logs | — | ~US$0,03 |
| New Relic | free tier 100 GB/mês | US$0,00 |
| **Total** | | **≈ US$0,31** |

`hml`, quando sobe, acrescenta ≈ US$0,18 (t3.small + RDS + IPv4).

## Medições

Preencher a cada sessão. O critério de aceite da E9 exige **ao menos duas** medições.

| Data | Ambientes | Início | Fim | Duração | Saldo inicial (US$) | Saldo final (US$) | Consumo (US$) | Órfãos na varredura | Observações |
|---|---|---|---|---|---|---|---|---|---|
| _a preencher na sessão de validação em produção_ | prod | | | | | | | | |
| | | | | | | | | | |
