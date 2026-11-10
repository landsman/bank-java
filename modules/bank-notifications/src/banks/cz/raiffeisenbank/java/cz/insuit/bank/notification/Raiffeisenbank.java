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
 * Raiffeisenbank's "Pohyb na účtě", from {@code info@rb.cz}, sent as HTML; {@link MailBody} gives one line per cell.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows the sanitised bodies in
 * <a href="https://github.com/radekhulan/myinvoice">radekhulan/myinvoice</a> (MIT), its Raiffeisenbank parser's test,
 * which are text a mail library made of the HTML. No key is pinned, so {@link BankNotifications#withDns()} refuses
 * every Raiffeisenbank e-mail as unverified until a real one confirms the template, its HTML and the key.
 *
 * <p>The direction is stated twice, in the opening sentence and in the amount's sign; both have to agree. No
 * transaction id. An account is followed by its holder's name with nothing between ({@code 123456789/5500Firma}).
 */
final class Raiffeisenbank implements Bank {

    static final String DOMAIN = "rb.cz";

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
        Direction direction = sentence.group(1).equals("příchozí") ? Direction.INCOMING : Direction.OUTGOING;
        boolean card = sentence.group(2).equals("karetní transakce");

        // Past the message or the card's details only the balance follows.
        Map<String, String> fields = lines.stacked(LABELS, Set.of(MESSAGE, DETAILS), Set.of(BALANCE));

        String amountText = fields.get(AMOUNT);
        Matcher amount = amountText == null ? null : MONEY.matcher(amountText);
        if (amount == null || !amount.matches()) throw new Unparsed("amount missing or not in the known format");
        if ((direction == Direction.INCOMING) != amount.group(1).equals("+")) {
            throw new Unparsed("the sentence and the amount's sign disagree on the direction");
        }
        String when = fields.get(DATE_TIME);
        if (when == null) throw new Unparsed("no Datum a čas");
        ZonedDateTime at;
        try {
            at = LocalDateTime.parse(when, DATE_TIME_FORMAT).atZone(PRAGUE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("Datum a čas not dd. MM. yyyy HH:mm: " + when);
        }

        String ownLabel = card ? CARD_ACCOUNT : direction == Direction.INCOMING ? TO : FROM;
        Matcher own = account(fields.get(ownLabel));
        if (own == null) throw new Unparsed("no " + ownLabel);
        Matcher counter = card ? null : account(fields.get(direction == Direction.INCOMING ? FROM : TO));
        String balanceText = fields.get(BALANCE);
        Matcher balance = balanceText == null ? null : MONEY.matcher(balanceText);
        // A balance in a format not seen yet is left out; the payment still counts.
        Money available = balance != null && balance.matches() ? signedMoney(balance) : null;
        try {
            return new Transaction(
                    DOMAIN,
                    direction,
                    card ? Transaction.Type.CARD : Transaction.Type.TRANSFER,
                    BankAccount.parse(own.group(1), BankAccount.Country.CZ),
                    counter == null ? null : BankAccount.parse(counter.group(1), BankAccount.Country.CZ),
                    counter == null || counter.group(2).isBlank()
                            ? null
                            : counter.group(2).strip(),
                    signedMoney(amount),
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)),
                            PaymentSymbols.checked(CONSTANT_SYMBOL, fields.get(CONSTANT_SYMBOL)),
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL))),
                    card ? fields.get(DETAILS) : fields.get(MESSAGE),
                    at.toLocalDate(),
                    null,
                    available == null ? null : new Balance(available, at),
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static @Nullable Matcher account(@Nullable String value) {
        if (value == null) return null;
        Matcher matcher = ACCOUNT_AND_NAME.matcher(value);
        return matcher.matches() ? matcher : null;
    }

    /** A {@link #MONEY} that matched, its sign kept: positive for money in, negative for money out. */
    private static Money signedMoney(Matcher money) {
        BigDecimal value = new BigDecimal(money.group(2).replace(".", "") + "." + money.group(3));
        Money unsigned = new Money(value, Currency.getInstance(money.group(4)));
        return money.group(1).equals("-") ? unsigned.negate() : unsigned;
    }

    private static final Pattern SENTENCE =
            Pattern.compile("na účtě byla provedena následující (příchozí|odchozí) (platba|karetní transakce):");
    // A dot between thousands: "+1.234,56 CZK".
    private static final Pattern MONEY = Pattern.compile("([+-])(\\d{1,3}(?:\\.\\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final Pattern ACCOUNT_AND_NAME = Pattern.compile("((?:\\d{1,6}-)?\\d{2,10}/\\d{4})(.*)");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd. MM. uuuu HH:mm");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    private static final String DATE_TIME = "Datum a čas";
    private static final String TO = "Na účet";
    private static final String FROM = "Z účtu";
    private static final String CARD_ACCOUNT = "Účet";
    private static final String AMOUNT = "Částka v měně účtu";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String MESSAGE = "Zpráva pro příjemce";
    private static final String DETAILS = "Detaily";
    private static final String BALANCE = "Disponibilní zůstatek po pohybu";
    private static final Set<String> LABELS = Set.of(
            DATE_TIME,
            TO,
            FROM,
            CARD_ACCOUNT,
            AMOUNT,
            "Typ pohybu",
            "Kategorie pohybu",
            "Debetní karta",
            "Původní částka a měna",
            "Kurz",
            VARIABLE_SYMBOL,
            CONSTANT_SYMBOL,
            SPECIFIC_SYMBOL,
            MESSAGE,
            DETAILS,
            BALANCE);
}
