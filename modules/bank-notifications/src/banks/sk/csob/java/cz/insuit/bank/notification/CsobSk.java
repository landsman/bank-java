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
 * ČSOB Slovakia's "ČSOB Info 24 - Avízo", from {@code csob.sk}, in windows-1250: a SEPA credit booked to the account.
 *
 * <p><b>Read from public samples, not from a real signed e-mail</b>: the template follows
 * <a href="https://github.com/tomaj/bank-mails-parser">tomaj/bank-mails-parser</a>'s fixtures and
 * <a href="https://github.com/remp2020/crm-payments-module">remp2020/crm-payments-module</a>'s raw e-mail (both MIT, 2019).
 * That e-mail carries no DKIM signature of the bank, and {@code csob.sk} is not DNSSEC-signed (2026-11-10), so no key is
 * pinned and {@link BankNotifications#withDns()} refuses every ČSOB SK e-mail as unverified; a real one has to show
 * whether ČSOB SK signs at all.
 *
 * <p>Only incoming payments are read: no outgoing one has been seen, so an amount with a minus is refused. The symbols
 * come from the payer's reference ({@code /VS…/SS…/KS…}), text the payer wrote. The template prints that line every time,
 * {@code NOTPROVIDED} when there is none, so it is required exactly once, and it has to follow the counter-party's name
 * directly; past the message for the recipient no labelled line may follow.
 */
final class CsobSk implements Bank {

    static final String DOMAIN = "csob.sk";

    /** None: no signed e-mail seen. See {@link AirBank#KEY_FINGERPRINTS} for how keys are kept. */
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
        Matcher sentence = lines.single(SENTENCE, "booking sentence");
        if (sentence == null) throw new Unparsed("no booking sentence");
        if (lines.single(REFERENCE_LINE, "payer's reference") == null)
            throw new Unparsed("no referencia platiteľa line");
        // The counter-party's name is text its holder chose; the payer's reference comes right after it.
        lines.requireNext(NAME_LINE, REFERENCE + ":");
        // Past the message for the recipient only the balance line follows, which is not a labelled one.
        Map<String, String> fields = lines.labelled(LABELLED, Set.of(MESSAGE), Set.of());

        String amountText = fields.get(AMOUNT);
        Matcher amount = amountText == null ? null : MONEY.matcher(amountText);
        if (amount == null || !amount.matches()) throw new Unparsed("suma missing or not in the known format");
        if (!amount.group(1).equals("+")) throw new Unparsed("an outgoing payment, not seen in a sample yet");

        LocalDate bookedOn;
        try {
            bookedOn = LocalDate.parse(sentence.group(1), DATE);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not d.M.yyyy: " + sentence.group(1));
        }
        Matcher reference = SYMBOLS.matcher(fields.getOrDefault(REFERENCE, ""));
        boolean symbols = reference.matches();
        String counter = fields.get(COUNTER_ACCOUNT);
        try {
            return new Transaction(
                    DOMAIN,
                    Direction.INCOMING,
                    Transaction.Type.TRANSFER,
                    BankAccount.parseIban(sentence.group(2)),
                    counter == null ? null : BankAccount.parseIban(counter),
                    fields.get(COUNTER_NAME),
                    new Money(
                            new BigDecimal(amount.group(2).replace(" ", "") + "." + amount.group(3)),
                            Currency.getInstance(amount.group(4))),
                    symbols
                            ? new PaymentSymbols(
                                    PaymentSymbols.checked("VS", empty(reference.group(1))),
                                    PaymentSymbols.checked("KS", empty(reference.group(3))),
                                    PaymentSymbols.checked("SS", empty(reference.group(2))))
                            : PaymentSymbols.NONE,
                    fields.get(MESSAGE),
                    bookedOn,
                    null,
                    // The balance line has no time; the date is all there is.
                    null,
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static @Nullable String empty(String symbol) {
        return symbol.isEmpty() ? null : symbol;
    }

    // The account holder's name follows the IBAN; it is the bank's record, not the payer's.
    private static final Pattern SENTENCE = Pattern.compile(
            "dovoľujeme si Vám oznámiť, že dňa (\\d{1,2}\\.\\d{1,2}\\.\\d{4}) bola na účte (SK\\d{2}(?: \\d{4}){5}) .*"
                    + " zaúčtovaná suma SEPA platobného príkazu:");
    private static final Pattern LABELLED = Pattern.compile(
            "(suma|z účtu|banka|názov protiúčtu|referencia platiteľa|informácia pre príjemcu):\\s*(.*)",
            Pattern.DOTALL);
    private static final Pattern REFERENCE_LINE = Pattern.compile("referencia platiteľa:.*", Pattern.DOTALL);
    private static final Pattern NAME_LINE = Pattern.compile("názov protiúčtu:.*", Pattern.DOTALL);
    // The payer's reference, "/VS212049000/SS964/KS0308"; each symbol may be empty.
    private static final Pattern SYMBOLS = Pattern.compile("/VS(\\d{0,10})/SS(\\d{0,10})/KS(\\d{0,10})");
    // A sign, no separator between thousands in the samples: "+1150,00 EUR".
    private static final Pattern MONEY = Pattern.compile("([+-])(\\d+),(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.uuuu");

    private static final String AMOUNT = "suma";
    private static final String COUNTER_ACCOUNT = "z účtu";
    private static final String COUNTER_NAME = "názov protiúčtu";
    private static final String REFERENCE = "referencia platiteľa";
    private static final String MESSAGE = "informácia pre príjemcu";
}
