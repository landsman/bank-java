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
 * comes from the bank.
 *
 * <pre>{@code
 * BankNotifications notifications = BankNotifications.withDns(); // thread-safe, keep one
 * switch (notifications.read(rawMessage)) {
 *     case Transaction t -> ...      // genuine; still compare t.account() with yours and deduplicate on t.transactionId()
 *     case Unverified u -> ...       // no valid signature of the bank: do not trust it
 *     case TemporaryFailure f -> ... // the bank's key could not be looked up: try again later
 *     case Unparsed u -> ...         // keep the message: a bank changed its template, or it is not a bank's
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
     * Reads one raw RFC 5322 message, exactly as received.
     *
     * @param rawMessage the message bytes, headers included, not re-serialised by a mail library
     * @return the transaction it reports, or why there is none to trust
     */
    public Notification read(byte[] rawMessage) {
        // Before the parser too: anyone can mail the address these come to.
        if (rawMessage.length > DkimVerifier.MAX_MESSAGE_BYTES) {
            return new Unparsed("message of " + rawMessage.length + " bytes is over the "
                    + DkimVerifier.MAX_MESSAGE_BYTES + " byte limit");
        }
        Notification parsed = parseUnverified(rawMessage);
        if (!(parsed instanceof Transaction transaction)) {
            return parsed;
        }
        return switch (dkim.verify(rawMessage, transaction.signedBy())) {
            case DkimResult.Verified verified -> transaction;
            case DkimResult.Failed failed -> new Unverified(failed.reason());
            case DkimResult.TemporaryFailure temporary -> new TemporaryFailure(temporary.reason());
        };
    }

    /** The parser alone, for its tests: a {@link Transaction} from here is not known to be genuine. */
    static Notification parseUnverified(byte[] rawMessage) {
        try {
            MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(rawMessage));
            String domain = senderDomain(message);
            if (!AirBank.DOMAIN.equals(domain)) {
                return new Unparsed("no parser for sender domain " + domain);
            }
            if (!message.isMimeType("text/plain") || !(message.getContent() instanceof String body)) {
                return new Unparsed("not a text/plain message");
            }
            return AirBank.parse(body);
        } catch (MessagingException | IOException e) {
            return new Unparsed("not a readable e-mail: " + e.getMessage());
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
