package cz.insuit.bank.notification;

import java.time.ZonedDateTime;

/**
 * The balance the bank states in a notification, right after the payment.
 *
 * @param available what can be spent, as the bank words it ("dostupný zůstatek"); it may differ from the booked
 *     balance by money that is blocked, such as a card payment not yet settled
 * @param asOf when the bank says it was that, in the bank's time zone (Europe/Prague for a Czech bank), to the minute
 */
public record Balance(Money available, ZonedDateTime asOf) {}
