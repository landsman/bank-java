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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are real Fio e-mails from August to November 2026, a personal account, with the accounts, names,
 * symbols, messages, balances, ids and recipient replaced and the transport headers and DKIM signature removed; the
 * card payment keeps its merchant. The body is quoted-printable as Fio sent it, re-encoded after the replacement with
 * the soft line breaks where Fio put them.
 */
class FioCzTest {

    @Test
    void readsAnInstantIncomingPayment() throws IOException {
        assertEquals(
                new Transaction(
                        "fio.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/2010"),
                        BankAccount.parse("5555555002/0800"),
                        "pennyworth alfred",
                        czk("1000.00"),
                        new PaymentSymbols(null, "0", null),
                        "batmobil / nahradni dily",
                        LocalDate.of(2026, 10, 11),
                        "10000000001",
                        balance("12345.67", 2026, 10, 11, 18, 29)),
                read("incoming-instant.eml"));
    }

    @Test
    void readsAnIncomingTransferWithItsVariableSymbol() throws IOException {
        Transaction parsed = read("incoming-transfer.eml");

        assertEquals(czk("42000.00"), parsed.amount());
        assertEquals(new PaymentSymbols("20260042", "0", null), parsed.symbols());
        assertEquals("wayne enterprises s.r.o.", parsed.counterName());
        assertEquals("WAYNE ENTERPRISES S R O", parsed.message());
    }

    @Test
    void readsAnOutgoingTransferAsANegativeAmount() throws IOException {
        assertEquals(
                new Transaction(
                        "fio.cz",
                        Direction.OUTGOING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/2010"),
                        BankAccount.parse("1939000008/5500"),
                        null,
                        czk("-2768.00"),
                        PaymentSymbols.NONE,
                        "Ošetření psa Ace",
                        LocalDate.of(2026, 11, 6),
                        "10000000003",
                        balance("51577.67", 2026, 11, 6, 14, 8)),
                read("outgoing-transfer.eml"));
    }

    @Test
    void readsACardPaymentWithoutACounterAccount() throws IOException {
        Transaction parsed = read("card.eml");

        assertEquals(Transaction.Type.CARD, parsed.type());
        assertEquals(czk("-500.00"), parsed.amount());
        assertEquals(null, parsed.counterAccount());
        assertEquals(
                "Nákup: Letec espresso bar,  Simackova 90817,, Hlavni mesto, 170 00, CZE, dne 7.11.2026, částka  500.00 CZK",
                parsed.message());
    }

    @Test
    void readsADirectDebit() throws IOException {
        Transaction parsed = read("direct-debit.eml");

        assertEquals(Transaction.Type.DIRECT_DEBIT, parsed.type());
        assertEquals(czk("-602.00"), parsed.amount());
        assertEquals(new PaymentSymbols("1000000042", "0308", null), parsed.symbols());
        assertEquals(BankAccount.parse("270000000/0300"), parsed.counterAccount());
    }

    @Test
    void takesAMovementTypeNotSeenYetForGeneral() throws IOException {
        String changed = text("incoming-instant.eml")
                .replace("Okam=C5=BEit=C3=A1 p=C5=99=C3=ADchoz=C3=AD platba", "Nov=C3=BD typ");

        assertEquals(
                Transaction.Type.GENERAL,
                BankNotifications.parseUnverified(bytes(changed)).type());
    }

    @Test
    void refusesTheShortTemplate() {
        // No transaction id to deduplicate on, and no currency.
        assertThrows(Unparsed.class, () -> read("incoming-short.eml"));
    }

    @Test
    void refusesADirectionTheSubjectDisagreesWith() throws IOException {
        String changed = text("incoming-instant.eml").replace("zv=C3=BD=C5=\r\n=A1il", "sn=C3=AD=C5=BEil");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterTheMessage() throws IOException {
        String changed = text("outgoing-transfer.eml")
                .replace("O=C5=A1et=C5=99en=C3=AD psa Ace=20\r\n", "x\r\nVariabiln=C3=AD symbol: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void keepsThePayerTheMessageAndTheBalanceOutOfToString() throws IOException {
        String logged = read("incoming-instant.eml").toString();

        assertFalse(logged.contains("pennyworth"), logged);
        assertFalse(logged.contains("batmobil"), logged);
        assertFalse(logged.contains("5555555002"), logged);
        assertFalse(logged.contains("12345.67"), logged);
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Balance balance(String available, int year, int month, int day, int hour, int minute) {
        return new Balance(
                czk(available), ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("Europe/Prague")));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = FioCzTest.class.getResourceAsStream("/fio-cz/" + name)) {
            return Objects.requireNonNull(in, name).readAllBytes();
        }
    }

    private static String text(String name) throws IOException {
        return new String(fixture(name), StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
