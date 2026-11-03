package br.com.domus.platform.commercial.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CnpjTest {

    @Test
    void acceptsKnownValidNumbersAndRejectsWrongCheckDigits() {
        assertThat(Cnpj.isValid("11222333000181")).isTrue();
        assertThat(Cnpj.isValid("11444777000161")).isTrue();
        assertThat(Cnpj.isValid("11222333000182")).isFalse();
        assertThat(Cnpj.isValid("11222333000191")).isFalse();
    }

    @Test
    void rejectsRepeatedDigitsWrongLengthAndNonNumbers() {
        assertThat(Cnpj.isValid("00000000000000")).isFalse();
        assertThat(Cnpj.isValid("11111111111111")).isFalse();
        assertThat(Cnpj.isValid("1122233300018")).isFalse();
        assertThat(Cnpj.isValid("ab222333000181")).isFalse();
        assertThat(Cnpj.isValid(null)).isFalse();
    }

    @Test
    void stripsTheUsualPunctuation() {
        assertThat(Cnpj.digitsOnly("11.222.333/0001-81")).isEqualTo("11222333000181");
        assertThat(Cnpj.isValid(Cnpj.digitsOnly("11.222.333/0001-81"))).isTrue();
    }
}
