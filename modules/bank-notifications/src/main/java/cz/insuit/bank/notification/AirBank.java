package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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
final class AirBank {

    static final String DOMAIN = "airbank.cz";

    static Transaction parse(String body) {
        Matcher summary = null;
        Matcher incoming = null;
        Matcher outgoing = null;
        Map<String, String> fields = new HashMap<>();
        boolean afterMessage = false;

        for (String raw : body.lines().toList()) {
            // The outgoing template ends the transaction code with a U+FEFF.
            String line = raw.replace(ZERO_WIDTH_NO_BREAK_SPACE, "").strip();
            Matcher summaryLine = SUMMARY.matcher(line);
            Matcher incomingLine = INCOMING.matcher(line);
            Matcher outgoingLine = OUTGOING.matcher(line);
            Matcher labelled = LABELLED.matcher(line);
            if (summaryLine.matches()) {
                if (summary != null) throw new Unparsed("two summary lines");
                summary = summaryLine;
            } else if (incomingLine.matches() || outgoingLine.matches()) {
                if (incoming != null || outgoing != null) throw new Unparsed("two counter-party lines");
                if (incomingLine.matches()) incoming = incomingLine;
                else outgoing = outgoingLine;
            } else if (labelled.matches()) {
                String label = labelled.group(1);
                // The message is the only free text a payer controls. Should the bank ever pass a line break through,
                // a forged "Variabilní symbol:" after it must not count: past the message only the code may follow.
                if (afterMessage && !label.equals(TRANSACTION_ID)) {
                    throw new Unparsed("'" + label + "' after the message");
                }
                if (fields.put(label, labelled.group(2)) != null) throw new Unparsed("two '" + label + "' lines");
                afterMessage |= label.equals(MESSAGE);
            }
        }

        if (summary == null) throw new Unparsed("no balance-change sentence");
        if (incoming == null && outgoing == null) throw new Unparsed("no counter-party line");
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
        String variableSymbol = fields.get(VARIABLE_SYMBOL);
        if (variableSymbol != null && !variableSymbol.matches("\\d{1,10}")) {
            throw new Unparsed("variable symbol is not 1 to 10 digits");
        }
        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(booked, DATE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("booking date not d.M.yyyy: " + booked);
        }

        String counterAccount = incoming != null
                ? incoming.group(2)
                : Objects.requireNonNull(outgoing).group(1);
        @Nullable String counterName = incoming != null ? incoming.group(1) : null;
        try {
            BigDecimal value = new BigDecimal(money.group(1) + "." + money.group(2));
            Money signed = new Money(value, Currency.getInstance(money.group(3)));
            return new Transaction(
                    DOMAIN,
                    direction,
                    // Both lines this parser reads say "úhrada", a transfer. A card payment or a direct debit, once
                    // seen, is a line of its own.
                    Transaction.Type.TRANSFER,
                    BankAccount.parse(summary.group(1)),
                    BankAccount.parse(counterAccount),
                    counterName,
                    direction == Direction.INCOMING ? signed : signed.negate(),
                    new PaymentSymbols(variableSymbol, null, null),
                    fields.get(MESSAGE),
                    bookedOn,
                    transactionId);
        } catch (IllegalArgumentException e) {
            // An account failing its checksum, or a currency code ISO 4217 does not know: a misread, not a payment.
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    // The account nickname is the client's own text and may contain "číslo", hence the greedy ".*" before it.
    private static final Pattern SUMMARY =
            Pattern.compile("zůstatek na účtu .* číslo (\\S+/3030) se (zvýšil|snížil) o částku (\\S+ [A-Z]{3})\\. .*");
    private static final Pattern INCOMING = Pattern.compile("Příchozí úhrada z účtu (.*) číslo (\\S+/\\d{4})");
    private static final Pattern OUTGOING = Pattern.compile("Odchozí úhrada na účet číslo (\\S+/\\d{4})");
    private static final Pattern LABELLED =
            Pattern.compile("(Částka|Datum zaúčtování|Variabilní symbol|Zpráva pro plátce|Kód transakce): (.*)");
    private static final Pattern MONEY = Pattern.compile("(\\d+),(\\d{2}) ([A-Z]{3})");
    private static final String ZERO_WIDTH_NO_BREAK_SPACE = String.valueOf((char) 0xFEFF);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu");

    private static final String AMOUNT = "Částka";
    private static final String BOOKED_ON = "Datum zaúčtování";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    // Seen on an outgoing payment. The incoming label, and KS and SS, are not known yet; until a sample shows them
    // they are not read.
    private static final String MESSAGE = "Zpráva pro plátce";
    private static final String TRANSACTION_ID = "Kód transakce";

    private AirBank() {}
}
