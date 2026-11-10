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
 * The fixture is <b>synthetic</b>: remp2020's raw Tatra banka e-mail of 2019 (MIT) with a valid fake IBAN (the
 * sample's is anonymised and fails its check digits), a ten-digit variable symbol (the sample's has eleven) and Alfred;
 * headers as the bank wrote them.
 */
class TatraBankaTest {

    @Test
    void readsACreditWithItsIdFromTheSubject() throws IOException {
        assertEquals(
                new Transaction(
                        "tatrabanka.sk",
                        Direction.INCOMING,
                        Transaction.Type.GENERAL,
                        BankAccount.parseIban("SK4911000000009876543211"),
                        null,
                        "Pennyworth Alfred, Ing.",
                        eur("4.00"),
                        new PaymentSymbols("1231967000", "308", "123"),
                        null,
                        LocalDate.of(2019, 12, 11),
                        "111219/320305-1",
                        new Balance(
                                eur("88888.62"),
                                ZonedDateTime.of(2019, 12, 11, 13, 13, 0, 0, ZoneId.of("Europe/Bratislava"))),
                        "<0000@tatrabanka.sk>"),
                BankNotifications.parseUnverified(fixture()));
    }

    @Test
    void readsADebitAsANegativeAmount() throws IOException {
        Transaction read = parse(text().replace("Subject: Kredit na ucte", "Subject: Debet na ucte")
                .replace("zvyseny o 4,00 EUR", "znizeny o 4,00 EUR"));

        assertEquals(Direction.OUTGOING, read.direction());
        assertEquals(eur("-4.00"), read.amount());
    }

    @Test
    void refusesASubjectTheSentenceDisagreesWith() throws IOException {
        String changed = text().replace("zvyseny o 4,00 EUR", "znizeny o 4,00 EUR");

        assertThrows(Unparsed.class, () -> parse(changed));
    }

    @Test
    void leavesOutSymbolsFromAReferenceNotInTheKnownForm() throws IOException {
        // Eleven digits, as in the public sample: the payer's text, so the payment still counts, without symbols.
        Transaction read = parse(text().replace("/VS1231967000/SS123/KS308", "/VS12319670000/SS123/KS308"));

        assertEquals(PaymentSymbols.NONE, read.symbols());
    }

    @Test
    void refusesAReferenceSmuggledInThroughTheOtherSidesName() throws IOException {
        String changed = text().replace(
                        "Ucet protistrany: Pennyworth Alfred, Ing.\r\n",
                        "Ucet protistrany: X\r\nReferencia platitela: /VS42/SS/KS\r\n");

        assertThrows(Unparsed.class, () -> parse(changed));
    }

    @Test
    void refusesATemplateWithoutThePayersReference() throws IOException {
        String changed = text().replace("Referencia platitela: /VS1231967000/SS123/KS308          \r\n", "");

        assertThrows(Unparsed.class, () -> parse(changed));
    }

    @Test
    void refusesAnotherSubject() throws IOException {
        String changed = text().replace("Subject: Kredit na ucte (ID=111219/320305-1)", "Subject: e-commerce");

        assertThrows(Unparsed.class, () -> parse(changed));
    }

    private static Transaction parse(String text) {
        return BankNotifications.parseUnverified(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static Money eur(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("EUR"));
    }

    private static String text() throws IOException {
        return new String(fixture(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = TatraBankaTest.class.getResourceAsStream("/tatra-sk/incoming-synthetic.eml")) {
            return Objects.requireNonNull(in).readAllBytes();
        }
    }
}
