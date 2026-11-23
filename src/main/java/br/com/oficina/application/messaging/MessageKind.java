package br.com.oficina.application.messaging;

/** Natureza da mensagem no envelope (spec 03 §2). {@code INFO} informa, mas não move a Saga. */
public enum MessageKind { COMMAND, EVENT, INFO }
