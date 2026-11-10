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
 * The fixtures are <b>synthetic</b>: their bodies are the sanitised samples in radekhulan/myinvoice's MONETA parser
 * test (MIT), with this repository's fake accounts and Wayne Enterprises; the headers are written here. No real MONETA
 * e-mail has been seen yet, so these pin what the parser does with that template, not that MONETA sends it.
 */
class MonetaTest {

    @Test
    void readsAnIncomingPaymentWithoutAccountOrTransactionId() throws IOException {
        assertEquals(
                new Transaction(
                        "moneta.cz",
                        Direction.INCOMING,
                        Transaction.Type.GENERAL,
                        null,
                        BankAccount.parse("9876543211/0300", Country.CZ),
                        null,
                        czk("35000.00"),
                        new PaymentSymbols("202600009", null, null),
                        "OKAMŽITÁ ÚHRADA, DOM-AVIZO:202600009 WAYNE ENTERPRISES S.R.O., VS: 202600009",
                        LocalDate.of(2026, 7, 1),
                        null,
                        null,
                        "<0000@moneta.cz>"),
                read("incoming-synthetic.eml"));
    }

    @Test
    void readsAnOutgoingPaymentAsANegativeAmount() throws IOException {
        Transaction parsed = read("outgoing-synthetic.eml");

        assertEquals(Direction.OUTGOING, parsed.direction());
        assertEquals(czk("-1250.50"), parsed.amount());
        assertEquals("2607001", parsed.symbols().variable());
    }

    @Test
    void refusesAHeadingTheSubjectDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("\r\nPřišly peníze\r\n", "\r\nOdešly peníze\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASecondVariableSymbolInTheDescription() throws IOException {
        // The description may carry the payer's text; with two symbols there, neither can be trusted as the bank's.
        String changed = text("incoming-synthetic.eml").replace("VS: 202600009\r\n", "VS: 202600009 VS: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountSmuggledInAfterTheDescription() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("VS: 202600009\r\n", "VS: 202600009\r\nČástka:\r\n99 999,00 Kč\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnotherCurrencyRatherThanGuessingIt() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("35 000,00 Kč", "35 000,00 EUR");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MonetaTest.class.getResourceAsStream("/moneta/" + name)) {
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
