package br.com.oficina.application.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Leitura tipada e validada do {@code payload} de uma mensagem recebida. Campo obrigatório ausente ou
 * de tipo errado é mensagem malformada: {@link InvalidMessageException}, que leva a mensagem à DLQ
 * depois das reentregas, em vez de mover a Saga com dados inventados.
 */
public final class MessagePayload {

    private final Map<String, Object> values;

    public MessagePayload(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Map.copyOf(withoutNulls(values));
    }

    public String text(String field) {
        Object value = required(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser texto não vazio");
    }

    public int integer(String field) {
        return Math.toIntExact(longInteger(field));
    }

    public long longInteger(String field) {
        Object value = required(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser numérico");
    }

    public boolean flag(String field) {
        Object value = required(field);
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser booleano");
    }

    public Optional<String> optionalText(String field) {
        return Optional.ofNullable(values.get(field)).map(Object::toString);
    }

    private Object required(String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new InvalidMessageException("Campo obrigatório ausente: '" + field + "'");
        }
        return value;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }
}
