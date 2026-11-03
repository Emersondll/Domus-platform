package br.com.domus.platform.commercial.service;

/** Conferência do CNPJ numérico (14 dígitos, dois dígitos verificadores pelo módulo 11). */
final class Cnpj {

    private static final int[] FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpj() {
    }

    /** Remove pontos, barra, hífen e espaços. */
    static String digitsOnly(String value) {
        return value.replaceAll("[.\\-/\\s]", "");
    }

    /** Diz se são 14 dígitos com os verificadores corretos (e não uma sequência repetida, como 00000000000000). */
    static boolean isValid(String digits) {
        if (digits == null || !digits.matches("\\d{14}") || digits.chars().distinct().count() == 1) {
            return false;
        }
        int d1 = check(digits, FIRST);
        int d2 = check(digits, SECOND);
        return d1 == digits.charAt(12) - '0' && d2 == digits.charAt(13) - '0';
    }

    private static int check(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
