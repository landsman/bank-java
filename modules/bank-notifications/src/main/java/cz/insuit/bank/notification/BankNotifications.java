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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a bank's notification e-mail into what it says about money that moved, once its DKIM signature shows it really
 * comes from the bank. Air Bank's and Fio's e-mails so far; any other is {@link BankNotificationException.Unparsed}.
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

    /** Every bank this release reads; adding one is a class in {@code src/banks/<bank>/} and a line here. */
    private static final List<Bank> BANKS = List.of(new AirBank(), new FioCz());

    /**
     * The one to use. Looks each bank's DKIM key up in DNS, accepts it only when the answer is DNSSEC-secure, validated
     * here from the root's trust anchors, and only when it is one of the keys this release pins for that bank.
     */
    public static BankNotifications withDns() {
        DkimVerifier dkim = DkimVerifier.withDns();
        for (Map.Entry<String, Set<String>> bank : pinnedKeys().entrySet()) {
            dkim = dkim.pinnedKeys(bank.getKey(), bank.getValue());
        }
        return new BankNotifications(dkim);
    }

    /**
     * The DKIM keys {@link #withDns()} accepts, per bank domain, as {@link DkimVerifier#fingerprint} gives them: what this
     * release trusts, for an audit or a log line at start-up.
     */
    public static Map<String, Set<String>> pinnedKeys() {
        return BANKS.stream().collect(Collectors.toUnmodifiableMap(Bank::domain, Bank::keyFingerprints));
    }

    /**
     * Takes the keys from your lookup and trusts them as they come: no DNSSEC, no pinning. For tests, which sign with a
     * key of their own, and for a resolver whose answers you vouch for yourself. In production, use {@link #withDns()}.
     *
     * @param keys where the key records come from
     */
    public static BankNotifications trustingKeysFrom(KeyLookup keys) {
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
            // Kept only when the signature covers it; anything unsigned in the headers is the sender's to change.
            case DkimResult.Verified verified ->
                verified.signedHeaders().contains("message-id") ? transaction : transaction.withMessageId(null);
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
            Bank bank = BANKS.stream()
                    .filter(candidate -> candidate.domain().equals(domain))
                    .findFirst()
                    .orElseThrow(() -> new BankNotificationException.Unparsed(
                            BankNotificationException.Unparsed.Reason.NOT_A_BANK_EMAIL,
                            "no parser for sender domain " + domain));
            if (!message.isMimeType("text/plain") || !(message.getContent() instanceof String body)) {
                throw new BankNotificationException.Unparsed("not a text/plain message");
            }
            return bank.parse(message.getSubject(), body).withMessageId(message.getMessageID());
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
