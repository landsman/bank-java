package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cz.insuit.bank.notification.Transaction.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
        Notification parsed = BankNotifications.parseUnverified(fixture("incoming.eml"));

        assertEquals(
                new Transaction(
                        "airbank.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        "1234567890/3030",
                        "9876543210/2010",
                        "Novák, Jan",
                        100,
                        "CZK",
                        "1337",
                        null,
                        LocalDate.of(2026, 11, 10),
                        "100000000001"),
                parsed);
    }

    @Test
    void readsAnOutgoingPayment() throws IOException {
        Notification parsed = BankNotifications.parseUnverified(fixture("outgoing.eml"));

        assertEquals(
                new Transaction(
                        "airbank.cz",
                        Direction.OUTGOING,
                        Transaction.Type.TRANSFER,
                        "1234567890/3030",
                        "9876543210/2010",
                        null,
                        100,
                        "CZK",
                        "7331",
                        "test email airbank",
                        LocalDate.of(2026, 11, 10),
                        "100000000002"),
                parsed);
    }

    @Test
    void refusesAnotherSender() throws IOException {
        String forged = text("incoming.eml").replace("info@airbank.cz", "info@airbank.cz.example.com");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(forged)));
    }

    @Test
    void refusesADirectionTheTwoLinesDisagreeOn() throws IOException {
        String changed = text("incoming.eml").replace("se zvýšil", "se snížil");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountThatDiffersFromTheSummary() throws IOException {
        String changed = text("incoming.eml").replace("Částka: 1,00 CZK", "Částka: 100,00 CZK");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountInAFormatNotSeenYet() throws IOException {
        String changed = text("incoming.eml").replace("1,00 CZK", "1 000,00 CZK");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesALabelThatAppearsTwice() throws IOException {
        String changed = text("incoming.eml")
                .replace("Variabilní symbol: 1337\r\n", "Variabilní symbol: 1337\r\nVariabilní symbol: 42\r\n");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheMessage() throws IOException {
        // An outgoing payment without its own VS, and a message that carries one on a line of its own.
        String changed = text("outgoing.eml")
                .replace("Variabilní symbol: 7331\r\n", "")
                .replace(
                        "Zpráva pro plátce: test email airbank\r\n",
                        "Zpráva pro plátce: x\r\nVariabilní symbol: 42\r\n");

        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void reportsAMessageThatIsNoEmail() {
        assertInstanceOf(Unparsed.class, BankNotifications.parseUnverified(new byte[0]));
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
