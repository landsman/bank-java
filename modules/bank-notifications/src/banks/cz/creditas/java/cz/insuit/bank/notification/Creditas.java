package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Banka CREDITAS's "Notifikace o změně na účtu", from {@code info@creditas.cz}: a balance-change notice, both
 * directions, card holds included.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows the sanitised bodies in
 * <a href="https://github.com/radekhulan/myinvoice">radekhulan/myinvoice</a> (MIT), its Creditas parser's test. No
 * key is pinned, so {@link BankNotifications#withDns()} refuses every Creditas e-mail as unverified until a real one
 * confirms the template and the key.
 *
 * <p>The sentence and the list state the account and the amount twice; both have to agree. A card hold
 * ({@code Blokace}) is not a booked payment and is refused: read as one, the payment would count twice once booked.
 * No transaction id, no counter-party name, no message.
 */
final class Creditas implements Bank {

    static final String DOMAIN = "creditas.cz";

    /** None yet: no real e-mail has shown Creditas's selector. See {@link AirBank#KEY_FINGERPRINTS} for how they are kept. */
    static final Set<String> KEY_FINGERPRINTS = Set.of();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public Set<String> keyFingerprints() {
        return KEY_FINGERPRINTS;
    }

    @Override
    public Transaction parse(@Nullable String subject, String body) {
        NotificationLines lines = new NotificationLines(body);
        Matcher summary = lines.single(SUMMARY, "summary");
        if (summary == null) throw new Unparsed("no balance-change sentence");
        String kind = summary.group(4);
        if (kind.equals("Blokace")) throw new Unparsed("a card hold, not a booked payment");
        Direction direction = summary.group(2).equals("zvýšil") ? Direction.INCOMING : Direction.OUTGOING;
        if (kind.startsWith("Příchozí") && direction != Direction.INCOMING) {
            throw new Unparsed("'" + kind + "' on a balance that went down");
        }

        Map<String, String> fields = lines.listed(LABELS);
        if (!summary.group(1).equals(fields.get(ACCOUNT))) throw new Unparsed("the account in the list differs");
        if (!summary.group(3).equals(fields.get(AMOUNT))) throw new Unparsed("the amount in the list differs");
        Money amount = money(summary.group(3));
        Money available = money(summary.group(6));
        if (amount == null) throw new Unparsed("amount not in the known format: " + summary.group(3));

        ZonedDateTime at;
        try {
            at = LocalDateTime.parse(summary.group(5), DATE_TIME).atZone(PRAGUE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not dd.MM.yyyy HH:mm: " + summary.group(5));
        }
        String counterNumber = fields.get(COUNTER_ACCOUNT);
        String counterBank = fields.get(COUNTER_BANK);
        try {
            return new Transaction(
                    DOMAIN,
                    direction,
                    kind.equals("Příchozí úhrada") ? Transaction.Type.TRANSFER : Transaction.Type.GENERAL,
                    BankAccount.parse(summary.group(1), Country.CZ),
                    counterNumber == null || counterBank == null
                            ? null
                            : BankAccount.parse(counterNumber + "/" + counterBank, Country.CZ),
                    null,
                    direction == Direction.INCOMING ? amount : amount.negate(),
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)), null, null),
                    null,
                    at.toLocalDate(),
                    null,
                    available == null ? null : new Balance(available, at),
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static @Nullable Money money(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) return null;
        return new Money(
                new BigDecimal(matcher.group(1).replace(" ", "") + "." + matcher.group(2)),
                Currency.getInstance(matcher.group(3)));
    }

    private static final Pattern SUMMARY = Pattern.compile(
            "zůstatek na účtu (\\S+/2250) se (zvýšil|snížil) o částku (\\S.*? [A-Z]{3}) \\(([^)]+)\\)\\."
                    + " Disponibilní zůstatek (\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}) je (\\S.*? [A-Z]{3})\\.");
    // Thousands are separated by a space in the samples: "23 000,00 CZK".
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    private static final String ACCOUNT = "změna na účtu";
    private static final String COUNTER_ACCOUNT = "účet protistrany";
    private static final String COUNTER_BANK = "banka protistrany";
    private static final String AMOUNT = "částka";
    private static final String VARIABLE_SYMBOL = "VS";
    private static final Set<String> LABELS =
            Set.of(ACCOUNT, COUNTER_ACCOUNT, COUNTER_BANK, "datum", AMOUNT, VARIABLE_SYMBOL, "disponibilní zůstatek");
}
