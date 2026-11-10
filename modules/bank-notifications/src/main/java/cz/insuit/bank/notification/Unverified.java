package cz.insuit.bank.notification;

/**
 * A bank's e-mail without a valid DKIM signature of that bank. Do not trust it: anyone can send one that looks real.
 *
 * @param reason why the signature did not count
 */
public record Unverified(String reason) implements Notification {}
