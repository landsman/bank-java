package cz.insuit.bank.notification;

/**
 * A country whose bank accounts {@link BankAccount} reads. Czechia and Slovakia number accounts the same way, a prefix,
 * a number and a four-digit bank code under one modulo 11 rule; another country is another constant.
 */
public enum Country {
    CZ,
    SK
}
