package cz.insuit.bank.notification;

import org.jspecify.annotations.Nullable;

/**
 * The Czech payment symbols of a transfer. Leading zeros are kept: a symbol is an identifier, not a number. The same
 * shape as {@code moneta-api-java}'s {@code PaymentSymbols}.
 *
 * @param variable the variable symbol, or {@code null}
 * @param constant the constant symbol, or {@code null}
 * @param specific the specific symbol, or {@code null}
 */
public record PaymentSymbols(
        @Nullable String variable,
        @Nullable String constant,
        @Nullable String specific) {

    /** No symbols at all. */
    public static final PaymentSymbols NONE = new PaymentSymbols(null, null, null);
}
