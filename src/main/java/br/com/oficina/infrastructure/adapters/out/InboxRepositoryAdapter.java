package br.com.oficina.infrastructure.adapters.out;

import br.com.oficina.application.ports.out.InboxPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.util.UUID;

/**
 * Inbox no PostgreSQL. {@code ON CONFLICT DO NOTHING} decide a duplicidade no próprio banco, de forma
 * atômica: duas réplicas recebendo a mesma mensagem ao mesmo tempo, só uma insere.
 */
@ApplicationScoped
public class InboxRepositoryAdapter implements InboxPort {

    private final EntityManager em;

    public InboxRepositoryAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean markProcessed(UUID messageId, String consumer) {
        int inserted = em.createNativeQuery("INSERT INTO inbox (message_id, consumer) VALUES (:id, :consumer) "
                + "ON CONFLICT DO NOTHING")
            .setParameter("id", messageId)
            .setParameter("consumer", consumer)
            .executeUpdate();
        return inserted == 1;
    }
}
