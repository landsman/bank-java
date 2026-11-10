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
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are <b>synthetic</b>: their bodies follow tomaj/bank-mails-parser's ČSOB CEB fixtures
 * ({@code dosla-platba}, {@code viac-transakcii}; MIT), with this repository's fake accounts, the samples' failing the
 * modulo 11 check and naming bank code 9999; the headers are written here. No real ČSOB e-mail has been seen.
 */
class CsobTest {

    @Test
    void readsOneBookedPayment() throws IOException {
        assertEquals(
                new Transaction(
                        "csob.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/0300", BankAccount.Country.CZ),
                        BankAccount.parse("9876543211/0800", BankAccount.Country.CZ),
                        "Pennyworth Alfred",
                        czk("1234.56"),
                        new PaymentSymbols("23456789", "3456", null),
                        null,
                        LocalDate.of(2018, 9, 25),
                        null,
                        null,
                        "<0000@csob.cz>"),
                BankNotifications.parseUnverified(fixture("incoming-synthetic.eml")));
    }

    @Test
    void readsACardPaymentWithoutACounterAccount() throws IOException {
        Transaction card = BankNotifications.parseUnverified(fixture("card-synthetic.eml"));

        assertEquals(Transaction.Type.CARD, card.type());
        assertEquals(czk("1234.56"), card.amount());
        assertEquals(null, card.counterAccount());
        assertEquals(new PaymentSymbols("23456789", "3456", "4545454545"), card.symbols());
    }

    @Test
    void readsEveryTransactionOfAnEMailThatListsSeveral() throws IOException {
        List<Transaction> all = BankNotifications.parseAllUnverified(fixture("two-transactions-synthetic.eml"));

        assertEquals(2, all.size());
        assertEquals(czk("1234.56"), all.get(0).amount());
        assertEquals(czk("987.65"), all.get(1).amount());
        assertEquals(
                BankAccount.parse("5555555002/0100", BankAccount.Country.CZ),
                all.get(1).counterAccount());
        assertEquals(all.get(0).messageId(), all.get(1).messageId());
    }

    @Test
    void refusesSeveralTransactionsWhereOneTransactionIsAsked() {
        assertThrows(
                Unparsed.class, () -> BankNotifications.parseUnverified(fixture("two-transactions-synthetic.eml")));
    }

    @Test
    void refusesBalancesThatDoNotChain() throws IOException {
        String changed = text("two-transactions-synthetic.eml").replace("+1 235 555,54 CZK.", "+1 235 555,55 CZK.");

        assertThrows(Unparsed.class, () -> BankNotifications.parseAllUnverified(bytes(changed)));
    }

    @Test
    void refusesSeveralTransactionsWhenOneHasNoBalance() throws IOException {
        String changed = text("two-transactions-synthetic.eml")
                .replace("Zůstatek na účtu po zaúčtování transakce: +1 235 555,54 CZK.\r\n", "");

        assertThrows(Unparsed.class, () -> BankNotifications.parseAllUnverified(bytes(changed)));
    }

    @Test
    void refusesATransactionForgedInsideThePayersMessage() throws IOException {
        // A whole block in a message: the forged amount does not add up to the balance printed after it.
        String forged = """
                Zpráva příjemci: x

                Dne 25.9.2018 byla na účtu 1234567899 zaúčtovaná transakce typu: Došlá platba.
                Účet: 1234567899, CZK, WAYNE ENTERPRIS
                Částka: +99 999,00 CZK
                """.replace("\n", "\r\n");
        String changed = text("incoming-synthetic.eml")
                .replace("Konstantní symbol: 3456\r\n", "Konstantní symbol: 3456\r\n" + forged);

        assertThrows(Unparsed.class, () -> BankNotifications.parseAllUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInAfterThePayersMessage() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("Variabilní symbol: 23456789\r\n", "")
                .replace(
                        "Konstantní symbol: 3456\r\n",
                        "Konstantní symbol: 3456\r\nZpráva příjemci: x\r\nVariabilní symbol: 42\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnIncomingKindWithANegativeAmount() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("Částka: +1 234,56 CZK", "Částka: -1 234,56 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASentenceAccountTheAccountLineDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("Účet: 1234567899, CZK, WAYNE ENTERPRIS", "Účet: 9876543211, CZK, WAYNE ENTERPRIS");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAmountInAnotherCurrencyThanTheAccount() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("Částka: +1 234,56 CZK", "Částka: +1 234,56 EUR");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    /** Synthetic: myinvoice's sanitised samples of "Moje info - Avízo" (MIT), with this repository's accounts. */
    @Test
    void readsTheOwnBankingNotice() throws IOException {
        assertEquals(
                new Transaction(
                        "csob.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/0300", BankAccount.Country.CZ),
                        BankAccount.parse("9876543211/2700", BankAccount.Country.CZ),
                        "WAYNE ENTERPRISES",
                        czk("10000.00"),
                        PaymentSymbols.NONE,
                        null,
                        LocalDate.of(2026, 5, 31),
                        null,
                        null,
                        "<0001@csob.cz>"),
                BankNotifications.parseUnverified(fixture("own-banking-incoming-synthetic.eml")));
    }

    @Test
    void readsAnOutgoingOwnBankingNoticeAndCutsOnlyTheZerosPaddingASymbol() throws IOException {
        Transaction out = BankNotifications.parseUnverified(fixture("own-banking-outgoing-synthetic.eml"));

        assertEquals(Direction.OUTGOING, out.direction());
        assertEquals(czk("-10000.00"), out.amount());
        assertEquals(new PaymentSymbols("9002567593", "1120", null), out.symbols());
    }

    @Test
    void keepsTheZerosThatBelongToAPaddedSymbol() throws IOException {
        String changed = text("own-banking-outgoing-synthetic.eml").replace("0009002567593", "0000002567593");

        assertEquals(
                "0002567593",
                BankNotifications.parseUnverified(bytes(changed)).symbols().variable());
    }

    @Test
    void refusesASymbolLongerThanTenThatIsNotZeroPadding() throws IOException {
        String changed = text("own-banking-outgoing-synthetic.eml").replace("0009002567593", "1009002567593");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnOwnBankingKindTheSignDisagreesWith() throws IOException {
        String changed = text("own-banking-incoming-synthetic.eml")
                .replace("Částka\r\n+10 000,00 CZK", "Částka\r\n-10 000,00 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesASymbolSmuggledInThroughTheOwnBankingCounterPartysName() throws IOException {
        String changed = text("own-banking-incoming-synthetic.eml")
                .replace("WAYNE ENTERPRISES\r\n", "EVIL\r\nVariabilní symbol\r\n42\r\nX\r\n");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = CsobTest.class.getResourceAsStream("/csob-cz/" + name)) {
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
