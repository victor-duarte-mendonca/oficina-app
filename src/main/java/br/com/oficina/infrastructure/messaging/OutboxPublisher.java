package br.com.oficina.infrastructure.messaging;

import br.com.oficina.infrastructure.adapters.out.OutboxRepositoryAdapter;
import br.com.oficina.infrastructure.persistence.OutboxMessageEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Publica um lote do outbox numa transação. Ao primeiro erro o lote para: mensagens seguintes da mesma
 * OS não podem passar à frente da que falhou (a ordem por OS é parte do contrato). A falha fica
 * registrada na linha ({@code attempts}, {@code last_error}) e o próximo ciclo tenta de novo.
 */
@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    private final OutboxRepositoryAdapter outbox;
    private final MessageTransport transport;

    public OutboxPublisher(OutboxRepositoryAdapter outbox, MessageTransport transport) {
        this.outbox = outbox;
        this.transport = transport;
    }

    /** @return quantas mensagens foram publicadas neste lote */
    @Transactional
    public int publishBatch(int limit) {
        List<OutboxMessageEntity> pending = outbox.lockPending(limit);
        int published = 0;
        for (OutboxMessageEntity message : pending) {
            try {
                transport.send(message.getDestination(), message.getMessageGroupId(), message.getId(), message.getPayload());
                message.markPublished(OffsetDateTime.now());
                published++;
            } catch (RuntimeException e) {
                message.markFailed(e.getMessage());
                LOG.warnf(e, "Falha ao publicar %s (%s); nova tentativa no próximo ciclo",
                    message.getId(), message.getMessageType());
                break;
            }
        }
        return published;
    }
}
