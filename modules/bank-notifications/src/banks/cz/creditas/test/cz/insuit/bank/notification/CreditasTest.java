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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The fixtures are <b>synthetic</b>: their bodies are the sanitised samples in radekhulan/myinvoice's Creditas parser
 * test (MIT), with this repository's fake accounts (the sample's counter-account failed the modulo 11 check, a sign
 * those samples were typed rather than copied); the headers are written here. No real Creditas e-mail has been seen.
 */
class CreditasTest {

    @Test
    void readsAnIncomingPaymentFromItsSentenceAndList() throws IOException {
        assertEquals(
                new Transaction(
                        "creditas.cz",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parse("1234567899/2250"),
                        BankAccount.parse("9876543211/0800"),
                        null,
                        czk("1000.00"),
                        new PaymentSymbols("123456", null, null),
                        null,
                        LocalDate.of(2026, 6, 23),
                        null,
                        new Balance(
                                czk("97163.53"),
                                ZonedDateTime.of(2026, 6, 23, 18, 43, 0, 0, ZoneId.of("Europe/Prague"))),
                        "<0000@creditas.cz>"),
                read("incoming-synthetic.eml"));
    }

    @Test
    void refusesACardHoldWhichIsNotABookedPayment() {
        assertThrows(Unparsed.class, () -> read("card-hold-synthetic.eml"));
    }

    @Test
    void refusesAnAmountTheListDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("- částka: 1 000,00 CZK", "- částka: 9 000,00 CZK");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnAccountTheListDisagreesWith() throws IOException {
        String changed = text("incoming-synthetic.eml")
                .replace("- změna na účtu: 1234567899/2250", "- změna na účtu: 1000000005/2250");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    @Test
    void refusesAnIncomingKindOnABalanceThatWentDown() throws IOException {
        String changed = text("incoming-synthetic.eml").replace("se zvýšil o částku", "se snížil o částku");

        assertThrows(Unparsed.class, () -> BankNotifications.parseUnverified(bytes(changed)));
    }

    private static Transaction read(String name) throws IOException {
        return BankNotifications.parseUnverified(fixture(name));
    }

    private static Money czk(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("CZK"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = CreditasTest.class.getResourceAsStream("/creditas/" + name)) {
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
