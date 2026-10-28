# ADRs — Architecture Decision Records

Formato MADR enxuto: **Contexto · Decisão · Consequências (positivas e negativas) · Alternativas consideradas · Status**.
A numeração é sequencial e imutável: um ADR superado é marcado `Superseded by NNNN`, nunca editado no lugar.

> **Sobre as datas.** Estes ADRs formalizam decisões tomadas e registradas nas specs da Fase 3 (`specs/00` a `specs/08`) e
> no código da Fase 2. O campo *Origem* aponta onde a decisão foi tomada; o ADR foi consolidado na etapa E8. Onde a
> implementação divergiu da spec (ex.: ADR 0010 e 0011), o ADR descreve **o que foi implementado** e diz onde divergiu.

| # | Título | Status |
|---|---|---|
| [0001](0001-arquitetura-hexagonal.md) | Arquitetura Hexagonal como estratégia de isolamento do domínio | Aceito |
| [0002](0002-k3s-em-ec2-unica.md) | k3s em EC2 única em vez de EKS gerenciado | Aceito |
| [0003](0003-api-gateway-http-api.md) | AWS API Gateway HTTP API como gateway de borda | Aceito |
| [0004](0004-autenticacao-por-cpf.md) | Autenticação de cliente por CPF, sem senha, com JWT de curta duração | Aceito |
| [0005](0005-lambda-na-vpc-somente-leitura.md) | Lambda dentro da VPC com acesso somente-leitura ao banco | Aceito |
| [0006](0006-emissor-unico-de-jwt.md) | Emissor único de JWT: mesmo par de chaves para aplicação e Lambda | Aceito |
| [0007](0007-autorizacao-em-duas-camadas.md) | Autorização em duas camadas (Lambda authorizer + RBAC na aplicação) | Aceito |
| [0008](0008-comunicacao-sincrona-rest.md) | Comunicação síncrona REST; sem mensageria nesta fase | Aceito |
| [0009](0009-hpa-somente-cpu.md) | HPA por CPU apenas, sem métrica de memória | Aceito |
| [0010](0010-observabilidade-otlp-new-relic.md) | Observabilidade por OTLP com New Relic como backend intercambiável | Aceito |
| [0011](0011-contrato-por-ssm.md) | Contrato entre repositórios por SSM Parameter Store | Aceito |
| [0012](0012-segredos-em-variavel-de-ambiente-do-lambda.md) | Segredos em variável de ambiente do Lambda em vez de Secrets Manager | Aceito |
| [0013](0013-duplicacao-do-algoritmo-de-cpf.md) | Duplicação deliberada do algoritmo de CPF entre repositórios | Aceito |
| [0014](0014-migrations-pertencem-a-aplicacao.md) | Migrations de schema pertencem à aplicação, não à infraestrutura | Aceito |
| [0015](0015-homologacao-como-stack-separada.md) | Homologação como stack separada por workspace, subindo sob demanda | Aceito |
| [0016](0016-java-21-snapstart.md) | Java 21 + SnapStart como runtime da Function Serverless | Aceito |
| [0017](0017-repositorios-publicos.md) | Repositórios públicos sob organização GitHub gratuita | Aceito |

RFCs (problema, espaço de soluções e recomendação): [`../rfc/`](../rfc/). Diagramas: [`../arquitetura.md`](../arquitetura.md).
