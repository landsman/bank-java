package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        balance("12345.67", 2026, 10, 11, 18, 29),
                        "<0000.0.0.JavaMail.ums@sap.private.fio.cz>"),
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
                        balance("51577.67", 2026, 11, 6, 14, 8),
                        "<0000.0.0.JavaMail.ums@sap.private.fio.cz>"),
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
    void refusesTheShortTemplateAsAFormatTheAccountHolderCanChange() {
        // No transaction id to deduplicate on, and no currency.
        Unparsed refused = assertThrows(Unparsed.class, () -> read("incoming-short.eml"));

        assertEquals(Unparsed.Reason.UNSUPPORTED_FORMAT, refused.reason());
        assertTrue(String.valueOf(refused.getMessage()).contains("Fio banka - příjem na účtu"), refused.getMessage());
    }

    @Test
    void refusesASubjectItDoesNotKnowAsNotRead() throws IOException {
        String changed = text("incoming-instant.eml")
                .replace(
                        "Subject: =?UTF-8?Q?Fio_banka_-_p=C5=99=C3=ADjem_na_=C3=BA=C4=8Dtu?=",
                        "Subject: Fio banka - novinka");

        Unparsed refused = assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));

        assertEquals(Unparsed.Reason.NOT_READ, refused.reason());
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
    void refusesASymbolSmuggledInThroughThePayersName() throws IOException {
        // The counter-party's name, in parentheses, is free text; a line break in it must not add a line.
        String changed = text("incoming-transfer.eml")
                .replace("Variabiln=C3=AD symbol: 20260042 =20\r\n", "")
                .replace(
                        "(wayne enterprises s.r.o.)",
                        "(evil)\r\nVariabiln=C3=AD symbol: 42\r\n(wayne enterprises s.r.o.)");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    /** The same separators as in {@code AirBankTest}, quoted-printable here, as Fio sends its body. */
    @ParameterizedTest
    @ValueSource(strings = {"=0D", "=0A", "=C2=85", "=E2=80=A8", "=E2=80=A9", "=C2=A0", "=0B", "=0C"})
    void takesNoSymbolFromBehindASeparatorInTheMessage(String separator) throws IOException {
        String changed = text("incoming-transfer.eml")
                .replace("Variabiln=C3=AD symbol: 20260042 =20\r\n", "")
                .replace("WAYNE ENTERPRISES S R O=20", "x" + separator + "Variabiln=C3=AD symbol: 42");

        AirBankTest.assertNoForgedSymbol(bytes(changed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=0D", "=0A", "=C2=85", "=E2=80=A8", "=E2=80=A9", "=C2=A0", "=0B", "=0C"})
    void takesNoSymbolFromBehindASeparatorInThePayersName(String separator) throws IOException {
        String changed = text("incoming-transfer.eml")
                .replace("Variabiln=C3=AD symbol: 20260042 =20\r\n", "")
                .replace(
                        "(wayne enterprises s.r.o.)",
                        "(evil)" + separator + "Variabiln=C3=AD symbol: 42" + separator + "(wayne)");

        AirBankTest.assertNoForgedSymbol(bytes(changed));
    }

    @Test
    void takesAnEmptyLabelForAnAbsentField() throws IOException {
        // Fio's short template prints "SS: " with nothing after it; a symbol line left empty means no symbol.
        String changed = text("incoming-transfer.eml")
                .replace(
                        "Konstantn=C3=AD symbol: 0\r\n", "Konstantn=C3=AD symbol: 0\r\nSpecifick=C3=BD symbol:=20\r\n");

        assertEquals(
                new PaymentSymbols("20260042", "0", null),
                BankNotifications.parseUnverified(bytes(changed)).symbols());
    }

    @Test
    void stillRefusesAnEmptyLabelThatAppearsTwice() throws IOException {
        String changed = text("incoming-transfer.eml")
                .replace(
                        "Konstantn=C3=AD symbol: 0\r\n",
                        "Konstantn=C3=AD symbol: 0\r\nSpecifick=C3=BD symbol:=20\r\nSpecifick=C3=BD symbol: 7\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    /** Synthetic: myinvoice's sanitised English sample (MIT), with this repository's accounts and id. */
    @Test
    void readsTheEnglishTemplate() throws IOException {
        assertEquals(
                new Transaction(
                        "fio.cz",
                        Direction.INCOMING,
                        Transaction.Type.GENERAL,
                        BankAccount.parse("1234567899/2010"),
                        BankAccount.parse("9876543211/2010"),
                        "Pennyworth, Alfred",
                        czk("1.00"),
                        new PaymentSymbols("123456", null, null),
                        "Faktura 123456",
                        LocalDate.of(2026, 10, 25),
                        "10000000006",
                        balance("1234.75", 2026, 10, 25, 10, 37),
                        "<0000.0.0.JavaMail.ums@sap.private.fio.cz>"),
                read("english-incoming-synthetic.eml"));
    }

    @Test
    void readsTheEnglishDateMonthFirst() throws IOException {
        // 3/4 is the 4th of March, not the 3rd of April.
        String changed =
                text("english-incoming-synthetic.eml").replace("on 10/25/2026 at 10:37", "on 3/4/2026 at 8:05");

        assertEquals(
                LocalDate.of(2026, 3, 4),
                BankNotifications.parseUnverified(bytes(changed)).bookedOn());
    }

    @Test
    void refusesAnEnglishSubjectTheSentenceDisagreesWith() throws IOException {
        String changed = text("english-incoming-synthetic.eml").replace("has increased by", "has decreased by");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void readsTheCzechTemplateUnderASubjectThatStatesNoDirection() throws IOException {
        String changed = text("incoming-instant.eml")
                .replace(
                        "Subject: =?UTF-8?Q?Fio_banka_-_p=C5=99=C3=ADjem_na_=C3=BA=C4=8Dtu?=",
                        "Subject: Fio banka - zmena zustatku");

        assertEquals(
                Direction.INCOMING,
                BankNotifications.parseUnverified(bytes(changed)).direction());
    }

    @Test
    void readsADateWrittenWithSpacesAndWithoutLeadingZeros() throws IOException {
        String changed = text("incoming-instant.eml").replace("se 11.10.2026 v 18:29", "se 1. 9. 2026 v 8:05");

        Transaction read = BankNotifications.parseUnverified(bytes(changed));

        assertEquals(LocalDate.of(2026, 9, 1), read.bookedOn());
    }

    @Test
    void refusesTheOutgoingShortTemplateAsAFormatTheAccountHolderCanChange() throws IOException {
        String changed = text("incoming-short.eml")
                .replace("Subject: Fio banka - prijem na konte", "Subject: Fio banka - vydaj na konte");

        Unparsed refused = assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));

        assertEquals(Unparsed.Reason.UNSUPPORTED_FORMAT, refused.reason());
    }

    @Test
    void takesAnOutgoingPaymentForATransfer() throws IOException {
        String changed = text("outgoing-transfer.eml").replace("Bezhotovostn=C3=AD platba", "Odchoz=C3=AD platba");

        assertEquals(
                Transaction.Type.TRANSFER,
                BankNotifications.parseUnverified(bytes(changed)).type());
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
