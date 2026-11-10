package cz.insuit.bank.notification;

/**
 * The bank's DKIM key could not be looked up. Keep the message and read it again later; until then, do not trust it.
 *
 * @param reason what failed
 */
public record TemporaryFailure(String reason) implements Notification {}
