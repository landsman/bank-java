package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * ČSOB's CEB notification, "CEB Info: Zaúčtování platby", from {@code csob.cz}: one block per booked transaction, each
 * opened by a sentence and closed by the balance after it. Several blocks may come in one e-mail.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows the fixtures of
 * <a href="https://github.com/tomaj/bank-mails-parser">tomaj/bank-mails-parser</a> (MIT, 2018 to 2022). ČSOB signed as
 * {@code d=csob.cz} with selector {@code alpha} in 2020, a key it has since replaced, so no key is pinned and
 * {@link BankNotifications#withDns()} refuses every ČSOB e-mail as unverified until a real one shows the current one.
 *
 * <p><b>The balances chain.</b> With several blocks, each balance has to be the one before plus the block's amount, and
 * every block needs its balance: a block forged in a payer's message cannot keep the chain, while without balances it
 * could not be told from a real one, so such an e-mail is refused. In a block, the sentence's account is the
 * {@code Účet} line's, the amount's currency is the account's, and the sign is the direction.
 */
final class Csob implements Bank {

    static final String DOMAIN = "csob.cz";

    /** None yet: the 2020 selector {@code alpha} is gone. See {@link AirBank#KEY_FINGERPRINTS} for how keys are kept. */
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
        List<Transaction> all = parseAll(subject, body);
        if (all.size() != 1) throw new Unparsed(all.size() + " transactions in one e-mail: read them with readAll");
        return all.get(0);
    }

    @Override
    public List<Transaction> parseAll(@Nullable String subject, String body) {
        if (OWN_BANKING_SUBJECT.equals(subject)) return List.of(ownBanking(body));
        List<List<String>> blocks = blocks(body);
        if (blocks.isEmpty()) throw new Unparsed("no transaction sentence");
        List<Transaction> transactions = new ArrayList<>();
        Money previousBalance = null;
        for (List<String> block : blocks) {
            Block read = block(block);
            if (blocks.size() > 1) {
                if (read.balance == null) throw new Unparsed("several transactions, one without its balance");
                if (previousBalance != null
                        && previousBalance
                                        .amount()
                                        .add(read.transaction.amount().amount())
                                        .compareTo(read.balance.amount())
                                != 0) {
                    throw new Unparsed("the balances do not chain: a transaction that does not add up");
                }
            }
            previousBalance = read.balance;
            transactions.add(read.transaction);
        }
        return List.copyOf(transactions);
    }

    /** The lines of each block: from one transaction sentence up to the next, or to the end. */
    private static List<List<String>> blocks(String body) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (SENTENCE.matcher(line).matches()) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            if (current != null) current.add(line);
        }
        return blocks;
    }

    private record Block(Transaction transaction, @Nullable Money balance) {}

    private static Block block(List<String> block) {
        NotificationLines lines = new NotificationLines(String.join("\n", block));
        Matcher sentence = lines.single(SENTENCE, "transaction sentence");
        if (sentence == null) throw new Unparsed("no transaction sentence");
        // Past the payer's message only the balance line follows, which is not a labelled one.
        Map<String, String> fields = lines.labelled(LABELLED, Set.of(MESSAGE), Set.of());

        String ownLine = fields.get(ACCOUNT);
        Matcher own = ownLine == null ? null : OWN_ACCOUNT.matcher(ownLine);
        if (own == null || !own.matches()) throw new Unparsed("Účet missing or not in the known format");
        if (!own.group(1).equals(sentence.group(2)))
            throw new Unparsed("the sentence's account is not the Účet line's");

        String amountText = fields.get(AMOUNT);
        Money amount = amountText == null ? null : signed(amountText);
        if (amount == null) throw new Unparsed("amount missing or not in the known format");
        if (!amount.currency().getCurrencyCode().equals(own.group(2))) {
            throw new Unparsed("the amount's currency is not the account's");
        }
        Matcher balanceLine = lines.single(BALANCE, "balance");
        Money balance = balanceLine == null ? null : signed(balanceLine.group(1));
        if (balanceLine != null && balance == null) throw new Unparsed("balance not in the known format");

        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(sentence.group(1), DATE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not d.M.yyyy: " + sentence.group(1));
        }
        String kind = sentence.group(3) != null ? sentence.group(3) : CARD;
        Direction direction = amount.amount().signum() < 0 ? Direction.OUTGOING : Direction.INCOMING;
        if (INCOMING_KINDS.contains(kind) && direction != Direction.INCOMING) {
            throw new Unparsed("'" + kind + "' with a negative amount");
        }
        String counter = fields.get(COUNTER_ACCOUNT);
        try {
            Transaction transaction = new Transaction(
                    DOMAIN,
                    direction,
                    kind.equals(CARD) ? Transaction.Type.CARD : Transaction.Type.TRANSFER,
                    BankAccount.parse(own.group(1) + "/" + BANK_CODE),
                    counter == null ? null : BankAccount.parse(counter),
                    fields.get(COUNTER_NAME),
                    amount,
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, fields.get(VARIABLE_SYMBOL)),
                            PaymentSymbols.checked(CONSTANT_SYMBOL, fields.get(CONSTANT_SYMBOL)),
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, fields.get(SPECIFIC_SYMBOL))),
                    fields.get(MESSAGE),
                    bookedOn,
                    null,
                    // The balance line has no time; the block's date is all there is.
                    null,
                    null);
            return new Block(transaction, balance);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    /**
     * The notification of ČSOB's own internet banking, "Moje info - Avízo": one transaction, the labels on lines of their
     * own and each value under its label. Read from radekhulan/myinvoice's samples (MIT), not a real e-mail. The
     * direction is stated twice, in the kind ({@code Příchozí} / {@code Odchozí}) and the amount's sign.
     */
    private static Transaction ownBanking(String body) {
        NotificationLines lines = new NotificationLines(body);
        Matcher sentence = lines.single(OWN_SENTENCE, "transaction sentence");
        if (sentence == null) throw new Unparsed("no transaction sentence");
        // The counter-party's name is the line under its label; the booking date's label comes right after it.
        lines.requireAt(OWN_NAME_LABEL, 2, OWN_BOOKED);
        Map<String, String> fields = lines.stacked(OWN_LABELS, Set.of(), Set.of());

        String ownText = fields.get(ACCOUNT);
        Matcher own = ownText == null ? null : OWN_BANKING_ACCOUNT.matcher(ownText);
        if (own == null || !own.matches()) throw new Unparsed("Účet missing or not in the known format");
        if (!own.group(1).equals(sentence.group(2)))
            throw new Unparsed("the sentence's account is not the Účet line's");
        String amountText = fields.get(AMOUNT);
        Money amount = amountText == null ? null : signed(amountText);
        if (amount == null) throw new Unparsed("amount missing or not in the known format");
        Direction direction = sentence.group(3).equals("Příchozí") ? Direction.INCOMING : Direction.OUTGOING;
        if ((direction == Direction.INCOMING) != (amount.amount().signum() > 0)) {
            throw new Unparsed("the kind and the amount's sign disagree on the direction");
        }
        String booked = fields.get(OWN_BOOKED);
        if (booked == null) throw new Unparsed("no Datum účtování");
        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(booked, DATE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("Datum účtování not d.M.yyyy: " + booked);
        }
        String counter = fields.get(COUNTER_ACCOUNT);
        try {
            return new Transaction(
                    DOMAIN,
                    direction,
                    Transaction.Type.TRANSFER,
                    BankAccount.parse(own.group(1) + "/" + BANK_CODE),
                    counter == null ? null : BankAccount.parse(counter),
                    fields.get(COUNTER_NAME),
                    amount,
                    new PaymentSymbols(
                            PaymentSymbols.checked(VARIABLE_SYMBOL, unpadded(fields.get(VARIABLE_SYMBOL))),
                            PaymentSymbols.checked(CONSTANT_SYMBOL, unpadded(fields.get(CONSTANT_SYMBOL))),
                            PaymentSymbols.checked(SPECIFIC_SYMBOL, unpadded(fields.get(SPECIFIC_SYMBOL)))),
                    null,
                    bookedOn,
                    null,
                    null,
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    /**
     * A symbol as this template prints it, padded with zeros to 13 digits in the sample ({@code 0009002567593}); a symbol
     * has 10 at most. Only the zeros beyond ten are cut, so zeros that belong to the symbol stay; anything else is left
     * for {@link PaymentSymbols#checked} to refuse.
     */
    private static @Nullable String unpadded(@Nullable String symbol) {
        if (symbol == null || symbol.length() <= 10) return symbol;
        String excess = symbol.substring(0, symbol.length() - 10);
        return excess.chars().allMatch(c -> c == '0') ? symbol.substring(symbol.length() - 10) : symbol;
    }

    /** A signed amount, {@code +1 234,56 CZK}: positive for money in, negative for money out. */
    private static @Nullable Money signed(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) return null;
        BigDecimal value = new BigDecimal(matcher.group(2).replace(" ", "") + "." + matcher.group(3));
        Money money = new Money(value, Currency.getInstance(matcher.group(4)));
        return matcher.group(1).equals("-") ? money.negate() : money;
    }

    // The kinds seen in the samples, all incoming; any other is refused rather than guessed at.
    private static final Set<String> INCOMING_KINDS =
            Set.of("Došlá platba", "Došlá úhrada", "Příchozí úhrada", "Příchozí úhrada okamžitá");
    private static final Pattern SENTENCE = Pattern.compile(
            "Dne (\\d{1,2}\\.\\d{1,2}\\.\\d{4}) byla na účtu (\\d{2,10}) (?:zaúčtovaná|zaúčtována) transakce"
                    + " (?:typu: (Došlá platba|Došlá úhrada|Příchozí úhrada okamžitá|Příchozí úhrada)|(platební kartou))\\.");
    private static final Pattern LABELLED = Pattern.compile(
            "(Název smlouvy|Číslo smlouvy|Majitel smlouvy|Účet|Částka|Účet protistrany|Název protistrany"
                    + "|Z účtu|Variabilní symbol|Konstantní symbol|Specifický symbol|Zpráva příjemci): (.*)",
            Pattern.DOTALL);
    private static final Pattern OWN_ACCOUNT = Pattern.compile("(\\d{2,10}), ([A-Z]{3}), .*");
    private static final Pattern BALANCE = Pattern.compile("Zůstatek na účtu po zaúčtování transakce: (.+)\\.");
    // A sign, a space between thousands: "+1 234,56 CZK".
    private static final Pattern MONEY = Pattern.compile("([+-])(\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu");

    private static final String OWN_BANKING_SUBJECT = "Moje info - Avízo";
    private static final Pattern OWN_SENTENCE =
            Pattern.compile("dne (\\d{1,2}\\.\\d{1,2}\\.\\d{4}) byla na účtu (\\d{2,10}) zaúčtována transakce typu:"
                    + " (Příchozí|Odchozí) úhrada(?: okamžitá)?\\.");
    private static final Pattern OWN_BANKING_ACCOUNT = Pattern.compile("(\\d{2,10})/0300");
    private static final Pattern OWN_NAME_LABEL = Pattern.compile("Název protistrany");
    private static final String OWN_BOOKED = "Datum účtování";
    private static final Set<String> OWN_LABELS = Set.of(
            "Účet",
            "Účet protistrany",
            "Název protistrany",
            OWN_BOOKED,
            "Částka",
            "Variabilní symbol",
            "Konstantní symbol",
            "Specifický symbol");
    private static final String BANK_CODE = "0300";

    private static final String CARD = "platební kartou";
    private static final String ACCOUNT = "Účet";
    private static final String AMOUNT = "Částka";
    private static final String COUNTER_ACCOUNT = "Účet protistrany";
    private static final String COUNTER_NAME = "Název protistrany";
    private static final String VARIABLE_SYMBOL = "Variabilní symbol";
    private static final String CONSTANT_SYMBOL = "Konstantní symbol";
    private static final String SPECIFIC_SYMBOL = "Specifický symbol";
    private static final String MESSAGE = "Zpráva příjemci";
}
