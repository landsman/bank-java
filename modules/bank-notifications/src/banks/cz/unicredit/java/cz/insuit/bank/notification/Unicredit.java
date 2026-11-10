package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * UniCredit Bank's "Informace o pohybu na účtu", from {@code unicreditbank@unicreditgroup.cz}, sent as HTML with
 * {@code <br/>} between the lines.
 *
 * <p><b>Read from one public sample, not from a real e-mail yet</b>: the template follows the sanitised body in
 * <a href="https://github.com/radekhulan/myinvoice">radekhulan/myinvoice</a> (MIT), its UniCredit parser's test. No
 * key is pinned, so {@link BankNotifications#withDns()} refuses every UniCredit e-mail as unverified.
 *
 * <p><b>The direction is the amount's sign</b>, as in that parser: none is money in, a minus is money out. The sample
 * is an incoming payment, so whether an outgoing one really carries a minus is not known. A refund to a customer has
 * the invoice's amount and symbol; read as incoming, it would pay that invoice again. That is the first thing a real
 * outgoing e-mail has to confirm, before any key is pinned.
 *
 * <p>The account in the sentence has no bank code, so {@code /2700} is added; its currency, in parentheses, has to be
 * the amount's. Amounts are written the English way ({@code 20,546.00 CZK}). No transaction id, no balance.
 */
final class Unicredit implements Bank {

    static final String DOMAIN = "unicreditgroup.cz";

    /**
     * None yet: no real e-mail has shown the selector. Before pinning one, confirm with a real outgoing e-mail how its
     * direction is written (see the class comment). See {@link AirBank#KEY_FINGERPRINTS} for how keys are kept.
     */
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
        // The template prints every label, an empty one too ("Specifický symbol: "), so a payment without a variable
        // symbol still has its line, and a forged one, smuggled in through the counter-party's name, makes two.
        if (lines.single(SYMBOL_LINE, "variable symbol") == null) throw new Unparsed("no Variabilní symbol line");
        // The counter-party's name is text its holder chose; the variable symbol comes right after it.
        lines.requireNext(NAME_LINE, VARIABLE_SYMBOL + ":");
        // Past the details only the date follows.
        Map<String, String> fields = lines.labelled(LABELLED, Set.of(DETAILS), Set.of(DATE));

        String amountText = fields.get(AMOUNT);
        Matcher amount = amountText == null ? null : MONEY.matcher(amountText);
        if (amount == null || !amount.matches()) throw new Unparsed("amount missing or not in the known format");
        if (!amount.group(4).equals(sentence.group(2))) {
            throw new Unparsed("the amount's currency is not the account's");
        }
        Direction direction = amount.group(1).equals("-") ? Direction.OUTGOING : Direction.INCOMING;

        String date = fields.get(DATE);
        if (date == null) throw new Unparsed("no Datum");
        LocalDateTime at;
        try {
            at = LocalDateTime.parse(date, DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new Unparsed("Datum not dd.MM.yyyy HH:mm:ss: " + date);
        }
        String counter = fields.get(COUNTER_ACCOUNT);
        try {
            Money value = new Money(
                    new BigDecimal(amount.group(2).replace(",", "") + "." + amount.group(3)),
                    Currency.getInstance(amount.group(4)));
            return new Transaction(
                    DOMAIN,
                    direction,
                    Transaction.Type.GENERAL,
                    BankAccount.parse(sentence.group(1) + "/" + BANK_CODE, BankAccount.Country.CZ),
                    counter == null ? null : BankAccount.parse(counter, BankAccount.Country.CZ),
                    fields.get(COUNTER_NAME),
                    direction == Direction.INCOMING ? value : value.negate(),
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)),
                            null,
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL))),
                    fields.get(DETAILS),
                    at.toLocalDate(),
                    null,
                    null,
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    // The account's nickname, the client's own text, sits between the currency and "právě".
    private static final Pattern SENTENCE = Pattern.compile(
            "na Vašem účtu č\\. ((?:\\d{1,6}-)?\\d{2,10}) \\(([A-Z]{3})\\) .*právě proběhla transakce s následujícími"
                    + " údaji:");
    private static final Pattern LABELLED = Pattern.compile(
            "(Částka|Číslo účtu protistrany|Název účtu protistrany|Variabilní symbol|Specifický symbol"
                    + "|Detaily transakce|Datum):(.*)",
            Pattern.DOTALL);
    private static final Pattern SYMBOL_LINE = Pattern.compile("Variabilní symbol:.*", Pattern.DOTALL);
    private static final Pattern NAME_LINE = Pattern.compile("Název účtu protistrany:.*", Pattern.DOTALL);
    // A comma between thousands, a dot before the decimals: "20,546.00 CZK".
    private static final Pattern MONEY = Pattern.compile("([+-]?)(\\d{1,3}(?:,\\d{3})*)\\.(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss");
    private static final String BANK_CODE = "2700";

    private static final String AMOUNT = "Částka";
    private static final String COUNTER_ACCOUNT = "Číslo účtu protistrany";
    private static final String COUNTER_NAME = "Název účtu protistrany";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String DETAILS = "Detaily transakce";
    private static final String DATE = "Datum";
}
