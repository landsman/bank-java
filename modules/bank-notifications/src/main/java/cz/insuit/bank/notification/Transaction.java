package cz.insuit.bank.notification;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Money that arrived on, or left, {@link #account()}, read from an e-mail whose DKIM signature verified. A new
 * component may be added in a minor version, so construct these only in tests and read them by accessor rather than
 * by record pattern.
 *
 * @param signedBy the bank's domain whose signature verified, e.g. {@code airbank.cz}
 * @param direction whether the money arrived or left; {@link #amount()} carries it as the sign as well
 * @param type how it was paid, {@link Type#GENERAL} when the e-mail does not say
 * @param account the account the e-mail is about; compare it with the account you expect, so that a genuine
 *     notification about somebody else's account is not taken for yours
 * @param counterAccount the other side: the payer of an incoming payment, the recipient of an outgoing one; {@code null}
 *     when the bank names no account, as for a card payment
 * @param counterName the other side's name, if the bank prints it (Air Bank for incoming only, Fio when it has one)
 * @param amount signed: positive for money in, negative for money out, so amounts add up as they are
 * @param symbols the variable, constant and specific symbol, each {@code null} when absent
 * @param message the payment's message, if the bank prints it
 * @param bookedOn the booking date the bank states
 * @param transactionId the bank's own id of the transaction, the key to deduplicate on, per bank
 * @param balance the account's balance right after the payment, if the bank states it in a form this library reads
 * @param messageId the e-mail's {@code Message-ID}, only when the bank's DKIM signature covers it; unsigned, anyone
 *     holding a copy of the e-mail could change it, so it is {@code null} then. Where the bank gives no transaction
 *     id of its own, this is what tells a second delivery of the same e-mail apart
 */
public record Transaction(
        String signedBy,
        Direction direction,
        Type type,
        BankAccount account,
        @Nullable BankAccount counterAccount,
        @Nullable String counterName,
        Money amount,
        PaymentSymbols symbols,
        @Nullable String message,
        LocalDate bookedOn,
        String transactionId,
        @Nullable Balance balance,
        @Nullable String messageId) {

    /** The same transaction with another {@link #messageId()}; the library sets it once the signature is known. */
    Transaction withMessageId(@Nullable String messageId) {
        return new Transaction(
                signedBy,
                direction,
                type,
                account,
                counterAccount,
                counterName,
                amount,
                symbols,
                message,
                bookedOn,
                transactionId,
                balance,
                messageId);
    }

    /** Whether money arrived on the account. */
    public boolean incoming() {
        return direction == Direction.INCOMING;
    }

    /**
     * Leaves out the other side, the message and the balance, so logging a transaction does not log who paid, what
     * they wrote or how much the account holds.
     */
    @Override
    public String toString() {
        return "Transaction[signedBy=%s, direction=%s, type=%s, account=%s, amount=%s, symbols=%s, bookedOn=%s, transactionId=%s]"
                .formatted(signedBy, direction, type, account, amount, symbols, bookedOn, transactionId);
    }

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
