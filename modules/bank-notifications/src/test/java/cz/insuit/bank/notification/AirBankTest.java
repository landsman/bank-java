package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The fixtures are two real Air Bank e-mails from 2026-11-10, a personal account, with the accounts, names, balance,
 * transaction codes and recipient replaced and the transport headers and DKIM signature removed. Everything else,
 * CRLF line ends and the U+FEFF after the outgoing transaction code included, is byte for byte what the bank sent.
 */
class AirBankTest {

    @Test
    void readsAnIncomingPayment() throws IOException {
        Transaction parsed = BankNotifications.parseUnverified(fixture("incoming.eml"));

        assertEquals(
                new Transaction(
                        "airbank.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/3030"),
                        BankAccount.parse("9876543211/2010"),
                        "Novák, Jan",
                        czk("1.00"),
                        new PaymentSymbols("1337", null, null),
                        null,
                        LocalDate.of(2026, 11, 10),
                        "100000000001"),
                parsed);
    }

    @Test
    void readsAnOutgoingPaymentAsANegativeAmount() throws IOException {
        Transaction parsed = BankNotifications.parseUnverified(fixture("outgoing.eml"));

        assertEquals(
                new Transaction(
                        "airbank.cz",
                        Direction.OUTGOING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/3030"),
                        BankAccount.parse("9876543211/2010"),
                        null,
                        czk("-1.00"),
                        new PaymentSymbols("7331", null, null),
                        "test email airbank",
                        LocalDate.of(2026, 11, 10),
                        "100000000002"),
                parsed);
    }

    @Test
    void keepsThePayerAndTheMessageOutOfToString() throws IOException {
        String logged =
                BankNotifications.parseUnverified(fixture("outgoing.eml")).toString();

        assertFalse(logged.contains("test email airbank"), logged);
        assertFalse(logged.contains("9876543211"), logged);
    }

    @Test
    void refusesAnAccountThatFailsItsChecksum() throws IOException {
        String misread = text("incoming.eml").replace("9876543211/2010", "9876543212/2010");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(misread)));
    }

    @Test
    void refusesAnotherSender() throws IOException {
        String forged = text("incoming.eml").replace("info@airbank.cz", "info@airbank.cz.example.com");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(forged)));
    }

    @Test
    void refusesADirectionTheTwoLinesDisagreeOn() throws IOException {
        String changed = text("incoming.eml").replace("se zvýšil", "se snížil");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountThatDiffersFromTheSummary() throws IOException {
        String changed = text("incoming.eml").replace("Částka: 1,00 CZK", "Částka: 100,00 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountInAFormatNotSeenYet() throws IOException {
        String changed = text("incoming.eml").replace("1,00 CZK", "1 000,00 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesALabelThatAppearsTwice() throws IOException {
        String changed = text("incoming.eml")
                .replace("Variabilní symbol: 1337\r\n", "Variabilní symbol: 1337\r\nVariabilní symbol: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheMessage() throws IOException {
        // An outgoing payment without its own VS, and a message that carries one on a line of its own.
        String changed = text("outgoing.eml")
                .replace("Variabilní symbol: 7331\r\n", "")
                .replace(
                        "Zpráva pro plátce: test email airbank\r\n",
                        "Zpráva pro plátce: x\r\nVariabilní symbol: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void reportsAMessageThatIsNoEmail() {
        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(new byte[0]));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = AirBankTest.class.getResourceAsStream("/airbank/" + name)) {
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
