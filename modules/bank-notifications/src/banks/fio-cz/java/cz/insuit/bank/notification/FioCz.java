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
 * Fio banka's Czech "příjem / výdaj na účtu" e-mail, sent from {@code automat@fio.cz} for every movement on an account.
 *
 * <p>The direction is stated twice, in the signed subject and in the summary sentence; the two have to agree. Lines are
 * read by their label, because the optional ones appear only when the payment has them. The short template
 * ("prijem na konte", seen in August 2026) carries no transaction id and no currency, so it is {@link Unparsed} rather
 * than read: without an id, the same e-mail arriving twice would count twice.
 */
final class FioCz implements Bank {

    static final String DOMAIN = "fio.cz";

    /**
     * The keys Fio signs with, as {@link cz.insuit.bank.notification.dkim.DkimVerifier#fingerprint} gives them. See
     * {@link AirBank#KEY_FINGERPRINTS} for how they are kept current.
     *
     * <p>{@code 20160512._domainkey.fio.cz}, RSA 2048-bit, read on 2026-11-10 through 1.1.1.1 and 8.8.8.8 alike.
     */
    static final Set<String> KEY_FINGERPRINTS = Set.of("g66SWhDk9AKwpAE7Gli7fGccyAq37YMXhmoTUv5Rx6c=");

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
        if (SHORT_FORMAT_SUBJECTS.contains(subject)) {
            throw new Unparsed(
                    Unparsed.Reason.UNSUPPORTED_FORMAT,
                    "Fio's short notification carries no transaction id: choose \"Fio banka - příjem na účtu\" under"
                            + " Informace v oznámení in the notification's settings");
        }
        Direction stated = subject == null ? null : SUBJECTS.get(subject);
        if (stated == null) throw new Unparsed("not a template this library knows, subject: " + subject);

        NotificationLines lines = new NotificationLines(body);
        Matcher summary = lines.single(SUMMARY, "summary");
        // The counter-party's name is on its line; the instruction id comes right after it.
        lines.requireNext(COUNTER_PARTY_LINE, TRANSACTION_ID + ":");
        // Past the user symbol and the message only the balance follows.
        Map<String, String> fields = lines.labelled(LABELLED, FREE_TEXT, Set.of(BALANCE));

        if (summary == null) throw new Unparsed("no balance-change sentence");
        Direction direction = summary.group(4).equals("zvýšil") ? Direction.INCOMING : Direction.OUTGOING;
        if (direction != stated) throw new Unparsed("subject and summary disagree on the direction");

        String transactionId = fields.get(TRANSACTION_ID);
        if (transactionId == null || !transactionId.matches("\\d+")) throw new Unparsed("ID pokynu missing");
        Money amount = money(summary.group(5));
        if (amount == null) throw new Unparsed("amount not in the known format: " + summary.group(5));

        ZonedDateTime at;
        try {
            at = LocalDateTime.parse(summary.group(2) + " " + summary.group(3), DATE_TIME)
                    .atZone(PRAGUE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not dd.MM.yyyy HH:mm: " + summary.group(2) + " " + summary.group(3));
        }

        BankAccount counterAccount = null;
        String counterName = null;
        String counterParty = fields.get(COUNTER_PARTY);
        try {
            if (counterParty != null) {
                Matcher counter = COUNTER.matcher(counterParty);
                if (!counter.matches()) throw new Unparsed("Protistrana not in the known format");
                counterAccount = BankAccount.parse(counter.group(1));
                counterName = counter.group(2);
            }
            String balance = fields.get(BALANCE);
            Money available = balance == null ? null : money(balance);
            return new Transaction(
                    DOMAIN,
                    direction,
                    type(fields.get(TYPE)),
                    BankAccount.parse(summary.group(1) + "/" + BANK_CODE),
                    counterAccount,
                    counterName,
                    direction == Direction.INCOMING ? amount : amount.negate(),
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)),
                            PaymentSymbols.checked(CONSTANT_SYMBOL, fields.get(CONSTANT_SYMBOL)),
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL))),
                    fields.get(MESSAGE),
                    at.toLocalDate(),
                    transactionId,
                    // A balance in a format not seen yet, an overdraft perhaps, is left out; the payment still counts.
                    available == null ? null : new Balance(available, at));
        } catch (IllegalArgumentException e) {
            // An account failing its checksum, or a currency code ISO 4217 does not know: a misread, not a payment.
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    /** The movement types seen so far; one not seen yet is {@link Transaction.Type#GENERAL}, not refused. */
    private static Transaction.Type type(@Nullable String type) {
        if (type == null) return Transaction.Type.GENERAL;
        return switch (type) {
            case "Platba kartou" -> Transaction.Type.CARD;
            case "Inkaso" -> Transaction.Type.DIRECT_DEBIT;
            case "Bezhotovostní příjem",
                    "Bezhotovostní platba",
                    "Okamžitá příchozí platba",
                    "Příjem převodem uvnitř banky" -> Transaction.Type.TRANSFER;
            default -> Transaction.Type.GENERAL;
        };
    }

    private static @Nullable Money money(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) return null;
        BigDecimal value = new BigDecimal(matcher.group(1).replace(" ", "") + "." + matcher.group(2));
        return new Money(value, Currency.getInstance(matcher.group(3)));
    }

    // Seen on 2026-08-18 for an incoming payment; the outgoing one's subject is not known.
    private static final Set<String> SHORT_FORMAT_SUBJECTS = Set.of("Fio banka - prijem na konte");
    private static final Map<String, Direction> SUBJECTS = Map.of(
            "Fio banka - příjem na účtu", Direction.INCOMING,
            "Fio banka - výdaj na účtu", Direction.OUTGOING);
    private static final Pattern SUMMARY = Pattern.compile(
            "zůstatek účtu (\\d{1,6}-\\d{2,10}|\\d{2,10}) se (\\d{2}\\.\\d{2}\\.\\d{4}) v (\\d{2}:\\d{2}) (zvýšil|snížil)"
                    + " o (.+)\\.");
    private static final Pattern LABELLED = Pattern.compile(
            "(Typ pohybu|Protistrana|ID pokynu|Variabilní symbol|Konstantní symbol|Specifický symbol|Uživatelský symbol"
                    + "|Zpráva pro příjemce|Aktuální zůstatek):(.*)");
    private static final Pattern COUNTER_PARTY_LINE = Pattern.compile("Protistrana:.*");
    private static final Pattern COUNTER = Pattern.compile("(\\S+/\\d{4})(?: \\((.*)\\))?");
    // Thousands are separated by a plain space: "93 000,00 CZK".
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    private static final String BANK_CODE = "2010";

    private static final String TYPE = "Typ pohybu";
    private static final String COUNTER_PARTY = "Protistrana";
    private static final String TRANSACTION_ID = "ID pokynu";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String MESSAGE = "Zpráva pro příjemce";
    private static final String BALANCE = "Aktuální zůstatek";
    private static final Set<String> FREE_TEXT = Set.of("Uživatelský symbol", MESSAGE);
}
