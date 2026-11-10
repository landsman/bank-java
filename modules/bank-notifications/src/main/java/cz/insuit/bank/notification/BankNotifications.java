package cz.insuit.bank.notification;

import cz.insuit.bank.notification.dkim.DkimResult;
import cz.insuit.bank.notification.dkim.DkimVerifier;
import cz.insuit.bank.notification.dkim.KeyLookup;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Reads a bank's notification e-mail into what it says about money that moved, once its DKIM signature shows it really
 * comes from the bank. Air Bank's e-mails only, so far; any other is {@link BankNotificationException.Unparsed}.
 *
 * <pre>{@code
 * BankNotifications notifications = BankNotifications.withDns(); // thread-safe, keep one
 * try {
 *     Transaction t = notifications.read(rawMessage);
 *     // genuine; still compare t.account() with yours and deduplicate on t.transactionId()
 * } catch (BankNotificationException e) {
 *     switch (e) {
 *         case BankNotificationException.Unverified u -> ...       // not signed by the bank: do not trust it
 *         case BankNotificationException.TemporaryFailure f -> ... // the bank's key could not be looked up: later
 *         case BankNotificationException.Unparsed u -> ...         // keep it: another bank, or a changed template
 *     }
 * }
 * }</pre>
 */
public final class BankNotifications {

    /** Looks the banks' DKIM keys up in DNS. */
    public static BankNotifications withDns() {
        return new BankNotifications(DkimVerifier.withDns());
    }

    /**
     * Looks the banks' DKIM keys up with your own lookup: a resolver with its own timeouts, or a recorded key in a test.
     *
     * @param keys where the key records come from
     */
    public static BankNotifications with(KeyLookup keys) {
        return new BankNotifications(DkimVerifier.with(keys));
    }

    /**
     * Reads one raw RFC 5322 message, exactly as received. Throws nothing but {@link BankNotificationException},
     * whatever the bytes.
     *
     * @param rawMessage the message bytes, headers included, not re-serialised by a mail library
     * @return the transaction it reports, its DKIM signature verified
     * @throws BankNotificationException when there is no transaction to trust, the subtype says why
     */
    public Transaction read(byte[] rawMessage) {
        // Before the parser too: anyone can mail the address these come to.
        if (rawMessage.length > DkimVerifier.MAX_MESSAGE_BYTES) {
            throw new BankNotificationException.Unparsed("message of " + rawMessage.length + " bytes is over the "
                    + DkimVerifier.MAX_MESSAGE_BYTES + " byte limit");
        }
        Transaction transaction = parseUnverified(rawMessage);
        DkimResult dkim;
        try {
            dkim = this.dkim.verify(rawMessage, transaction.signedBy());
        } catch (RuntimeException e) {
            throw new BankNotificationException.Unverified("signature could not be checked: " + e);
        }
        return switch (dkim) {
            case DkimResult.Verified verified -> transaction;
            case DkimResult.Failed failed -> throw new BankNotificationException.Unverified(failed.reason());
            case DkimResult.TemporaryFailure temporary ->
                throw new BankNotificationException.TemporaryFailure(temporary.reason());
        };
    }

    /** The parser alone, for its tests: a {@link Transaction} from here is not known to be genuine. */
    static Transaction parseUnverified(byte[] rawMessage) {
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(rawMessage));
            String domain = senderDomain(message);
            if (!AirBank.DOMAIN.equals(domain)) {
                throw new BankNotificationException.Unparsed("no parser for sender domain " + domain);
            }
            if (!message.isMimeType("text/plain") || !(message.getContent() instanceof String body)) {
                throw new BankNotificationException.Unparsed("not a text/plain message");
            }
            return AirBank.parse(body);
        } catch (BankNotificationException e) {
            throw e;
        } catch (MessagingException | IOException | RuntimeException e) {
            // Whatever the mail library throws on bytes it cannot make sense of is an unreadable e-mail, not a crash.
            throw new BankNotificationException.Unparsed("not a readable e-mail: " + e, e);
        }
    }

    private final DkimVerifier dkim;

    private BankNotifications(DkimVerifier dkim) {
        this.dkim = dkim;
    }

    private static final Session SESSION = Session.getInstance(new Properties());

    private static String senderDomain(MimeMessage message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress address)) {
            return "";
        }
        String email = address.getAddress();
        return email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }
}
