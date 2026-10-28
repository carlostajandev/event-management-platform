package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesScaleToTheCurrency() {
        assertThat(Money.of("150000", "COP").amount()).isEqualByComparingTo("150000").hasScaleOf(2);
        assertThat(Money.of("10", "JPY").amount()).hasScaleOf(0);
    }

    @Test
    void multipliesWithoutLosingPrecision() {
        assertThat(Money.of("33.33", "USD").times(3)).isEqualTo(Money.of("99.99", "USD"));
        assertThat(Money.of("150000", "COP").times(0).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsInvalidAmounts() {
        assertThatThrownBy(() -> Money.of("-1", "COP")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of("1.005", "USD")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of("abc", "USD")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of("1", "XXXX")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("USD"))).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(DomainValidationException.class);
    }
}
