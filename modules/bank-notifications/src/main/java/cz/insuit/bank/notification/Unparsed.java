package cz.insuit.bank.notification;

/**
 * An e-mail that could not be read. Keep the raw message: a bank that changed its template shows up here first.
 *
 * @param reason what did not match
 */
public record Unparsed(String reason) implements Notification {}
