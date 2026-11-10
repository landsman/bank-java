package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class BankAccountTest {

    @Test
    void readsAnAccountWithAPrefix() {
        // The Czech National Bank's sample account in its own guidance.
        BankAccount account = BankAccount.parse("19-2000145399/0800", BankAccount.Country.CZ);

        assertEquals("19", account.prefix());
        assertEquals("2000145399", account.number());
        assertEquals("0800", account.bankCode());
        assertEquals("19-2000145399/0800", account.toString());
    }

    @Test
    void readsAnAccountWithoutAPrefix() {
        BankAccount account = BankAccount.parse("1234567899/3030", BankAccount.Country.CZ);

        assertNull(account.prefix());
        assertEquals("1234567899/3030", account.toString());
    }

    @Test
    void dropsLeadingZerosSoTheSameAccountIsEqual() {
        assertEquals(
                BankAccount.parse("000019-2000145399/0800", BankAccount.Country.CZ),
                BankAccount.parse("19-2000145399/0800", BankAccount.Country.CZ));
        assertEquals(
                BankAccount.parse("000000-1234567899/3030", BankAccount.Country.CZ),
                BankAccount.parse("1234567899/3030", BankAccount.Country.CZ));
    }

    @Test
    void refusesANumberThatFailsTheChecksum() {
        assertThrows(
                IllegalArgumentException.class, () -> BankAccount.parse("1234567890/3030", BankAccount.Country.CZ));
    }

    @Test
    void refusesAPrefixThatFailsTheChecksum() {
        assertThrows(
                IllegalArgumentException.class, () -> BankAccount.parse("18-2000145399/0800", BankAccount.Country.CZ));
    }

    @Test
    void refusesAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("1234567899", BankAccount.Country.CZ));
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("1234567899/30", BankAccount.Country.CZ));
        assertThrows(
                IllegalArgumentException.class,
                () -> BankAccount.parse("CZ6508000000192000145399", BankAccount.Country.CZ));
    }

    @Test
    void readsTheLocalFormOfAnotherCountry() {
        BankAccount account = BankAccount.parse("4019425381/7500", BankAccount.Country.SK);

        assertEquals(BankAccount.Country.SK, account.country());
        assertEquals("4019425381/7500", account.toString());
    }

    @Test
    void readsAnIbanWithOrWithoutSpaces() {
        // The Czech National Bank's sample account, as an IBAN.
        BankAccount spaced = BankAccount.parseIban("CZ65 0800 0000 1920 0014 5399");

        assertEquals(BankAccount.parse("19-2000145399/0800", BankAccount.Country.CZ), spaced);
        assertEquals(spaced, BankAccount.parseIban("cz6508000000192000145399"));
    }

    @Test
    void readsASlovakIban() {
        // A real company's account, printed in a public ČSOB SK sample.
        BankAccount account = BankAccount.parseIban("SK13 7500 0000 0040 1942 5381");

        assertEquals(BankAccount.Country.SK, account.country());
        assertNull(account.prefix());
        assertEquals("4019425381", account.number());
        assertEquals("7500", account.bankCode());
    }

    @Test
    void writesTheIban() {
        assertEquals(
                "CZ6508000000192000145399",
                BankAccount.parse("19-2000145399/0800", BankAccount.Country.CZ).iban());
        assertEquals(
                "SK1375000000004019425381",
                BankAccount.parse("4019425381/7500", BankAccount.Country.SK).iban());
    }

    @Test
    void writesAnIbanThatReadsBackAsTheSameAccount() {
        BankAccount account = BankAccount.parse("1234567899/3030", BankAccount.Country.CZ);

        assertEquals(account, BankAccount.parseIban(account.iban()));
    }

    @Test
    void keepsTheSameDigitsInTwoCountriesApart() {
        assertNotEquals(
                BankAccount.parse("4019425381/7500", BankAccount.Country.CZ),
                BankAccount.parse("4019425381/7500", BankAccount.Country.SK));
    }

    @Test
    void refusesAnIbanWhoseCheckDigitsDoNotHold() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parseIban("CZ66 0800 0000 1920 0014 5399"));
    }

    @Test
    void refusesAnIbanWhoseAccountFailsTheModulo11Rule() {
        // Check digits recomputed for a number that fails modulo 11: the IBAN alone would pass.
        String bban = "0800" + "000019" + "2000145398";
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parseIban(validIban("CZ", bban)));
    }

    @Test
    void refusesAnIbanOfAnotherCountry() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parseIban("DE89 3704 0044 0532 0130 00"));
    }

    private static String validIban(String country, String bban) {
        BigInteger value =
                new BigInteger(bban + (country.charAt(0) - 'A' + 10) + (country.charAt(1) - 'A' + 10) + "00");
        int check = 98 - value.mod(BigInteger.valueOf(97)).intValue();
        return country + (check < 10 ? "0" : "") + check + bban;
    }
}
