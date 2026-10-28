# ADR 0013 — Duplicação deliberada do algoritmo de CPF entre repositórios

- **Status:** Aceito
- **Origem:** [`specs/02-auth-cpf-lambda.md`](../../specs/02-auth-cpf-lambda.md) §5

## Contexto

A aplicação valida CPF/CNPJ em `CpfCnpjValidator`; a função de autenticação precisa da mesma regra de CPF (11 dígitos, rejeita as 10
sequências repetidas, dois dígitos verificadores em módulo 11). Os dois estão em repositórios separados por exigência da fase (R4).

## Decisão

O Lambda tem a **sua própria** classe `Cpf` (value object, validação no construtor), uma reimplementação de ~40 linhas. **Não** se
extrai biblioteca compartilhada. A rede de segurança é o teste: `CpfTest` usa os mesmos CPFs válidos e inválidos dos testes de
`CpfCnpjValidator` da aplicação.

## Consequências

- (+) Nenhum artefato Maven para publicar, versionar e manter: os dois repositórios continuam independentes.
- (+) O Lambda não puxa dependências da aplicação nem do Quarkus (cold start e superfície de CVE).
- (−) **A regra pode divergir** se um lado mudar e o outro não. O algoritmo é estável há décadas, mas a equivalência depende de
  alguém atualizar os casos de teste nos dois lados.
- (−) Uma correção de bug precisa ser feita duas vezes.

## Alternativas consideradas

- **Biblioteca compartilhada publicada (Maven/GitHub Packages):** elimina a duplicação, mas cria acoplamento de versão e de
  pipeline entre repositórios que a fase pede para separar, para 40 linhas.
- **Lambda chamar a aplicação para validar:** acopla a autenticação à disponibilidade do cluster e acrescenta um salto de rede.
- **Git submodule:** acoplamento de versão sem a ergonomia de um pacote.

Quando outra abordagem seria melhor: se a regra passasse a mudar com frequência ou a ter mais consumidores, uma biblioteca
versionada passaria a valer o custo.
