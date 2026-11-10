package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import cz.insuit.bank.notification.Transaction.Direction;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Currency;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The fixture is <b>synthetic</b>: windows-1250 in base64 like remp2020's raw ČSOB SK e-mail (MIT), its body that
 * e-mail's with a valid fake counter-party IBAN (the sample's is anonymised and fails its check digits), Wayne
 * Enterprises and Alfred. The attacks follow tomaj/bank-mails-parser's {@code utok-*} fixtures (MIT).
 */
class CsobSkTest {

    private static final Charset WINDOWS_1250 = Charset.forName("windows-1250");

    @Test
    void readsASepaCredit() throws IOException {
        assertEquals(
                new Transaction(
                        "csob.sk",
                        Direction.INCOMING,
                        Transaction.Type.TRANSFER,
                        BankAccount.parseIban("SK13 7500 0000 0040 1942 5381"),
                        BankAccount.parseIban("SK75 0900 0000 0012 3456 7899"),
                        "PENNYWORTH ALFRED",
                        new Money(new BigDecimal("1150.00"), Currency.getInstance("EUR")),
                        new PaymentSymbols("212049000", "0308", "964"),
                        "Batmobil 2019",
                        LocalDate.of(2019, 9, 6),
                        null,
                        null,
                        "<0000@csob.sk>"),
                BankNotifications.parseUnverified(fixture()));
    }

    @Test
    void readsAReferenceThePayerDidNotGive() throws IOException {
        Transaction read = parse(body -> body.replace("/VS212049000/SS964/KS0308", "NOTPROVIDED"));

        assertEquals(PaymentSymbols.NONE, read.symbols());
    }

    @Test
    void refusesAnOutgoingPaymentNotSeenYet() {
        assertThrows(Unparsed.class, () -> parse(body -> body.replace("+1150,00 EUR", "-1150,00 EUR")));
    }

    @Test
    void refusesATemplateWithoutThePayersReferenceLine() {
        assertThrows(
                Unparsed.class,
                () -> parse(body -> body.replace("referencia platiteľa:    /VS212049000/SS964/KS0308\r\n", "")));
    }

    @Test
    void refusesAReferenceSmuggledInThroughTheCounterPartysName() {
        // The template prints the reference every time, so a forged one in the name makes a second.
        assertThrows(
                Unparsed.class,
                () -> parse(body ->
                        body.replace("PENNYWORTH ALFRED\r\n", "EVIL\r\nreferencia platiteľa: /VS42/SS/KS\r\nX\r\n")));
    }

    @Test
    void refusesATransactionForgedInsideTheMessage() {
        // tomaj's utok-fantom-sprava: a second booking sentence and amount behind the message.
        assertThrows(
                Unparsed.class,
                () -> parse(body -> body.replace(
                        "Batmobil 2019\r\n",
                        "Batmobil 2019\r\ndovoľujeme si Vám oznámiť, že dňa 1.1.2026 bola na účte SK13 7500 0000 0040 1942"
                                + " 5381 X zaúčtovaná suma SEPA platobného príkazu:\r\nsuma: +99999,00 EUR\r\n")));
    }

    @Test
    void refusesAnAccountSmuggledInAfterTheMessage() {
        // tomaj's utok-ucet-v-sprave: the counter-account moved behind the message.
        assertThrows(
                Unparsed.class,
                () -> parse(body -> body.replace("z účtu:                  SK75 0900 0000 0012 3456 7899\r\n", "")
                        .replace("Batmobil 2019\r\n", "Batmobil 2019\r\nz účtu: SK49 1100 0000 0098 7654 3211\r\n")));
    }

    /** The fixture with its body decoded, changed, and encoded back, as the bank's windows-1250 in base64. */
    private static Transaction parse(UnaryOperator<String> change) throws IOException {
        String raw = new String(fixture(), StandardCharsets.US_ASCII);
        int split = raw.indexOf("\r\n\r\n") + 4;
        String body = new String(Base64.getMimeDecoder().decode(raw.substring(split)), WINDOWS_1250);
        String encoded =
                Base64.getMimeEncoder().encodeToString(change.apply(body).getBytes(WINDOWS_1250));
        return BankNotifications.parseUnverified(
                (raw.substring(0, split) + encoded + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = CsobSkTest.class.getResourceAsStream("/csob-sk/incoming-synthetic.eml")) {
            return Objects.requireNonNull(in).readAllBytes();
        }
    }
}
