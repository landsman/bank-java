package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are two real KB+ incoming-payment e-mails of a personal account and a real notice about deposit
 * protection, anonymised: accounts, recipient, Message-ID and tracking links replaced, the rest byte for byte. In both
 * payments the message and the three symbols were empty; the tests that fill them put text into those same empty cells.
 */
class KomercniBankaTest {

    @Test
    void readsAnIncomingPayment() throws Exception {
        assertEquals(
                new Transaction(
                        "kbinfo.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/0100", Country.CZ),
                        BankAccount.parse("9876543211/2010", Country.CZ),
                        null,
                        czk("1700.00"),
                        new PaymentSymbols(null, null, null),
                        null,
                        LocalDate.of(2026, 11, 7),
                        null,
                        null,
                        "<00.00.00000.00000000@i-00000000000000000.mta3vrest.sd.prd.sparkpost>"),
                BankNotifications.parseUnverified(fixture("incoming.eml")));
    }

    @Test
    void readsAnAmountUnderAThousand() throws Exception {
        Transaction small = BankNotifications.parseUnverified(fixture("incoming-small.eml"));

        assertEquals(czk("20.00"), small.amount());
        assertEquals(LocalDate.of(2026, 11, 25), small.bookedOn());
    }

    @Test
    void readsTheMessageAndSymbolsFromTheirCells() throws Exception {
        String html = filled(html("incoming.eml"), "nájem 11/2026", "1337", "0308", "42");

        Transaction read = parse(html);

        assertEquals(new PaymentSymbols("1337", "0308", "42"), read.symbols());
        assertEquals("nájem 11/2026", read.message());
    }

    @Test
    void takesNoLineFromAPayersLineBreakInsideTheMessage() throws Exception {
        // In HTML a line break in text is whitespace: the forged label stays inside the message.
        String message = "x\r\nVariabilní symbol\r\n42";
        String html = filled(html("incoming.eml"), message, "", "", "");

        Transaction read = parse(html);

        assertNull(read.symbols().variable());
        assertEquals("x Variabilní symbol 42", read.message());
    }

    @Test
    void refusesASymbolTheEnglishBlockDoesNotRepeat() throws Exception {
        String html = filled(html("incoming.eml"), "", "1337", "", "", "", "", "", "");

        assertDisagree(html);
    }

    @Test
    void refusesAnAmountTheEnglishBlockStatesOtherwise() throws Exception {
        String html = html("incoming.eml").replace("1,700.00 CZK", "1,800.00 CZK");

        assertDisagree(html);
    }

    @Test
    void refusesADueDateTheEnglishBlockStatesOtherwise() throws Exception {
        String html = html("incoming.eml").replace("11-07-2026", "07-11-2026");

        assertDisagree(html);
    }

    @Test
    void refusesACounterAccountTheEnglishBlockStatesOtherwise() throws Exception {
        String html = html("incoming.eml");
        int english = html.indexOf("From account");
        String changed = html.substring(0, english) + html.substring(english).replace("9876543211/2010", "19/2010");

        assertDisagree(changed);
    }

    @Test
    void refusesAMissingAmount() throws Exception {
        String html = html("incoming.eml").replace("Přijali jsme platbu 1" + NBSP + "700,00 Kč", "Přijali jsme platbu");

        assertThrows(Unparsed.class, () -> parse(html));
    }

    @Test
    void refusesAnotherCurrency() throws Exception {
        String html = html("incoming.eml").replace("700,00 Kč", "700,00 EUR");

        assertThrows(Unparsed.class, () -> parse(html));
    }

    @Test
    void refusesAHeadingItDoesNotKnow() throws Exception {
        // KB+ sends incoming payments only; anything else is refused rather than read as one.
        String html = html("incoming.eml").replace("Přijali jsme platbu", "Odeslali jsme platbu");

        assertThrows(Unparsed.class, () -> parse(html));
    }

    @Test
    void refusesASubjectItDoesNotKnow() throws Exception {
        String body = MailBody.lines(html("incoming.eml"));

        Unparsed refused = assertThrows(
                Unparsed.class,
                () -> new KomercniBanka().parse("Servisní zpráva: Odeslali jsme platbu z Vašeho účtu", body));

        assertEquals(Unparsed.Reason.NOT_READ, refused.reason());
    }

    @Test
    void dropsTheNoticeAboutDepositProtection() throws Exception {
        Unparsed refused = assertThrows(
                Unparsed.class, () -> BankNotifications.parseUnverified(fixture("deposit-protection-notice.eml")));

        assertEquals(Unparsed.Reason.NOT_A_PAYMENT, refused.reason());
    }

    private static final String NBSP = String.valueOf((char) 0xA0);
    private static final String EMPTY_CELL = "style=\"font-weight:500\"></font>";
    private static final String SUBJECT = "Servisní zpráva: Přijali jsme platbu na Váš účet";

    private static void assertDisagree(String html) {
        Unparsed refused = assertThrows(Unparsed.class, () -> parse(html));
        assertTrue(String.valueOf(refused.getMessage()).contains("disagree"), refused.getMessage());
    }

    private static Transaction parse(String html) {
        return new KomercniBanka().parse(SUBJECT, MailBody.lines(html));
    }

    /**
     * The message and the three symbols put into their empty cells, Czech then English: four values fill both blocks
     * alike, eight fill each cell on its own.
     */
    private static String filled(String html, String... values) {
        String[] all = values.length == 4
                ? new String[] {values[0], values[1], values[2], values[3], values[0], values[1], values[2], values[3]}
                : values;
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (String value : all) {
            int cell = html.indexOf(EMPTY_CELL, from);
            if (cell < 0) throw new AssertionError("fewer empty cells than values");
            int content = cell + EMPTY_CELL.length() - "</font>".length();
            out.append(html, from, content).append(value);
            from = content;
        }
        return out.append(html.substring(from)).toString();
    }

    private static String html(String name) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), stream(name));
        return (String) message.getContent();
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = stream(name)) {
            return in.readAllBytes();
        }
    }

    private static InputStream stream(String name) {
        return Objects.requireNonNull(KomercniBankaTest.class.getResourceAsStream("/kb-cz/" + name), name);
    }
}
