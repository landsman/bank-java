package cz.insuit.bank.notification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A Czech bank account number, {@code [prefix-]number/bankCode}, checked against the Czech National Bank's modulo 11
 * rule (Vyhláška č. 169/2011 Sb.), so a misread number is refused rather than handed out.
 *
 * @param prefix the předčíslí without leading zeros, or {@code null} when there is none
 * @param number the account number without leading zeros
 * @param bankCode the four-digit bank code, e.g. {@code 3030} for Air Bank
 */
public record BankAccount(@Nullable String prefix, String number, String bankCode) {

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
     * Reads {@code 19-2000145399/0800} or {@code 2000145399/0800}.
     *
     * @param text the account as written
     * @throws IllegalArgumentException when it is not a valid Czech account number
     */
    public static BankAccount parse(String text) {
        Matcher m = FORMAT.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException("not in the form [prefix-]number/bankCode: " + text);
        }
        return new BankAccount(m.group(1), m.group(2), m.group(3));
    }

    /** {@code 19-2000145399/0800}, or {@code 2000145399/0800} without a prefix. */
    @Override
    public String toString() {
        return (prefix == null ? "" : prefix + "-") + number + "/" + bankCode;
    }

    private static final Pattern FORMAT = Pattern.compile("(?:(\\d{1,6})-)?(\\d{2,10})/(\\d{4})");
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

    private static String stripZeros(String digits) {
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
