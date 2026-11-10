package cz.insuit.bank.notification;

/** What one bank e-mail says: money moved on an account, or why there is nothing to trust. */
public sealed interface Notification permits Transaction, Unverified, TemporaryFailure, Unparsed {}
