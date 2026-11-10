package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * MONETA Money Bank's Info Servis e-mail, "Přišly peníze" / "Odešly peníze", from {@code infoservis@moneta.cz}.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows the sanitised bodies in
 * <a href="https://github.com/radekhulan/myinvoice">radekhulan/myinvoice</a> (MIT), its MONETA parser's test. No
 * key is pinned, so {@link BankNotifications#withDns()} refuses every MONETA e-mail as unverified, naming the key DNS
 * gave, until a real one confirms the template and the key.
 *
 * <p>What the e-mail does not carry, and the transaction leaves out: the receiving account, which MONETA masks
 * ({@code 238***891}), so {@link Transaction#account()} is {@code null}; a transaction id, so
 * {@link Transaction#transactionId()} is {@code null}; and the balance's time, so there is no balance. The variable
 * symbol is taken from {@code Popis}, which may hold the payer's own text, so a match must check the amount too.
 */
final class Moneta implements Bank {

    static final String DOMAIN = "moneta.cz";

    /** None yet: no real e-mail has shown MONETA's selector. See {@link AirBank#KEY_FINGERPRINTS} for how they are kept. */
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
        Direction stated = subject == null ? null : HEADINGS.get(subject);
        if (stated == null) throw new Unparsed("not a template this library knows, subject: " + subject);

        NotificationLines lines = new NotificationLines(body);
        Matcher heading = lines.single(HEADING, "heading");
        if (heading == null || HEADINGS.get(heading.group()) != stated) {
            throw new Unparsed("subject and heading disagree on the direction");
        }
        // Past the description only the counter-account and the balances follow.
        Map<String, String> fields =
                lines.stacked(LABELS, Set.of(DESCRIPTION), Set.of(COUNTER_ACCOUNT, AVAILABLE, BOOKED));
        if (!fields.containsKey(ACCOUNT)) throw new Unparsed("no Účet");

        String amountText = fields.get(AMOUNT);
        Matcher amount = amountText == null ? null : MONEY.matcher(amountText);
        if (amount == null || !amount.matches()) throw new Unparsed("amount missing or not in the known format");
        String date = fields.get(DATE);
        if (date == null) throw new Unparsed("no Datum");
        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(date, DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new Unparsed("Datum not dd.MM.yyyy: " + date);
        }

        String description = fields.get(DESCRIPTION);
        String variableSymbol = null;
        if (description != null) {
            Matcher symbol = VARIABLE_SYMBOL.matcher(description);
            if (symbol.find()) {
                variableSymbol = symbol.group(1);
                // A second one can only be the payer's text; neither is then the bank's.
                if (symbol.find()) throw new Unparsed("two variable symbols in Popis");
            }
        }
        try {
            String counter = fields.get(COUNTER_ACCOUNT);
            Money value = new Money(
                    new BigDecimal(amount.group(1).replace(" ", "") + "." + amount.group(2)),
                    Currency.getInstance("CZK"));
            return new Transaction(
                    DOMAIN,
                    stated,
                    Transaction.Type.GENERAL,
                    null,
                    counter == null ? null : BankAccount.parse(counter),
                    null,
                    stated == Direction.INCOMING ? value : value.negate(),
                    new PaymentSymbols(variableSymbol, null, null),
                    description,
                    bookedOn,
                    null,
                    null,
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static final Map<String, Direction> HEADINGS =
            Map.of("Přišly peníze", Direction.INCOMING, "Odešly peníze", Direction.OUTGOING);
    private static final Pattern HEADING = Pattern.compile("Přišly peníze|Odešly peníze");
    // Only Kč seen; another currency is refused rather than guessed.
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?: \\d{3})*),(\\d{2}) Kč");
    private static final Pattern VARIABLE_SYMBOL = Pattern.compile("\\bVS: ?(\\d{1,10})\\b");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private static final String ACCOUNT = "Účet";
    private static final String DATE = "Datum";
    private static final String AMOUNT = "Částka";
    private static final String DESCRIPTION = "Popis";
    private static final String COUNTER_ACCOUNT = "Od";
    private static final String AVAILABLE = "Disponibilní zůstatek";
    private static final String BOOKED = "Účetní zůstatek";
    private static final Set<String> LABELS =
            Set.of(ACCOUNT, DATE, AMOUNT, DESCRIPTION, COUNTER_ACCOUNT, AVAILABLE, BOOKED);
}
