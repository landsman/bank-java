package cz.insuit.bank.notification;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The lines of a bank's plain-text notification, read the way every {@link Bank} reads them: a sentence or a line that
 * may appear once, and "Label: value" lines that may appear once each. Anything doubled is {@link Unparsed}, so a
 * template that changed is refused rather than half-read.
 */
final class NotificationLines {

    private final List<String> lines;

    /** Splits the body into stripped lines, after the bank's own clean-up of each one (an invisible character, say). */
    NotificationLines(String body, UnaryOperator<String> clean) {
        this.lines = body.lines().map(clean).map(String::strip).toList();
    }

    NotificationLines(String body) {
        this(body, UnaryOperator.identity());
    }

    /** The one line matching the whole pattern, {@code null} when there is none, {@link Unparsed} when there are two. */
    @Nullable
    Matcher single(Pattern pattern, String what) {
        Matcher found = null;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                if (found != null) throw new Unparsed("two " + what + " lines");
                found = matcher;
            }
        }
        return found;
    }

    /**
     * {@link Unparsed} unless the line right after the one matching {@code line} starts with {@code next}; nothing when
     * no line matches. A line holding a name the payer controls is pinned this way: a line break smuggled into the name
     * would add lines, a counter-party or a variable symbol of its own, and push {@code next} down.
     */
    void requireNext(Pattern line, String next) {
        for (int i = 0; i < lines.size(); i++) {
            if (!line.matcher(lines.get(i)).matches()) continue;
            if (i + 1 >= lines.size() || !lines.get(i + 1).startsWith(next)) {
                throw new Unparsed("'" + next + "' does not follow the line naming the other side");
            }
        }
    }

    /**
     * The lines matching {@code labelled}, whose first group is the label and second the value, stripped. A label
     * with nothing after it (`SS: `) is the field being absent, not a value to refuse, and stays out of the map.
     *
     * <p>Free text is what a payer controls: a message, a name. Should the bank ever pass a line break through, a forged
     * "Variabilní symbol:" after it must not count, so once a free-text label has appeared only another free-text
     * label or one of {@code allowedAfterFreeText} may follow.
     */
    Map<String, String> labelled(Pattern labelled, Set<String> freeText, Set<String> allowedAfterFreeText) {
        Map<String, String> fields = new HashMap<>();
        Set<String> seen = new HashSet<>();
        boolean afterFreeText = false;
        for (String line : lines) {
            Matcher matcher = labelled.matcher(line);
            if (!matcher.matches()) continue;
            String label = matcher.group(1);
            if (afterFreeText && !freeText.contains(label) && !allowedAfterFreeText.contains(label)) {
                throw new Unparsed("'" + label + "' after the message");
            }
            if (!seen.add(label)) throw new Unparsed("two '" + label + "' lines");
            String value = matcher.group(2).strip();
            if (!value.isEmpty()) fields.put(label, value);
            afterFreeText |= freeText.contains(label);
        }
        return fields;
    }
}
