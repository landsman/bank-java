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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Fio banka's e-mail for every movement on an account, sent from {@code automat@fio.cz}, in Czech or in English as the
 * account's notification is set.
 *
 * <p>Both languages are one template, written twice: a sentence with the account, the date, the direction and the
 * amount, then labelled lines. Where the subject states the direction, it has to agree with the sentence. Lines are
 * read by their label, because the optional ones appear only when the payment has them.
 *
 * <p>The Czech one is read from real e-mails. The English one, and the Czech subject {@code Fio banka - zmena
 * zustatku}, follow the sanitised samples in <a href="https://github.com/radekhulan/myinvoice">radekhulan/myinvoice</a>
 * (MIT), written after a Fio client's report; a real e-mail has yet to confirm them. Fio's short format ({@code prijem /
 * vydaj na konte}) carries no transaction id and no currency, so it is refused as an unsupported format: without an id,
 * the same e-mail arriving twice would count twice.
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
        Template template = null;
        Direction stated = null;
        for (Template candidate : List.of(CZECH, ENGLISH)) {
            if (candidate.subjects().containsKey(subject)) {
                template = candidate;
                stated = candidate.subjects().get(subject);
            }
        }
        if (template == null) throw new Unparsed("not a template this library knows, subject: " + subject);

        NotificationLines lines = new NotificationLines(body);
        Matcher summary = lines.single(template.summary(), "summary");
        // The counter-party's name is on its line; the instruction id comes right after it.
        lines.requireNext(template.counterPartyLine(), template.label(TRANSACTION_ID) + ":");
        // Past the user symbol and the message only the balance follows.
        Map<String, String> fields = template.fields(lines);

        if (summary == null) throw new Unparsed("no balance-change sentence");
        Direction direction = template.up().equals(summary.group("change")) ? Direction.INCOMING : Direction.OUTGOING;
        if (stated != null && direction != stated) throw new Unparsed("subject and summary disagree on the direction");

        String transactionId = fields.get(TRANSACTION_ID);
        if (transactionId == null || !transactionId.matches("\\d+")) throw new Unparsed("instruction id missing");
        Money amount = template.money(summary.group("amount"));
        if (amount == null) throw new Unparsed("amount not in the known format: " + summary.group("amount"));

        ZonedDateTime at;
        String when = summary.group("date").replace(" ", "") + " " + summary.group("time");
        try {
            at = LocalDateTime.parse(when, template.dateTime()).atZone(PRAGUE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not in the known format: " + when);
        }

        BankAccount counterAccount = null;
        String counterName = null;
        String counterParty = fields.get(COUNTER_PARTY);
        try {
            if (counterParty != null) {
                Matcher counter = COUNTER.matcher(counterParty);
                if (!counter.matches()) throw new Unparsed("counter-party not in the known format");
                counterAccount = BankAccount.parse(counter.group(1), BankAccount.Country.CZ);
                counterName = counter.group(2);
            }
            String balance = fields.get(BALANCE);
            Money available = balance == null ? null : template.money(balance);
            return new Transaction(
                    DOMAIN,
                    direction,
                    type(fields.get(TYPE)),
                    BankAccount.parse(summary.group("account") + "/" + BANK_CODE, BankAccount.Country.CZ),
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
                    available == null ? null : new Balance(available, at),
                    null);
        } catch (IllegalArgumentException e) {
            // An account failing its checksum, or a currency code ISO 4217 does not know: a misread, not a payment.
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    /**
     * The movement types seen so far, in Czech; one not seen yet, the English ones included, is
     * {@link Transaction.Type#GENERAL}, not refused.
     */
    private static Transaction.Type type(@Nullable String type) {
        if (type == null) return Transaction.Type.GENERAL;
        return switch (type) {
            case "Platba kartou" -> Transaction.Type.CARD;
            case "Inkaso" -> Transaction.Type.DIRECT_DEBIT;
            case "Bezhotovostní příjem",
                    "Bezhotovostní platba",
                    "Odchozí platba",
                    "Okamžitá příchozí platba",
                    "Příjem převodem uvnitř banky" -> Transaction.Type.TRANSFER;
            default -> Transaction.Type.GENERAL;
        };
    }

    /**
     * One language of the e-mail: its subjects, the sentence (named groups {@code account}, {@code date}, {@code time},
     * {@code change}, {@code amount}), the word for money in, its labels mapped to the keys below, its money (named
     * groups {@code units}, {@code cents}, {@code currency}) and its date and time.
     */
    private record Template(
            Map<String, @Nullable Direction> subjects,
            Pattern summary,
            String up,
            Map<String, String> labels,
            Pattern moneyFormat,
            DateTimeFormatter dateTime) {

        /** The label this language prints for a key; the first one where it has two. */
        String label(String key) {
            return labels.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(key))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .findFirst()
                    .orElseThrow();
        }

        Pattern counterPartyLine() {
            return Pattern.compile(Pattern.quote(label(COUNTER_PARTY) + ":") + ".*", Pattern.DOTALL);
        }

        /** The labelled lines, keyed by what they mean rather than by this language's word; two words for one key refuse. */
        Map<String, String> fields(NotificationLines lines) {
            Pattern labelled = Pattern.compile(
                    "("
                            + String.join(
                                    "|",
                                    labels.keySet().stream().map(Pattern::quote).toList())
                            + "):(.*)",
                    Pattern.DOTALL);
            Set<String> freeText = Set.copyOf(labels.entrySet().stream()
                    .filter(entry -> FREE_TEXT.contains(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList());
            Set<String> balance = Set.copyOf(labels.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(BALANCE))
                    .map(Map.Entry::getKey)
                    .toList());
            Map<String, String> byKey = new HashMap<>();
            for (Map.Entry<String, String> field :
                    lines.labelled(labelled, freeText, balance).entrySet()) {
                String key = labels.get(field.getKey());
                if (key != null && byKey.put(key, field.getValue()) != null) {
                    throw new Unparsed("two lines for " + key);
                }
            }
            return byKey;
        }

        @Nullable
        Money money(String text) {
            Matcher matcher = moneyFormat.matcher(text);
            if (!matcher.matches()) return null;
            BigDecimal value =
                    new BigDecimal(matcher.group("units").replaceAll("[ ,]", "") + "." + matcher.group("cents"));
            return new Money(value, Currency.getInstance(matcher.group("currency")));
        }
    }

    private static final String TYPE = "type";
    private static final String COUNTER_PARTY = "counter-party";
    private static final String TRANSACTION_ID = "instruction id";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String USER_SYMBOL = "user symbol";
    private static final String MESSAGE = "message";
    private static final String BALANCE = "balance";
    private static final Set<String> FREE_TEXT = Set.of(USER_SYMBOL, MESSAGE);

    private static final Map<String, @Nullable Direction> CZECH_SUBJECTS = new HashMap<>();

    static {
        CZECH_SUBJECTS.put("Fio banka - příjem na účtu", Direction.INCOMING);
        CZECH_SUBJECTS.put("Fio banka - výdaj na účtu", Direction.OUTGOING);
        // The same template under a subject that does not say the direction; the sentence alone does.
        CZECH_SUBJECTS.put("Fio banka - zmena zustatku", null);
    }

    private static final Template CZECH = new Template(
            CZECH_SUBJECTS,
            Pattern.compile("zůstatek účtu (?<account>\\d{1,6}-\\d{2,10}|\\d{2,10}) se"
                    + " (?<date>\\d{1,2}\\. ?\\d{1,2}\\. ?\\d{4}) v (?<time>\\d{1,2}:\\d{2})"
                    + " (?<change>zvýšil|snížil) o (?<amount>.+)\\."),
            "zvýšil",
            Map.of(
                    "Typ pohybu", TYPE,
                    "Protistrana", COUNTER_PARTY,
                    "ID pokynu", TRANSACTION_ID,
                    "Variabilní symbol", VARIABLE_SYMBOL,
                    "Konstantní symbol", CONSTANT_SYMBOL,
                    "Specifický symbol", SPECIFIC_SYMBOL,
                    "Uživatelský symbol", USER_SYMBOL,
                    "Zpráva pro příjemce", MESSAGE,
                    "Aktuální zůstatek", BALANCE),
            // Thousands are separated by a plain space: "93 000,00 CZK".
            Pattern.compile("(?<units>\\d{1,3}(?: \\d{3})*),(?<cents>\\d{2}) (?<currency>[A-Z]{3})"),
            DateTimeFormatter.ofPattern("d.M.uuuu H:mm"));

    private static final Template ENGLISH = new Template(
            Map.of(
                    "Fio banka - new income on account", Direction.INCOMING,
                    "Fio banka - new expense on account", Direction.OUTGOING),
            Pattern.compile("the balance of account (?<account>\\d{1,6}-\\d{2,10}|\\d{2,10}) has"
                    + " (?<change>increased|decreased) by (?<amount>[A-Z]{3} \\S+)"
                    + " on (?<date>\\d{1,2}/\\d{1,2}/\\d{4}) at (?<time>\\d{1,2}:\\d{2})\\."),
            "increased",
            Map.of(
                    "Transaction type", TYPE,
                    "Counterparty", COUNTER_PARTY,
                    "Instruction ID", TRANSACTION_ID,
                    "Variable symbol", VARIABLE_SYMBOL,
                    "Constant symbol", CONSTANT_SYMBOL,
                    "Specific symbol", SPECIFIC_SYMBOL,
                    "User symbol", USER_SYMBOL,
                    "Message for beneficiary", MESSAGE,
                    "Current balance", BALANCE),
            // The currency first, a comma between thousands: "CZK 1,234.75".
            Pattern.compile("(?<currency>[A-Z]{3}) (?<units>\\d{1,3}(?:,\\d{3})*)\\.(?<cents>\\d{2})"),
            // Month first: "10/25/2026 at 10:37".
            DateTimeFormatter.ofPattern("M/d/uuuu H:mm"));

    private static final Set<String> SHORT_FORMAT_SUBJECTS =
            Set.of("Fio banka - prijem na konte", "Fio banka - vydaj na konte");
    private static final Pattern COUNTER = Pattern.compile("(\\S+/\\d{4})(?: \\((.*)\\))?");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    private static final String BANK_CODE = "2010";
}
