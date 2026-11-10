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
 * The fixtures are <b>synthetic</b> HTML e-mails: their cells are the sanitised samples in radekhulan/myinvoice's
 * Raiffeisenbank parser test (MIT), one per row, with this repository's fake accounts (the samples' failed the modulo
 * 11 check); the markup and the headers are written here. No real Raiffeisenbank e-mail, nor its HTML, has been seen.
 */
class RaiffeisenbankTest {

    @Test
    void readsAnIncomingPaymentFromHtml() throws IOException {
        assertEquals(
                new Transaction(
                        "rb.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/5500", BankAccount.Country.CZ),
                        BankAccount.parse("9876543211/0100", BankAccount.Country.CZ),
                        "Pennyworth Alfred",
                        czk("1234.56"),
                        new PaymentSymbols("2606001", "308", null),
                        "Faktura 2606001",
                        LocalDate.of(2026, 6, 1),
                        null,
                        new Balance(
                                czk("99999.99"),
                                ZonedDateTime.of(2026, 6, 1, 10, 15, 0, 0, ZoneId.of("Europe/Prague"))),
                        "<0000@rb.cz>"),
                read("incoming-synthetic.eml"));
    }

    @Test
    void readsACardPaymentWithItsDetailsAsTheMessage() throws IOException {
        Transaction card = read("card-synthetic.eml");

        assertEquals(Transaction.Type.CARD, card.type());
        assertEquals(Direction.OUTGOING, card.direction());
        assertEquals(czk("-1234.56"), card.amount());
        assertEquals(BankAccount.parse("1234567899/5500", BankAccount.Country.CZ), card.account());
        assertNull(card.counterAccount());
        assertEquals("DEMO SOFTWARE; PRAHA; CZE", card.message());
    }

    @Test
    void refusesASentenceTheAmountsSignDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("+1.234,56 CZK", "-1.234,56 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheMessage() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("<tr><td>Variabilní symbol</td></tr><tr><td>2606001</td></tr>", "")
                .replace(
                        "<tr><td>Faktura 2606001</td></tr>",
                        "<tr><td>x</td></tr><tr><td>Variabilní symbol</td></tr><tr><td>42</td></tr>");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountSmuggledInAfterTheMessage() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace(
                        "<tr><td>Faktura 2606001</td></tr>",
                        "<tr><td>x</td></tr><tr><td>Částka v měně účtu</td></tr><tr><td>+99.999,00 CZK</td></tr>");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void takesNoLineFromAPayersLineBreakInsideTheMessage() throws IOException {
        // In HTML a line break in text is whitespace: the forged label stays inside the message.
        String changed = text("incoming-synthetic.eml")
                .replace("<tr><td>Variabilní symbol</td></tr><tr><td>2606001</td></tr>", "")
                .replace("Faktura 2606001", "x\r\nVariabilní symbol\r\n42");

        Transaction read = BankNotifications.parseUnverified(bytes(changed));

        assertNull(read.symbols().variable());
        assertEquals("x Variabilní symbol 42", read.message());
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = RaiffeisenbankTest.class.getResourceAsStream("/raiffeisenbank/" + name)) {
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
