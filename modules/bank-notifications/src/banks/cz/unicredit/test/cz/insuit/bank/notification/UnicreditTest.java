package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The fixture is a <b>synthetic</b> HTML e-mail: its body is the one sanitised sample in radekhulan/myinvoice's
 * UniCredit parser test (MIT), with this repository's fake accounts (the sample's failed the modulo 11 check) and
 * Wayne Enterprises; the headers are written here. No real UniCredit e-mail has been seen.
 */
class UnicreditTest {

    @Test
    void readsAnIncomingPaymentWithAnEnglishAmount() throws IOException {
        assertEquals(
                new Transaction(
                        "unicreditgroup.cz",
                        Direction.INCOMING,
                        Transaction.Type.GENERAL,
                        BankAccount.parse("1234567899/2700"),
                        BankAccount.parse("9876543211/0600"),
                        "WAYNE ENTERPRISES s.r.o.",
                        czk("20546.00"),
                        new PaymentSymbols("0002026017", null, null),
                        "Faktura 2026017",
                        LocalDate.of(2026, 5, 25),
                        null,
                        null,
                        "<0000@unicreditgroup.cz>"),
                read("incoming-synthetic.eml"));
    }

    @Test
    void takesAMinusForMoneyOut() throws IOException {
        // How an outgoing e-mail writes its direction is not known yet; this pins the rule the parser applies.
        Transaction read = BankNotifications.parseUnverified(
                bytes(text("incoming-synthetic.eml").replace("Částka: 20,546.00 CZK", "Částka: -20,546.00 CZK")));

        assertEquals(Direction.OUTGOING, read.direction());
        assertEquals(czk("-20546.00"), read.amount());
    }

    @Test
    void refusesAnAmountInAnotherCurrencyThanTheAccount() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("20,546.00 CZK", "20,546.00 EUR");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInThroughTheCounterPartysName() throws IOException {
        // A payment without a variable symbol still prints its label, empty; the forged one makes a second.
        String changed = text("incoming-synthetic.eml")
                .replace("Variabilní symbol: 0002026017<br/>", "Variabilní symbol: <br/>")
                .replace("WAYNE ENTERPRISES s.r.o.<br/>", "EVIL<br/>\r\nVariabilní symbol: 42<br/>");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void readsAPaymentWithoutAVariableSymbol() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("Variabilní symbol: 0002026017<br/>", "Variabilní symbol: <br/>");

        assertEquals(
                PaymentSymbols.NONE,
                BankNotifications.parseUnverified(bytes(changed)).symbols());
    }

    @Test
    void refusesATemplateWithoutTheVariableSymbolLine() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("Variabilní symbol: 0002026017<br/>", "");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheDetails() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("Specifický symbol: <br/>", "")
                .replace("Faktura 2026017<br/>", "x<br/>\r\nSpecifický symbol: 42<br/>");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = UnicreditTest.class.getResourceAsStream("/unicredit-cz/" + name)) {
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
