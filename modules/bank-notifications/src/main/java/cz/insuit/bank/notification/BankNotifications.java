package cz.insuit.bank.notification;

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
 * Reads a bank's notification e-mail into what it says about money that moved.
 *
 * <p>Anyone can send an e-mail that looks like a bank's. The parser picks the bank by the {@code From} header and
 * does not verify anything; before acting on a {@link Transaction}, check that the raw message carries a valid
 * DKIM signature of {@link Transaction#signedBy()}, verified by your own receiving side.
 */
public final class BankNotifications {

    /**
     * Parses one raw RFC 5322 message, exactly as received.
     *
     * @param rawMessage the message bytes, headers included
     * @return the transaction it reports, or why it could not be read
     */
    public static Notification parse(byte[] rawMessage) {
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

    private static final Session SESSION = Session.getInstance(new Properties());

    private static String senderDomain(MimeMessage message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress address)) {
            return "";
        }
        String email = address.getAddress();
        return email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    private BankNotifications() {}
}
