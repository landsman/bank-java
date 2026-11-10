package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Air Bank's "Zvýšení / Snížení zůstatku na účtu" e-mail, sent from {@code info@airbank.cz} when the balance changes
 * and the client asked for it under Účty a karty → Možnosti → Info o dění na účtu.
 *
 * <p>The body states the direction twice, in the summary sentence and in the line naming the other side; the two have
 * to agree, or the template changed and the message is {@link Unparsed} rather than guessed at. Lines are read by their
 * label, because the optional ones appear only when the payment has them.
 */
final class AirBank implements Bank {

    static final String DOMAIN = "airbank.cz";

    /**
     * The keys Air Bank signs with, as {@link cz.insuit.bank.notification.dkim.DkimVerifier#fingerprint} gives them.
     * Only these are accepted, whatever DNS says. The daily key check (.github/workflows/keys.yml) compares them with
     * the DNSSEC-validated key in DNS and opens an issue when Air Bank publishes another one; a new key goes in here
     * beside the old one, and the old one leaves once DNS no longer publishes it.
     *
     * <p>{@code ab._domainkey.airbank.cz}, RSA 1024-bit, read on 2026-11-10 through 1.1.1.1 and 8.8.8.8 alike.
     */
    static final Set<String> KEY_FINGERPRINTS = Set.of("M1mJ01eTgtt/3Jw9syH2YdwTWzPzYeST8Hfk/dfZg6E=");

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public Set<String> keyFingerprints() {
        return KEY_FINGERPRINTS;
    }

