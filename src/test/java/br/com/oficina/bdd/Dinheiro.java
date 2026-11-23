package br.com.oficina.bdd;

import io.cucumber.java.ParameterType;
import java.math.BigDecimal;

/** Valores em reais como o negócio escreve ({@code R$ 1.329,80}) e o contrato de mensagens os transporta ({@code "1329.80"}). */
public class Dinheiro {

    @ParameterType("\\d{1,3}(?:\\.\\d{3})*,\\d{2}")
    public BigDecimal dinheiro(String value) {
        return new BigDecimal(value.replace(".", "").replace(',', '.'));
    }

    static BigDecimal doContrato(String value) {
        return new BigDecimal(value);
    }
}
