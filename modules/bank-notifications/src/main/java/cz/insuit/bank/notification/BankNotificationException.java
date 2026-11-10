package cz.insuit.bank.notification;

import org.jspecify.annotations.Nullable;

/**
 * An e-mail that did not give a transaction to trust, one subtype per thing a caller does about it, so a {@code switch}
 * over them is exhaustive:
 *
 * <pre>{@code
 * try {
 *     Transaction t = notifications.read(rawMessage);
 * } catch (BankNotificationException e) {
 *     switch (e) {
 *         case BankNotificationException.Unverified u -> log.warn("not from the bank: {}", u.getMessage());
 *         case BankNotificationException.TemporaryFailure f -> retryLater(rawMessage);
 *         case BankNotificationException.Unparsed u -> keepForLater(rawMessage, u.reason(), u.getMessage());
 *     }
 * }
 * }</pre>
 *
 * <p>The message is safe to log: it never carries the payer's name, the payment's message or the e-mail's body, and
 * control characters from the e-mail are replaced. The subtypes' constructors are public so a caller's own tests can
 * throw them from a fake; the hierarchy stays closed.
 */
public abstract sealed class BankNotificationException extends RuntimeException {

    BankNotificationException(String message, @Nullable Throwable cause) {
        super(printable(message), cause);
    }

    /**
     * The e-mail looks like a bank's, but carries no valid DKIM signature of that bank. Do not act on it: anyone can send
     * an e-mail that looks real. Retrying will not help.
     */
    public static final class Unverified extends BankNotificationException {
        public Unverified(String message) {
            super(message, null);
        }
    }

    /** The bank's DKIM key could not be looked up. Keep the message and read it again later; until then, do not trust it. */
    public static final class TemporaryFailure extends BankNotificationException {
        public TemporaryFailure(String message) {
            super(message, null);
        }
    }

    /**
     * Not an e-mail the library can read: another bank's, a bank's template that changed, or not a bank's at all. Keep
     * the raw message, so a later release can read it again. {@link #reason()} says which, for a program to act on.
     */
    public static final class Unparsed extends BankNotificationException {
        private final Reason reason;

        /** A message the library could not read, {@link Reason#NOT_READ}. */
        public Unparsed(String message) {
            this(Reason.NOT_READ, message, null);
        }

        public Unparsed(String message, @Nullable Throwable cause) {
            this(Reason.NOT_READ, message, cause);
        }

        public Unparsed(Reason reason, String message) {
            this(reason, message, null);
        }

        private Unparsed(Reason reason, String message, @Nullable Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /** Why, for a program to act on; the message says it for a person. */
        public Reason reason() {
            return reason;
        }

        /** Why an e-mail was not read. A constant may be added in a minor version: keep a {@code default} branch. */
        public enum Reason {
            /** Not from a bank this release reads: another bank, or not a bank at all. Nothing for the account holder to fix. */
            NOT_A_BANK_EMAIL,
            /**
             * A bank's notification in a format the library knows but will not read, such as one without a transaction
             * id. The account holder can fix it in their banking; the message says what to choose instead.
             */
            UNSUPPORTED_FORMAT,
            /** Anything else: a template that changed, a misread value, a message cut short, bytes that are no e-mail. */
            NOT_READ
        }
    }

    private static String printable(String message) {
        return message.replaceAll("\\p{Cntrl}", "?");
    }
}
