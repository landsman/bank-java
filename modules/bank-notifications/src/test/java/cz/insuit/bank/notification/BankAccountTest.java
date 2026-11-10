package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BankAccountTest {

    @Test
    void readsAnAccountWithAPrefix() {
        // The Czech National Bank's sample account in its own guidance.
        BankAccount account = BankAccount.parse("19-2000145399/0800");

        assertEquals("19", account.prefix());
        assertEquals("2000145399", account.number());
        assertEquals("0800", account.bankCode());
        assertEquals("19-2000145399/0800", account.toString());
    }

    @Test
    void readsAnAccountWithoutAPrefix() {
        BankAccount account = BankAccount.parse("1234567899/3030");

        assertNull(account.prefix());
        assertEquals("1234567899/3030", account.toString());
    }

    @Test
    void dropsLeadingZerosSoTheSameAccountIsEqual() {
        assertEquals(BankAccount.parse("000019-2000145399/0800"), BankAccount.parse("19-2000145399/0800"));
        assertEquals(BankAccount.parse("000000-1234567899/3030"), BankAccount.parse("1234567899/3030"));
    }

    @Test
    void refusesANumberThatFailsTheChecksum() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("1234567890/3030"));
    }

    @Test
    void refusesAPrefixThatFailsTheChecksum() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("18-2000145399/0800"));
    }

    @Test
    void refusesAnythingElse() {
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("1234567899"));
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("1234567899/30"));
        assertThrows(IllegalArgumentException.class, () -> BankAccount.parse("CZ6508000000192000145399"));
    }
}
