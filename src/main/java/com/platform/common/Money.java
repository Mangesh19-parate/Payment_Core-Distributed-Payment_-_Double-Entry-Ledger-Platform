package com.platform.common;

import java.util.Objects;

/**
 * Immutable value object representing monetary values in integer minor units (e.g. paise).
 * Floating-point representation is strictly forbidden.
 */
public record Money(long amount, String currency) {

    public static final String DEFAULT_CURRENCY = "INR";
    public static final Money ZERO = new Money(0, DEFAULT_CURRENCY);

    public Money {
        Objects.requireNonNull(currency, "Currency must not be null");
        if (!DEFAULT_CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("Unsupported currency: " + currency + ". Only " + DEFAULT_CURRENCY + " is supported in v1.");
        }
    }

    public static Money ofPaise(long paise) {
        return new Money(paise, DEFAULT_CURRENCY);
    }

    public static Money ofPaise(long paise, String currency) {
        return new Money(paise, currency);
    }

    public Money plus(Money other) {
        validateSameCurrency(other);
        return new Money(Math.addExact(this.amount, other.amount), this.currency);
    }

    public Money minus(Money other) {
        validateSameCurrency(other);
        return new Money(Math.subtractExact(this.amount, other.amount), this.currency);
    }

    public boolean isGreaterThan(Money other) {
        validateSameCurrency(other);
        return this.amount > other.amount;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        validateSameCurrency(other);
        return this.amount >= other.amount;
    }

    public boolean isPositive() {
        return this.amount > 0;
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    private void validateSameCurrency(Money other) {
        Objects.requireNonNull(other, "Other Money must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }
}
