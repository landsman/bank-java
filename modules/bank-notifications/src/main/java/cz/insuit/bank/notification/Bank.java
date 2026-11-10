package cz.insuit.bank.notification;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One bank whose notification e-mail the library reads. Each lives in its own folder, {@code src/banks/<bank>/}, and
 * is listed in {@link BankNotifications}; the e-mail's {@code From} domain picks it.
 */
interface Bank {

    /** The domain the e-mail comes from and is DKIM-signed by, e.g. {@code airbank.cz}. */
    String domain();

    /**
     * The fingerprints of the keys the bank signs with, as {@link cz.insuit.bank.notification.dkim.DkimVerifier#fingerprint}
     * gives them: only these are accepted, whatever DNS says. The daily key check compares them with DNS.
     */
    Set<String> keyFingerprints();

    /**
     * Reads the decoded text body into a transaction, or throws {@link BankNotificationException.Unparsed}. Nothing is
     * guessed: a template that does not match exactly is refused.
     *
     * @param subject the decoded subject, signed by the bank's DKIM signature, or {@code null}
     * @param body the decoded {@code text/plain} body
     */
    Transaction parse(@Nullable String subject, String body);
}
