package cz.insuit.bank.notification;

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A Czech or Slovak bank account: the local form {@code [prefix-]number/bankCode} and its IBAN are the same account.
 * Both countries number accounts the same way, a prefix of up to 6 digits, a number of up to 10 and a 4-digit bank code,
 * each part checked against the same modulo 11 rule (in Czechia Vyhláška č. 169/2011 Sb.), so a misread number is
 * refused rather than handed out. An IBAN is checked against its own modulo 97 digits as well.
 *
 * <p>Equal accounts are equal however they were written: leading zeros are dropped, and a local form and an IBAN of the
 * same account compare equal. The country is part of the account: the same digits under a Czech and a Slovak bank code
 * are two accounts.
 *
 * @param country the country whose bank keeps the account
 * @param prefix the prefix (předčíslí, predčíslie) without leading zeros, or {@code null} when there is none
 * @param number the account number without leading zeros
 * @param bankCode the four-digit bank code, e.g. {@code 3030} for Air Bank
 */
public record BankAccount(Country country, @Nullable String prefix, String number, String bankCode) {

    public BankAccount {
        if (prefix != null && (!prefix.matches("\\d{1,6}") || !checksumHolds(prefix, PREFIX_WEIGHTS))) {
            throw new IllegalArgumentException("not a valid account prefix: " + prefix);
        }
        if (!number.matches("\\d{2,10}") || !checksumHolds(number, NUMBER_WEIGHTS)) {
            throw new IllegalArgumentException("not a valid account number: " + number);
        }
        if (!bankCode.matches("\\d{4}")) {
            throw new IllegalArgumentException("not a bank code: " + bankCode);
        }
        prefix = prefix == null || prefix.matches("0+") ? null : stripZeros(prefix);
        number = stripZeros(number);
    }

    /**
     * Reads an account in the local form of its country, {@code [prefix-]number/bankCode}. The form does not say the
     * country, so the caller does: the same digits under a Czech and a Slovak bank code are two accounts.
     *
     * @param text the account as written
     * @param country the country whose bank keeps it
     * @throws IllegalArgumentException when it is not a valid account number
     */
    public static BankAccount parse(String text, Country country) {
        Matcher m = LOCAL.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("not in the form [prefix-]number/bankCode: " + text);
        }
        return new BankAccount(country, m.group(1), m.group(2), m.group(3));
    }

    /**
     * Reads a Czech or Slovak IBAN, with or without the spaces between its groups of four: {@code CZ65 0800 0000 1920
     * 0014 5399}.
     *
     * @param text the IBAN as written
     * @throws IllegalArgumentException when it is not a valid IBAN, or not a Czech or Slovak one
     */
    public static BankAccount parseIban(String text) {
        String iban = text.replace(" ", "").toUpperCase(Locale.ROOT);
        Matcher m = IBAN.matcher(iban);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a Czech or Slovak IBAN: " + text);
        }
        if (!checksumHolds(iban)) {
            throw new IllegalArgumentException("IBAN check digits do not hold: " + text);
        }
        // In an IBAN the bank code comes first, then the prefix and the number.
        return new BankAccount(Country.valueOf(m.group(1)), m.group(3), m.group(4), m.group(2));
    }

    /** The IBAN, without spaces: {@code CZ6508000000192000145399}. */
    public String iban() {
        String bban = bankCode + zeros(prefix == null ? "" : prefix, 6) + zeros(number, 10);
        BigInteger value = new BigInteger(digits(bban + country + "00"));
        int check = 98 - value.mod(BigInteger.valueOf(97)).intValue();
        return country.name() + (check < 10 ? "0" : "") + check + bban;
    }

    /** The local form: {@code 19-2000145399/0800}, or {@code 2000145399/0800} without a prefix. */
    @Override
    public String toString() {
        return (prefix == null ? "" : prefix + "-") + number + "/" + bankCode;
    }

    private static final Pattern LOCAL = Pattern.compile("(?:(\\d{1,6})-)?(\\d{2,10})/(\\d{4})");
    // Country, two check digits, then the bank code, the prefix and the number at their full lengths.
    private static final Pattern IBAN = Pattern.compile("(CZ|SK)\\d{2}(\\d{4})(\\d{6})(\\d{10})");
    private static final int[] PREFIX_WEIGHTS = {10, 5, 8, 4, 2, 1};
    private static final int[] NUMBER_WEIGHTS = {6, 3, 7, 9, 10, 5, 8, 4, 2, 1};

    private static boolean checksumHolds(String digits, int[] weights) {
        int offset = weights.length - digits.length();
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * weights[offset + i];
        }
        return sum % 11 == 0;
    }

    /** ISO 13616: the first four characters moved to the end, letters as numbers, the whole modulo 97 is 1. */
    private static boolean checksumHolds(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        return new BigInteger(digits(rearranged)).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Letters as ISO 13616 numbers them, A = 10 to Z = 35; digits as they are. */
    private static String digits(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return out.toString();
    }

    private static String zeros(String digits, int length) {
        return "0".repeat(length - digits.length()) + digits;
    }

    private static String stripZeros(String digits) {
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
