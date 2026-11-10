package cz.insuit.bank.notification;

/** What one bank e-mail says: money moved on an account, or the e-mail could not be read. */
public sealed interface Notification permits Transaction, Unparsed {}