    /** The subject carries the account's nickname, the client's own text, so only the body is read. */
    @Override
    public Transaction parse(@Nullable String subject, String body) {
        // The outgoing template ends the transaction code with a U+FEFF.
        NotificationLines lines = new NotificationLines(body, line -> line.replace(ZERO_WIDTH_NO_BREAK_SPACE, ""));
        Matcher summary = lines.single(SUMMARY, "summary");
        Matcher incoming = lines.single(INCOMING, "counter-party");
        Matcher outgoing = lines.single(OUTGOING, "counter-party");
        // The payer's name is on the incoming line; the amount comes right after it in both templates.
        lines.requireNext(INCOMING, AMOUNT + ":");
        lines.requireNext(OUTGOING, AMOUNT + ":");
        // Past the message only the transaction code follows.
        Map<String, String> fields = lines.labelled(LABELLED, MESSAGES, Set.of(TRANSACTION_ID));

        if (summary == null) throw new Unparsed("no balance-change sentence");
        if (incoming == null && outgoing == null) throw new Unparsed("no counter-party line");
        if (incoming != null && outgoing != null) throw new Unparsed("two counter-party lines");
        Direction direction = summary.group(2).equals("zvýšil") ? Direction.INCOMING : Direction.OUTGOING;
        if ((direction == Direction.INCOMING) != (incoming != null)) {
            throw new Unparsed("summary and counter-party line disagree on the direction");
        }

        String amount = fields.get(AMOUNT);
        if (amount == null || !amount.equals(summary.group(3))) {
            throw new Unparsed("amount line missing or different from the summary");
        }
        Matcher money = MONEY.matcher(amount);
        // A thousands separator has not been seen yet; until it is, an amount that does not match is not guessed at.
        if (!money.matches()) throw new Unparsed("amount not in the known format: " + amount);

        String booked = fields.get(BOOKED_ON);
        String transactionId = fields.get(TRANSACTION_ID);
        if (booked == null || transactionId == null || !transactionId.matches("\\d+")) {
            throw new Unparsed("booking date or transaction code missing");
        }
        String variableSymbol = PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL));
        String constantSymbol = PaymentSymbols.checked(CONSTANT_SYMBOL, fields.get(CONSTANT_SYMBOL));
        String specificSymbol = PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL));
        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(booked, DATE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("booking date not d.M.yyyy: " + booked);
        }

        String counterAccount = incoming != null
                ? incoming.group(2)
                : Objects.requireNonNull(outgoing).group(2);
        @Nullable
        String counterName = incoming != null
                ? incoming.group(1)
                : Objects.requireNonNull(outgoing).group(1);
        try {
            BigDecimal value = new BigDecimal(money.group(1).replace(" ", "") + "." + money.group(2));
            Money signed = new Money(value, Currency.getInstance(money.group(3)));
            return new Transaction(
                    DOMAIN,
                    direction,
                    // Both lines this parser reads say "úhrada", a transfer. A card payment or a direct debit, once
                    // seen, is a line of its own.
                    Transaction.Type.TRANSFER,
                    BankAccount.parse(summary.group(1), BankAccount.Country.CZ),
                    BankAccount.parse(counterAccount, BankAccount.Country.CZ),
                    counterName,
                    direction == Direction.INCOMING ? signed : signed.negate(),
                    new PaymentSymbols(variableSymbol, constantSymbol, specificSymbol),
                    // The incoming sample said "pro příjemce", the outgoing one "pro plátce".
                    fields.getOrDefault(MESSAGE_FOR_RECIPIENT, fields.get(MESSAGE_FOR_PAYER)),
                    bookedOn,
                    transactionId,
                    balance(summary.group(0)),
                    null);
        } catch (IllegalArgumentException e) {
            // An account failing its checksum, or a currency code ISO 4217 does not know: a misread, not a payment.
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    // The account nickname is the client's own text and may contain "číslo", hence the greedy ".*" before it.
    private static final Pattern SUMMARY = Pattern.compile(
            "zůstatek na účtu .* číslo (\\S+/3030) se (zvýšil|snížil) o částku (\\d[\\d ]*,\\d{2} [A-Z]{3})\\. .*");
    private static final Pattern INCOMING = Pattern.compile("Příchozí úhrada z účtu (.*) číslo (\\S+/\\d{4})");
    // The recipient's name, when the account holder saved one, as in myinvoice's sample; without one, as in a real
    // e-mail.
    private static final Pattern OUTGOING = Pattern.compile("Odchozí úhrada na účet (?:(.*) )?číslo (\\S+/\\d{4})");
    private static final Pattern LABELLED = Pattern.compile(
            "(Částka|Datum zaúčtování|Variabilní symbol|Konstantní symbol|Specifický symbol"
                    + "|Zpráva pro příjemce|Zpráva pro plátce|Kód transakce): (.*)",
            // A line holds no CR or LF, so DOTALL only lets a message keep a NEL or U+2028 instead of being dropped.
            Pattern.DOTALL);
    private static final Pattern BALANCE = Pattern.compile(
            "\\. Dostupný zůstatek k (\\d{1,2}\\.\\d{1,2}\\.\\d{4} v \\d{1,2}:\\d{2}) je (\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})\\.");
    // A plain space between thousands, as in myinvoice's samples ("9 500,00 CZK"); no real e-mail of 1 000 or more yet.
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final String ZERO_WIDTH_NO_BREAK_SPACE = String.valueOf((char) 0xFEFF);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d.M.uuuu 'v' H:mm");
    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    private static final String AMOUNT = "Částka";
    private static final String BOOKED_ON = "Datum zaúčtování";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String MESSAGE_FOR_RECIPIENT = "Zpráva pro příjemce";
    private static final String MESSAGE_FOR_PAYER = "Zpráva pro plátce";
    private static final Set<String> MESSAGES = Set.of(MESSAGE_FOR_RECIPIENT, MESSAGE_FOR_PAYER);
    private static final String TRANSACTION_ID = "Kód transakce";

    /**
     * The balance in the summary sentence, or {@code null} when it is not in the one format seen so far. How 1 000 and
     * more, or an overdraft, is written has not been seen; a balance is extra information and never a reason to refuse
     * the payment, so an unknown format leaves it out instead of making the message {@link Unparsed}.
     */
    private static @Nullable Balance balance(String summary) {
        Matcher matcher = BALANCE.matcher(summary);
        if (!matcher.find()) return null;
        try {
            Money available = new Money(
                    new BigDecimal(matcher.group(2).replace(" ", "") + "." + matcher.group(3)),
                    Currency.getInstance(matcher.group(4)));
            return new Balance(
                    available, LocalDateTime.parse(matcher.group(1), DATE_TIME).atZone(PRAGUE));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }
}
