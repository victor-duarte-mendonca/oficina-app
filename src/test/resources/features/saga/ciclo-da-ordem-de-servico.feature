# language: pt
@saga
Funcionalidade: Ciclo de vida da ordem de serviço entre os microsserviços
  Como oficina mecânica
  Quero que a OS percorra orçamento, aprovação, pagamento e execução de forma coordenada
  Para que nenhuma etapa fique pela metade: ou a OS é concluída, ou tudo o que já foi feito é desfeito

  Contexto:
    Dado uma OS em diagnóstico com um serviço de R$ 150,00 e 2 peças de R$ 89,90 cada, com 10 peças em estoque

  Cenário: S1 — fluxo completo com pagamento aprovado e execução concluída
    Quando o mecânico envia a OS para aprovação
    Então a OS fica "Aguardando Aprovação"
    E o Billing recebe o pedido de orçamento de R$ 329,80
    Quando o Billing gera o orçamento
    E o cliente aprova o orçamento
    Então o Billing recebe a cobrança de R$ 329,80
    Quando o Billing emite o Pix
    Então a OS fica "Aguardando Pagamento"
    Quando o pagamento é aprovado
    Então o Execution recebe a OS para a fila de execução
    Quando o Execution coloca a OS na fila
    Então a OS fica "Aguardando Execução"
    Quando o mecânico inicia a execução
    Então a OS fica "Execução"
    Quando o mecânico conclui a execução
    Então a OS fica "Finalizada"
    E a Saga termina concluída
    E o histórico registra as etapas "Recebida, Diagnóstico, Aguardando Aprovação, Aguardando Pagamento, Aguardando Execução, Execução, Finalizada"

  Cenário: S2 — cliente recusa o orçamento
    Quando o mecânico envia a OS para aprovação
    E o Billing gera o orçamento
    E o cliente recusa o orçamento
    Então o Billing recebe o pedido para anular o orçamento
    Quando o Billing confirma a anulação do orçamento
    Então a OS fica "Cancelada"
    E o estoque volta a ter 10 peças
    E nenhuma cobrança foi solicitada ao cliente

  Cenário: S5 — o Pix é recusado em todas as tentativas
    Dado uma OS com o orçamento aprovado e o Pix emitido
    Quando o pagamento é recusado pelo banco
    Então o Billing recebe uma nova cobrança, tentativa 2
    Quando o Billing emite o Pix
    E o pagamento é recusado pelo banco
    Então o Billing recebe uma nova cobrança, tentativa 3
    Quando o Billing emite o Pix
    E o pagamento é recusado pelo banco
    Então o Billing recebe o pedido para anular o orçamento
    Quando o Billing confirma a anulação do orçamento
    Então a OS fica "Cancelada"
    E o estoque volta a ter 10 peças

  Cenário: S6 — falha na execução depois do pagamento estorna o cliente
    Dado uma OS paga e em execução
    Quando a execução falha por "peça indisponível"
    Então o Billing recebe o pedido de estorno
    E a OS continua "Execução" até o dinheiro ser devolvido
    Quando o Billing confirma o estorno
    Então o Billing recebe o pedido para anular o orçamento
    Quando o Billing confirma a anulação do orçamento
    Então a OS fica "Cancelada"
    E o estoque volta a ter 10 peças
    E o histórico registra o motivo "peça indisponível"

  Cenário: S7 — o estorno é recusado e a Saga pede intervenção da oficina
    Dado uma OS paga e em execução
    Quando a execução falha por "motor danificado"
    E o Billing recusa o estorno 3 vezes por "saldo insuficiente"
    Então a Saga aguarda intervenção da oficina
    E a OS continua "Execução" até o dinheiro ser devolvido
    Quando a oficina pede uma nova tentativa de compensação
    Então o Billing recebe o pedido de estorno
    Quando o Billing confirma o estorno
    E o Billing confirma a anulação do orçamento
    Então a OS fica "Cancelada"
    E a Saga termina cancelada
