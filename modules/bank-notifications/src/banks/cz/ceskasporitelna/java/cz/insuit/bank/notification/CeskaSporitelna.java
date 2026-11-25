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
 * Česká spořitelna's "Přišla platba" and "Odešla platba", from {@code ceskasporitelna@csas.cz}, sent as HTML.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows the wording in
 * <a href="https://github.com/Joey7500/Mobile-Finance-App">Joey7500/Mobile-Finance-App</a> (Apache-2.0), its payment
 * parser's test, copied from real e-mails with every number replaced, and the labels a second project names. No key is
 * pinned: {@link BankNotifications#withDns()} accepts any key DNSSEC proves {@code csas.cz} published.
 *
 * <p>The opening sentence and {@code Směr platby} state the direction twice; both have to agree, as do the account
 * and the amount the sentence names with the lines below it. No transaction id. Card payments and the daily balance
 * are refused: neither has the sentence.
 */
final class CeskaSporitelna implements Bank {

    static final String DOMAIN = "csas.cz";

    /** None yet: no real e-mail has shown the selector. See {@link AirBank#KEY_FINGERPRINTS} for how they are kept. */
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
        Matcher sentence = lines.single(SENTENCE, "opening sentence");
        if (sentence == null) throw new Unparsed("no opening sentence");
        boolean intoAccount = sentence.group(1).equals("na účet");
        Direction direction = sentence.group(3).equals("odešla") ? Direction.OUTGOING : Direction.INCOMING;
        if (intoAccount != (direction == Direction.INCOMING)) {
            throw new Unparsed("'" + sentence.group(1) + "' with '" + sentence.group(3) + "'");
        }

        // The name is the payer's and comes before the symbols, so it cannot join the free text: the line after it is
        // pinned instead, and a line break smuggled into it cannot add a label. The other project's order is not
        // known; its pin mirrors Joey7500's and refuses rather than misreads if it is wrong.
        lines.requireNext(NAME_LINE, TO + ":");
        lines.requireNext(OTHER_NAME_LINE, ACCOUNT + ":");
        Map<String, String> fields = lines.labelled(LABELLED, Set.of(MESSAGE), Set.of());
        String stated = fields.get(DIRECTION);
        if (stated == null) throw new Unparsed("no Směr platby");
        if (!stated.equals(direction == Direction.INCOMING ? "příchozí" : "odchozí")) {
            throw new Unparsed("the sentence and Směr platby disagree on the direction");
        }

        String account = sentence.group(2);
        String ownLine = firstOf(fields, direction == Direction.INCOMING ? TO : FROM, ACCOUNT);
        if (ownLine != null && !ownLine.equals(account)) throw new Unparsed("the account below differs");
        Money amount = money(sentence.group(4));
        if (amount == null) throw new Unparsed("amount not in the known format: " + sentence.group(4));
        String amountLine = fields.get(AMOUNT);
        if (amountLine != null && !amount.equals(money(amountLine))) throw new Unparsed("the amount below differs");

        String when = fields.get(DATE_TIME);
        if (when == null) throw new Unparsed("no Datum a čas platby");
        ZonedDateTime at;
        try {
            at = LocalDateTime.parse(when, DATE_TIME_FORMAT).atZone(PRAGUE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("Datum a čas platby not d. M. yyyy HH:mm: " + when);
        }
        String counter = firstOf(fields, direction == Direction.INCOMING ? FROM : TO, COUNTER_ACCOUNT);
        // Only an outgoing payment has been seen with a balance, on the line right after the sentence; a balance line
        // anywhere else is what a payer's line break in the message would make. Worded otherwise, it is left out; the
        // payment still counts. It includes an overdraft, as the bank says.
        Matcher balance = direction == Direction.OUTGOING ? lines.single(BALANCE, "balance") : null;
        if (balance != null) lines.requireNext(SENTENCE, BALANCE_START);
        Money available = balance == null ? null : money(balance.group(1));
        try {
            return new Transaction(
                    DOMAIN,
                    direction,
                    Transaction.Type.TRANSFER,
                    BankAccount.parse(account, Country.CZ),
                    counter == null ? null : BankAccount.parse(counter, Country.CZ),
                    firstOf(fields, NAME, OTHER_NAME),
                    direction == Direction.INCOMING ? amount : amount.negate(),
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)),
                            PaymentSymbols.checked(CONSTANT_SYMBOL, fields.get(CONSTANT_SYMBOL)),
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL))),
                    fields.get(MESSAGE),
                    at.toLocalDate(),
                    null,
                    available == null ? null : new Balance(available, at),
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    /** The value under either label: the two public sources name some lines differently, and a mix is refused. */
    private static @Nullable String firstOf(Map<String, String> fields, String label, String otherLabel) {
        String value = fields.get(label);
        String other = fields.get(otherLabel);
        if (value != null && other != null) throw new Unparsed("both '" + label + "' and '" + otherLabel + "'");
        return value != null ? value : other;
    }

    private static @Nullable Money money(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) return null;
        return new Money(new BigDecimal(matcher.group(1).replaceAll("[ \\u00A0]", "") + "." + matcher.group(2)), CZK);
    }

    // "na účet … přišla", "z účtu … odešla"; the second project saw "právě dorazila". A space before the full stop is
    // what a line break in the HTML leaves.
    private static final Pattern SENTENCE =
            Pattern.compile("(na účet|z účtu) ((?:\\d{1,6}-)?\\d{2,10}/\\d{4}) (?:právě )?(přišla|dorazila|odešla)"
                    + " platba ve výši (\\S.*? Kč) ?\\.");
    private static final String BALANCE_START = "Na účtu zůstává k dispozici";
    private static final Pattern BALANCE =
            Pattern.compile(BALANCE_START + " (\\S.*? Kč)(?: \\(včetně kontokorentu \\S.*? Kč\\))?\\.");
    // A space or, from the HTML, a no-break space between thousands: "6 500,00 Kč".
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?:[ \\u00A0]\\d{3})*),(\\d{2}) Kč");
    private static final Currency CZK = Currency.getInstance("CZK");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("d. M. uuuu HH:mm");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    private static final String DATE_TIME = "Datum a čas platby";
    private static final String DIRECTION = "Směr platby";
    private static final String FROM = "Z účtu";
    private static final String TO = "Na účet";
    private static final String ACCOUNT = "Číslo účtu";
    private static final String COUNTER_ACCOUNT = "Číslo účtu protistrany";
    private static final String NAME = "Název protiúčtu";
    private static final String OTHER_NAME = "Název protistrany";
    private static final Pattern NAME_LINE = Pattern.compile(NAME + ":.*", Pattern.DOTALL);
    private static final Pattern OTHER_NAME_LINE = Pattern.compile(OTHER_NAME + ":.*", Pattern.DOTALL);
    private static final String AMOUNT = "Částka v měně účtu";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String MESSAGE = "Zpráva pro příjemce";
    // DOTALL: a no-break space or U+2028 in a payer's message stays inside its line, and so in the value.
    private static final Pattern LABELLED = Pattern.compile(
            "("
                    + String.join(
                            "|",
                            DATE_TIME,
                            DIRECTION,
                            COUNTER_ACCOUNT,
                            ACCOUNT,
                            FROM,
                            TO,
                            NAME,
                            OTHER_NAME,
                            "Částka v měně transakce",
                            AMOUNT,
                            VARIABLE_SYMBOL,
                            CONSTANT_SYMBOL,
                            SPECIFIC_SYMBOL,
                            MESSAGE)
                    + "):(.*)",
            Pattern.DOTALL);
}
