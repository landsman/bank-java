package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Komerční banka's KB+ e-mail "Přijali jsme platbu na Váš účet", from {@code servis@kbinfo.cz}, sent as HTML only;
 * {@link MailBody} gives one line per cell. Read from two real e-mails of a personal account.
 *
 * <p>The e-mail says everything twice, in Czech and then in English: the direction (and the subject a third time), the
 * amount, the due date, both accounts, the message and the symbols. Both have to agree, or it is refused. KB+ notifies
 * incoming payments only, so any other heading is refused. No transaction id and no balance: deduplicate on the signed
 * {@link Transaction#messageId()}. A label whose value cell is empty is followed by the next label, so the field is
 * absent.
 */
final class KomercniBanka implements Bank {

    static final String DOMAIN = "kbinfo.cz";

    /**
     * {@code scph0521._domainkey.kbinfo.cz}, RSA 1024-bit, read on 2026-11-25 through 1.1.1.1 and 8.8.8.8 alike. See
     * {@link AirBank#KEY_FINGERPRINTS} for how they are kept.
     */
    static final Set<String> KEY_FINGERPRINTS = Set.of("eMt7sZv6hImbWc6czAVHyTbua9n1wrFfAB9Mv8+3n6g=");

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
        if (subject != null && NOT_A_PAYMENT_SUBJECTS.contains(subject)) {
            throw new Unparsed(Unparsed.Reason.NOT_A_PAYMENT, "Komerční banka's notice, not a payment: " + subject);
        }
        if (!INCOMING_SUBJECT.equals(subject)) {
            throw new Unparsed("not a template this library knows, subject: " + subject);
        }
        NotificationLines lines = new NotificationLines(body);
        Matcher czechHeading = lines.single(CZECH_HEADING, "Czech heading");
        Matcher englishHeading = lines.single(ENGLISH_HEADING, "English heading");
        if (czechHeading == null || englishHeading == null) {
            throw new Unparsed("no incoming-payment heading in Czech and in English");
        }
        Money amount = czk(GROUP_SEPARATOR.matcher(czechHeading.group(1)).replaceAll(""), czechHeading.group(2));
        if (!amount.equals(czk(englishHeading.group(1).replace(",", ""), englishHeading.group(2)))) {
            throw new Unparsed("the Czech and the English amount disagree");
        }
        LocalDate due = date(lines.single(CZECH_DUE, "Splatnost"), CZECH_DATE, "Splatnost");
        if (!due.equals(date(lines.single(ENGLISH_DUE, "Due date"), ENGLISH_DATE, "Due date"))) {
            throw new Unparsed("the Czech and the English due date disagree");
        }

        Map<String, String> czech = lines.stacked(CZECH.labels(), Set.of(CZECH.message()), CZECH.afterMessage());
        Map<String, String> english =
                lines.stacked(ENGLISH.labels(), Set.of(ENGLISH.message()), ENGLISH.afterMessage());
        for (Field field : Field.values()) {
            if (!Objects.equals(czech.get(CZECH.label(field)), english.get(ENGLISH.label(field)))) {
                throw new Unparsed("the Czech and the English '" + ENGLISH.label(field) + "' disagree");
            }
        }
        String to = czech.get(CZECH.label(Field.TO));
        String from = czech.get(CZECH.label(Field.FROM));
        if (to == null || from == null) throw new Unparsed("no Na účet or Z účtu");
        try {
            return new Transaction(
                    DOMAIN,
                    Direction.INCOMING,
                    Transaction.Type.TRANSFER,
                    BankAccount.parse(to, Country.CZ),
                    BankAccount.parse(from, Country.CZ),
                    null,
                    amount,
                    new PaymentSymbols(
                            PaymentSymbols.checked("Variabilní symbol", czech.get(CZECH.label(Field.VARIABLE))),
                            PaymentSymbols.checked("Konstantní symbol", czech.get(CZECH.label(Field.CONSTANT))),
                            PaymentSymbols.checked("Specifický symbol", czech.get(CZECH.label(Field.SPECIFIC)))),
                    czech.get(CZECH.message()),
                    due,
                    null,
                    null,
                    null);
        } catch (IllegalArgumentException e) {
            throw new Unparsed(e.getMessage() == null ? "invalid value" : e.getMessage(), e);
        }
    }

    private static Money czk(String units, String hundredths) {
        return new Money(new BigDecimal(units + "." + hundredths), Currency.getInstance("CZK"));
    }

    private static LocalDate date(@Nullable Matcher line, DateTimeFormatter format, String label) {
        if (line == null) throw new Unparsed("no " + label);
        try {
            return LocalDate.parse(line.group(1), format);
        } catch (DateTimeParseException e) {
            throw new Unparsed(label + " is not a date: " + line.group(1));
        }
    }

    /** What both languages state, each under its own label. */
    private enum Field {
        FROM,
        TO,
        MESSAGE,
        VARIABLE,
        CONSTANT,
        SPECIFIC
    }

    /**
     * One language's labels, in the order the e-mail lists them, and the line that ends its block: the last symbol is
     * followed by it when the symbol is empty, so it must count as a label, or it would be read as the symbol.
     */
    private record Labels(Map<Field, String> byField, String end) {

        String label(Field field) {
            return Objects.requireNonNull(byField.get(field), field.name());
        }

        String message() {
            return label(Field.MESSAGE);
        }

        Set<String> labels() {
            return Set.of(
                    label(Field.FROM),
                    label(Field.TO),
                    message(),
                    label(Field.VARIABLE),
                    label(Field.CONSTANT),
                    label(Field.SPECIFIC),
                    end);
        }

        /** Past the message, only the symbols and the end of the block follow. */
        Set<String> afterMessage() {
            return Set.of(label(Field.VARIABLE), label(Field.CONSTANT), label(Field.SPECIFIC), end);
        }
    }

    private static final Labels CZECH = new Labels(
            Map.of(
                    Field.FROM, "Z účtu",
                    Field.TO, "Na účet",
                    Field.MESSAGE, "Zpráva pro příjemce",
                    Field.VARIABLE, "Variabilní symbol",
                    Field.CONSTANT, "Konstantní symbol",
                    Field.SPECIFIC, "Specifický symbol"),
            "Chcete s\u00A0něčím poradit?");
    private static final Labels ENGLISH = new Labels(
            Map.of(
                    Field.FROM, "From account",
                    Field.TO, "To account",
                    Field.MESSAGE, "Message for beneficiary",
                    Field.VARIABLE, "Variable symbol",
                    Field.CONSTANT, "Constant symbol",
                    Field.SPECIFIC, "Specific symbol"),
            "We are here to help");

    private static final String INCOMING_SUBJECT = "Servisní zpráva: Přijali jsme platbu na Váš účet";
    // Same sender, same key: a legal notice sent to every client, which the account holder cannot turn off.
    private static final Set<String> NOT_A_PAYMENT_SUBJECTS = Set.of(
            "Servisní zpráva: Informace o pojištění vkladů / Service message: Information on deposit protection");

    // "1 700,00 Kč", a no-break space between the thousands; KB writes no-break spaces between words too.
    private static final Pattern CZECH_HEADING =
            Pattern.compile("Přijali jsme platbu (\\d{1,3}(?:[ \u00A0\u202F]\\d{3})*),(\\d{2}) Kč na[ \u00A0]Váš účet");
    private static final Pattern GROUP_SEPARATOR = Pattern.compile("[ \u00A0\u202F]");
    private static final Pattern ENGLISH_HEADING =
            Pattern.compile("We have received the payment (\\d{1,3}(?:,\\d{3})*)\\.(\\d{2}) CZK to your account");
    private static final Pattern CZECH_DUE = Pattern.compile("Splatnost: +(\\d{2}\\. \\d{2}\\. \\d{4})");
    private static final Pattern ENGLISH_DUE = Pattern.compile("Due date: +(\\d{2}-\\d{2}-\\d{4})");
    private static final DateTimeFormatter CZECH_DATE = DateTimeFormatter.ofPattern("dd. MM. uuuu");
    private static final DateTimeFormatter ENGLISH_DATE = DateTimeFormatter.ofPattern("MM-dd-uuuu");
}
