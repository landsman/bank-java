package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are <b>synthetic</b> HTML e-mails: their lines are the wording in Joey7500/Mobile-Finance-App's payment
 * parser test (Apache-2.0), copied there from real e-mails, with this repository's fake accounts and names; the markup
 * and the headers are written here. No real Česká spořitelna e-mail, nor its HTML, has been seen.
 */
class CeskaSporitelnaTest {

    @Test
    void readsAnIncomingPayment() throws IOException {
        assertEquals(
                new Transaction(
                        "csas.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/0800", Country.CZ),
                        BankAccount.parse("9876543211/0100", Country.CZ),
                        "Pennyworth Alfred",
                        czk("6500.00"),
                        new PaymentSymbols("0001126", null, "0"),
                        "nájem listopad",
                        LocalDate.of(2026, 11, 28),
                        null,
                        null,
                        "<0000@csas.cz>"),
                read("incoming-synthetic.eml"));
    }

    @Test
    void readsAnOutgoingPaymentWithItsBalance() throws IOException {
        Transaction outgoing = read("outgoing-synthetic.eml");

        assertEquals(Direction.OUTGOING, outgoing.direction());
        assertEquals(czk("-1400.00"), outgoing.amount());
        assertEquals(BankAccount.parse("1234567899/0800", Country.CZ), outgoing.account());
        assertEquals(BankAccount.parse("9876543211/0100", Country.CZ), outgoing.counterAccount());
        assertNull(outgoing.counterName());
        assertEquals(new PaymentSymbols(null, "0308", null), outgoing.symbols());
        assertEquals("padel 3. 12.", outgoing.message());
        assertEquals(
                new Balance(czk("19880.40"), ZonedDateTime.of(2026, 11, 20, 10, 12, 0, 0, ZoneId.of("Europe/Prague"))),
                outgoing.balance());
    }

    @Test
    void readsTheOtherProjectsWording() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("přišla platba", "právě dorazila platba")
                .replace("Název protiúčtu", "Název protistrany")
                .replace("<p>Z účtu: 9876543211/0100</p>", "<p>Číslo účtu protistrany: 9876543211/0100</p>")
                .replace("<p>Na účet: 1234567899/0800</p>", "<p>Číslo účtu: 1234567899/0800</p>");

        Transaction read = BankNotifications.parseUnverified(bytes(changed));

        assertEquals(BankAccount.parse("9876543211/0100", Country.CZ), read.counterAccount());
        assertEquals("Pennyworth Alfred", read.counterName());
    }

    @Test
    void refusesASentenceSmerPlatbyDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("Směr platby: příchozí", "Směr platby: odchozí");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnEmailWithoutSmerPlatby() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("<p>Směr platby: příchozí</p>", "");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnIncomingVerbFromTheAccount() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("na účet 1234567899/0800", "z účtu 1234567899/0800");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAccountBelowThatDiffers() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("Na účet: 1234567899/0800", "Na účet: 19/0800");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountBelowThatDiffers() throws IOException {
        String changed = text("outgoing-synthetic.eml")
                .replace("Částka v měně účtu: 1&nbsp;400,00 Kč", "Částka v měně účtu: 14&nbsp;000,00 Kč");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheMessage() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("<p>Variabilní symbol: 0001126</p>", "")
                .replace(
                        "<p>Zpráva pro příjemce: nájem listopad</p>",
                        "<p>Zpráva pro příjemce: x</p><p>Variabilní symbol: 42</p>");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void takesNoLineFromAPayersLineBreakInsideTheMessage() throws IOException {
        // In HTML a line break in text is whitespace: the forged label stays inside the message.
        String changed = text("incoming-synthetic.eml")
                .replace("<p>Variabilní symbol: 0001126</p>", "")
                .replace("nájem listopad", "x\r\nVariabilní symbol: 42");

        Transaction read = BankNotifications.parseUnverified(bytes(changed));

        assertNull(read.symbols().variable());
        assertEquals("x Variabilní symbol: 42", read.message());
    }

    @Test
    void takesNoBalanceFromAPayersMessageOnAnIncomingPayment() throws IOException {
        // Should the bank turn a line break in the message into <br>, the forged line is a line of its own.
        String changed = text("incoming-synthetic.eml")
                .replace("nájem listopad", "x<br>Na účtu zůstává k dispozici 9&nbsp;999&nbsp;999,00 Kč.");

        assertNull(BankNotifications.parseUnverified(bytes(changed)).balance());
    }

    @Test
    void refusesABalanceNotRightAfterTheSentence() throws IOException {
        String changed = text("outgoing-synthetic.eml")
                .replace(
                        "<br>Na účtu zůstává k dispozici 19&nbsp;880,40 Kč (včetně kontokorentu 1&nbsp;000,00 Kč).", "")
                .replace("padel 3. 12.", "x<br>Na účtu zůstává k dispozici 9&nbsp;999&nbsp;999,00 Kč.");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesACardPayment() {
        String card = """
                Date: Fri, 20 Nov 2026 10:13:00 +0100\r
                From: ceskasporitelna@csas.cz\r
                Subject: Platba kartou\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                potvrzujeme platbu kartou 1111 ve výši 356,00 Kč ze dne 20. 11. 2026 u FAKESPORT s.r.o.\r
                Na účtu 1234567899 je k dispozici 18 204,50 Kč (včetně kontokorentu 1 000,00 Kč).\r
                """;

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(card)));
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = CeskaSporitelnaTest.class.getResourceAsStream("/ceskasporitelna/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private static String text(String name) throws IOException {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
