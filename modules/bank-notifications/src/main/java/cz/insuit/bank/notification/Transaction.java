package cz.insuit.bank.notification;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Money that arrived on, or left, {@link #account()}.
 *
 * @param signedBy the domain whose DKIM signature makes the message genuine, e.g. {@code airbank.cz}; trust the
 *     transaction only once that signature is verified
 * @param direction whether the money arrived or left
 * @param type how it was paid, {@link Type#GENERAL} when the e-mail does not say
 * @param account the account the e-mail is about, {@code number/bankCode}; compare it with the account you expect,
 *     so that a genuine notification about somebody else's account is not taken for yours
 * @param counterAccount the other side, {@code [prefix-]number/bankCode}: the payer of an incoming payment, the
 *     recipient of an outgoing one
 * @param counterName the other side's account name, if the bank prints it (Air Bank does for incoming only)
 * @param amountMinor the amount in minor units (haléře for CZK), always positive
 * @param currency ISO 4217 code
 * @param variableSymbol the variable symbol, if the payment carried one
 * @param message the payment's message, if the bank prints it
 * @param bookedOn the booking date the bank states
 * @param transactionId the bank's id of the transaction, the key to deduplicate on
 */
public record Transaction(
        String signedBy,
        Direction direction,
        Type type,
        String account,
        String counterAccount,
        @Nullable String counterName,
        long amountMinor,
        String currency,
        @Nullable String variableSymbol,
        @Nullable String message,
        LocalDate bookedOn,
        String transactionId)
        implements Notification {

    /** Whether money arrived on the account or left it. */
    public enum Direction {
        INCOMING,
        OUTGOING
    }

    /** How the money was paid. */
    public enum Type {
        /** A bank transfer, "úhrada" in Czech banking. */
        TRANSFER,
        CARD,
        DIRECT_DEBIT,
        /** The e-mail does not say. */
        GENERAL
    }
}
