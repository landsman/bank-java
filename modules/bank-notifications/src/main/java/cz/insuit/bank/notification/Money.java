package cz.insuit.bank.notification;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * An amount in a currency, exact. Written with at least the currency's minor digits and without trailing zeros beyond
 * them, so {@code 1500} and {@code 1500.00} CZK are equal and hash alike. Never rounded. The same shape as
 * {@code moneta-api-java}'s {@code Money}.
 *
 * @param amount signed: positive for money in, negative for money out
 * @param currency the currency
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        BigDecimal stripped = amount.stripTrailingZeros();
        int digits = Math.max(currency.getDefaultFractionDigits(), 0);
        amount = stripped.scale() < digits ? stripped.setScale(digits) : stripped;
    }

    /** The same amount with the opposite sign. */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** {@code 1234.10 CZK}. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
