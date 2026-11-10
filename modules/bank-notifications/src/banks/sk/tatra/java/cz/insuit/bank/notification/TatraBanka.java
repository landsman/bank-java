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
 * Tatra banka's B-mail, "Kredit na ucte (ID=…)", from {@code b-mail@tatrabanka.sk}, in ISO-8859-2 without diacritics.
 *
 * <p><b>Read from public samples, not from a real e-mail yet</b>: the template follows
 * <a href="https://github.com/remp2020/crm-payments-module">remp2020/crm-payments-module</a>'s raw e-mail (2019) and
 * <a href="https://github.com/tomaj/bank-mails-parser">tomaj/bank-mails-parser</a>'s fixtures (2015), both MIT. That
 * e-mail was signed with selector {@code mta}, and {@code mta._domainkey.tatrabanka.sk} is still published, RSA 2048,
 * DNSSEC-secure, fingerprint {@code sOUiSq9+xyeXpczY9SzI+HLjzU4jZyfQUXGvxjtXmVc=} (2026-11-10, through 1.1.1.1 and
 * 8.8.8.8). It is not pinned until a real e-mail confirms the template and that Tatra still signs with it, so
 * {@link BankNotifications#withDns()} refuses every Tatra banka e-mail as unverified.
 *
 * <p>The signed subject carries the transaction id and the direction ({@code Kredit} / {@code Debet}); the sentence
 * states the direction again, and the two have to agree. The symbols come from the payer's reference
 * ({@code /VS…/SS…/KS…}), text the payer wrote: one not in that form leaves them out rather than refusing the payment.
 * {@code Ucet protistrany} holds the other side's name, not an account.
 */
final class TatraBanka implements Bank {

    static final String DOMAIN = "tatrabanka.sk";

    /** None yet; see the class comment for the one the 2019 sample was signed with. */
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
        Matcher stated = subject == null ? null : SUBJECT.matcher(subject);
        if (stated == null || !stated.matches())
            throw new Unparsed("not a template this library knows, subject: " + subject);

        NotificationLines lines = new NotificationLines(body);
        Matcher sentence = lines.single(SENTENCE, "balance sentence");
        if (sentence == null) throw new Unparsed("no balance sentence");
        // The other side's name and the message for the recipient are the payer's text; nothing labelled follows them.
        Map<String, String> fields = lines.labelled(LABELLED, FREE_TEXT, Set.of());
        // Every sample prints the payer's reference line, an empty one too; without it, it is another template.
        if (lines.single(REFERENCE_LINE, "payer's reference") == null) throw new Unparsed("no Referencia platitela");

        Direction direction = sentence.group(3).equals("zvyseny") ? Direction.INCOMING : Direction.OUTGOING;
        if (direction != (stated.group(1).equals("Kredit") ? Direction.INCOMING : Direction.OUTGOING)) {
            throw new Unparsed("subject and sentence disagree on the direction");
        }
        Money amount = money(sentence.group(4));
        if (amount == null) throw new Unparsed("amount not in the known format: " + sentence.group(4));
        ZonedDateTime at;
        try {
            at = LocalDateTime.parse(sentence.group(1), DATE_TIME).atZone(BRATISLAVA);
        } catch (DateTimeParseException e) {
            throw new Unparsed("date not d.M.yyyy H:mm: " + sentence.group(1));
        }
        String availableText = fields.get(AVAILABLE);
        Money available = availableText == null ? null : money(availableText);
        Matcher reference = SYMBOLS.matcher(fields.getOrDefault(REFERENCE, ""));
        try {
            return new Transaction(
                    DOMAIN,
                    direction,
                    Transaction.Type.GENERAL,
                    BankAccount.parseIban(sentence.group(2)),
                    null,
                    fields.get(COUNTER_NAME),
                    direction == Direction.INCOMING ? amount : amount.negate(),
                    reference.matches()
                            ? new PaymentSymbols(
                                    empty(reference.group(1)), empty(reference.group(3)), empty(reference.group(2)))
                            : PaymentSymbols.NONE,
                    fields.get(MESSAGE),
                    at.toLocalDate(),
                    stated.group(2),
                    available == null ? null : new Balance(available, at),
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static @Nullable String empty(String symbol) {
        return symbol.isEmpty() ? null : symbol;
    }

    private static @Nullable Money money(String text) {
        Matcher matcher = MONEY.matcher(text);
        if (!matcher.matches()) return null;
        return new Money(
                new BigDecimal(matcher.group(1).replace(" ", "") + "." + matcher.group(2)),
                Currency.getInstance(matcher.group(3)));
    }

    private static final Pattern SUBJECT = Pattern.compile("(Kredit|Debet) na ucte \\(ID=(\\d{6}/\\d{6}-\\d+)\\)");
    private static final Pattern SENTENCE = Pattern.compile(
            "(\\d{1,2}\\.\\d{1,2}\\.\\d{4} \\d{1,2}:\\d{2}) bol zostatok Vasho uctu (SK\\d{22}) (zvyseny|znizeny) o (.+)\\.");
    private static final Pattern LABELLED = Pattern.compile(
            "(uctovny zostatok|aktualny zostatok|disponibilny zostatok|Popis transakcie|Referencia platitela"
                    + "|Ucet protistrany|Informacia pre prijemcu):\\s*(.*)",
            Pattern.DOTALL);
    private static final Pattern REFERENCE_LINE = Pattern.compile("Referencia platitela:.*", Pattern.DOTALL);
    // The payer's reference, "/VS1234056789/SS/KS"; each symbol may be empty, ten digits at most.
    private static final Pattern SYMBOLS = Pattern.compile("/VS(\\d{0,10})/SS(\\d{0,10})/KS(\\d{0,10})");
    // A space between thousands: "88 888,62 EUR".
    private static final Pattern MONEY = Pattern.compile("(\\d{1,3}(?: \\d{3})*),(\\d{2}) ([A-Z]{3})");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d.M.uuuu H:mm");
    private static final ZoneId BRATISLAVA = ZoneId.of("Europe/Bratislava");

    private static final String AVAILABLE = "disponibilny zostatok";
    private static final String REFERENCE = "Referencia platitela";
    private static final String COUNTER_NAME = "Ucet protistrany";
    private static final String MESSAGE = "Informacia pre prijemcu";
    private static final Set<String> FREE_TEXT = Set.of(COUNTER_NAME, MESSAGE);
}
