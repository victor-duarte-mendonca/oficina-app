package br.com.oficina.testsupport;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPFs e placas válidos e únicos por execução, para testes de integração que criam dados pela API
 * (a validação de DTO recusa documento com dígito verificador errado).
 */
public final class TestDocuments {

    private static final AtomicInteger PLATES = new AtomicInteger(ThreadLocalRandom.current().nextInt(1000));

    private TestDocuments() {
    }

    public static String cpf() {
        int[] digits = new int[11];
        for (int i = 0; i < 9; i++) {
            digits[i] = ThreadLocalRandom.current().nextInt(10);
        }
        digits[0] = digits[0] == digits[1] ? (digits[1] + 1) % 10 : digits[0]; // evita sequência repetida
        digits[9] = checkDigit(digits, 9);
        digits[10] = checkDigit(digits, 10);
        StringBuilder cpf = new StringBuilder();
        for (int digit : digits) {
            cpf.append(digit);
        }
        return cpf.toString();
    }

    /** Placa no padrão Mercosul (ABC1D23). */
    public static String plate() {
        int n = PLATES.incrementAndGet();
        char a = (char) ('A' + (n / 6760) % 26);
        char b = (char) ('A' + (n / 260) % 26);
        char d = (char) ('A' + (n / 10) % 26);
        return "T" + a + b + (n % 10) + d + String.format("%02d", ThreadLocalRandom.current().nextInt(100));
    }

    private static int checkDigit(int[] digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += digits[i] * (length + 1 - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
