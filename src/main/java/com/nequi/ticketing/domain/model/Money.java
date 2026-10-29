package com.nequi.ticketing.domain.model;

import com.nequi.ticketing.domain.exception.DomainValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null || currency == null) {
            throw new DomainValidationException("Money requires an amount and a currency");
        }
        if (amount.signum() < 0) {
            throw new DomainValidationException("Money amount must not be negative");
        }
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new DomainValidationException("%s allows at most %d decimals"
                    .formatted(currency.getCurrencyCode(), currency.getDefaultFractionDigits()));
        }
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currencyCode) {
        try {
            return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("Invalid money: %s %s".formatted(amount, currencyCode));
        }
    }

    public Money times(int quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }
}
