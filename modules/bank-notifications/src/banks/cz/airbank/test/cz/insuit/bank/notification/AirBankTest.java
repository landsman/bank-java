package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fixtures are three real Air Bank e-mails from 2026-11-10, a personal account, with the accounts, names, balance,
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
                        BankAccount.parse("1234567899/3030", Country.CZ),
                        BankAccount.parse("9876543211/2010", Country.CZ),
                        "Novák, Jan",
                        czk("1.00"),
                        new PaymentSymbols("1337", null, null),
                        null,
                        LocalDate.of(2026, 11, 10),
                        "100000000001",
                        balance("512.30", 14, 39),
                        "<0000@localhost.localdomain>"),
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
                        BankAccount.parse("1234567899/3030", Country.CZ),
                        BankAccount.parse("9876543211/2010", Country.CZ),
                        null,
                        czk("-1.00"),
                        new PaymentSymbols("7331", null, null),
                        "test email airbank",
                        LocalDate.of(2026, 11, 10),
                        "100000000002",
                        balance("511.30", 14, 41),
                        "<0000@localhost.localdomain>"),
                parsed);
    }

    @Test
    void readsTheMessageAndAllThreeSymbolsOfAnIncomingPayment() throws IOException {
        Transaction parsed = BankNotifications.parseUnverified(fixture("incoming-message.eml"));

        assertEquals(
                new Transaction(
                        "airbank.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/3030", Country.CZ),
                        BankAccount.parse("9876543211/2010", Country.CZ),
                        "Novák, Jan",
                        czk("1.00"),
                        new PaymentSymbols("1337", "1234", "123456789"),
                        "hello gotham!",
                        LocalDate.of(2026, 11, 10),
                        "100000000003",
                        balance("512.30", 18, 54),
                        "<0000@localhost.localdomain>"),
                parsed);
    }

    @Test
    void leavesOutABalanceInAFormatNotSeenYet() throws IOException {
        // A no-break space between thousands has not been seen; a plain one has, in public samples.
        String changed = text("incoming.eml").replace("je 512,30 CZK.", "je 1\u00a0512,30 CZK.");

        Transaction parsed = BankNotifications.parseUnverified(bytes(changed));

        assertNull(parsed.balance());
        assertEquals(czk("1.00"), parsed.amount());
    }

    @Test
    void keepsThePayerTheMessageAndTheBalanceOutOfToString() throws IOException {
        String logged = BankNotifications.parseUnverified(fixture("incoming-message.eml"))
                .toString();

        assertFalse(logged.contains("hello gotham"), logged);
        assertFalse(logged.contains("9876543211"), logged);
        assertFalse(logged.contains("Novák"), logged);
        assertFalse(logged.contains("512.3"), logged);
    }

    @Test
    void refusesAnAccountThatFailsItsChecksum() throws IOException {
        String misread = text("incoming.eml").replace("9876543211/2010", "9876543212/2010");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(misread)));
    }

    @Test
    void refusesAnotherSenderAsNotABankEmail() throws IOException {
        String forged = text("incoming.eml").replace("info@airbank.cz", "info@airbank.cz.example.com");

        Unparsed refused = assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(forged)));

        assertEquals(Unparsed.Reason.NOT_A_BANK_EMAIL, refused.reason());
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
        String changed = text("incoming.eml").replace("1,00 CZK", "1\u00a0000,00 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    /** myinvoice's samples (MIT) show thousands separated by a plain space; no real e-mail of 1 000 or more yet. */
    @Test
    void readsThousandsSeparatedByASpace() throws IOException {
        String changed =
                text("incoming.eml").replace("1,00 CZK", "1 000,00 CZK").replace("je 512,30 CZK.", "je 9 512,30 CZK.");

        Transaction parsed = BankNotifications.parseUnverified(bytes(changed));

        assertEquals(czk("1000.00"), parsed.amount());
        assertEquals(czk("9512.30"), Objects.requireNonNull(parsed.balance()).available());
    }

    @Test
    void readsAnEurAccount() throws IOException {
        String changed = text("incoming.eml").replace(" CZK", " EUR");

        assertEquals(
                Currency.getInstance("EUR"),
                BankNotifications.parseUnverified(bytes(changed)).amount().currency());
    }

    /** myinvoice's outgoing sample names the recipient; the real one, without a saved name, does not. */
    @Test
    void readsTheRecipientsNameOfAnOutgoingPaymentWhenTheBankPrintsIt() throws IOException {
        String changed = text("outgoing.eml")
                .replace("Odchozí úhrada na účet číslo", "Odchozí úhrada na účet Wayne Enterprises s.r.o. číslo");

        Transaction parsed = BankNotifications.parseUnverified(bytes(changed));

        assertEquals("Wayne Enterprises s.r.o.", parsed.counterName());
        assertEquals(BankAccount.parse("9876543211/2010", Country.CZ), parsed.counterAccount());
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
    void refusesASymbolThatIsNotDigits() throws IOException {
        String changed = text("incoming-message.eml").replace("Konstantní symbol: 1234", "Konstantní symbol: 12a4");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheIncomingMessage() throws IOException {
        String changed = text("incoming-message.eml")
                .replace("Specifický symbol: 123456789\r\n", "")
                .replace(
                        "Zpráva pro příjemce: hello gotham!\r\n",
                        "Zpráva pro příjemce: x\r\nSpecifický symbol: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInThroughThePayersName() throws IOException {
        // The payer's account name is free text too. A line break in it could carry a counter-party line of its own,
        // then a variable symbol, before the real line it was cut out of.
        String changed = text("incoming.eml")
                .replace("Variabilní symbol: 1337\r\n", "")
                .replace(
                        "Příchozí úhrada z účtu Novák, Jan číslo",
                        "Příchozí úhrada z účtu Evil číslo 5555555002/0800\r\nVariabilní symbol: 42\r\nNovák, Jan číslo");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    /**
     * Line separators a payer might get through in their message or account name: a lone CR splits a line for
     * {@code String.lines()}, NEL and U+2028 do not but stop a regex's {@code .}, NBSP only looks like a space. Whatever
     * the parser does with them, the symbol written after one must not become the payment's.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\u0085", "\u2028", "\u2029", "\u00a0", "\u000b", "\u000c"})
    void takesNoSymbolFromBehindASeparatorInTheMessage(String separator) throws IOException {
        String changed = text("outgoing.eml")
                .replace("Variabilní symbol: 7331\r\n", "")
                .replace(
                        "Zpráva pro plátce: test email airbank",
                        "Zpráva pro plátce: x" + separator + "Variabilní symbol: 42");

        assertNoForgedSymbol(bytes(changed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0085", "\u2028", "\u2029", "\u00a0", "\u000b", "\u000c"})
    void keepsAMessageWithASeparatorThatDoesNotEndALine(String separator) throws IOException {
        String changed = text("outgoing.eml").replace("test email airbank", "faktura" + separator + "2026-001");

        assertEquals(
                "faktura" + separator + "2026-001",
                BankNotifications.parseUnverified(bytes(changed)).message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\u0085", "\u2028", "\u2029", "\u00a0", "\u000b", "\u000c"})
    void takesNoSymbolFromBehindASeparatorInThePayersName(String separator) throws IOException {
        String changed = text("incoming.eml")
                .replace("Variabilní symbol: 1337\r\n", "")
                .replace(
                        "Novák, Jan číslo",
                        "Evil číslo 5555555002/0800" + separator + "Variabilní symbol: 42" + separator
                                + "Novák, Jan číslo");

        assertNoForgedSymbol(bytes(changed));
    }

    @Test
    void reportsAMessageThatIsNoEmail() {
        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(new byte[0]));
    }

    private static Balance balance(String available, int hour, int minute) {
        return new Balance(
                czk(available), ZonedDateTime.of(2026, 11, 10, hour, minute, 0, 0, ZoneId.of("Europe/Prague")));
    }

    /** Refused, or read without the forged symbol: both are safe, a forged symbol is not. */
    static void assertNoForgedSymbol(byte[] message) {
        Transaction read;
        try {
            read = BankNotifications.parseUnverified(message);
        } catch (Unparsed refused) {
            return;
        }
        assertNotEquals("42", read.symbols().variable(), read.toString());
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
