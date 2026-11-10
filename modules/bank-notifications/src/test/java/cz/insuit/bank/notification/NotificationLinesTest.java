package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The line reader every bank shares, branch by branch; the banks' own tests reach it only through whole e-mails. */
class NotificationLinesTest {

    private static final Pattern LABELLED = Pattern.compile("(VS|KS|Zpráva|Kód):(.*)", Pattern.DOTALL);
    private static final Set<String> STACKED = Set.of("Účet", "Částka", "Popis", "Od");

    @Test
    void singleFindsTheOneMatchingLine() {
        Matcher found = new NotificationLines("a\r\nVS: 1\r\nb").single(Pattern.compile("VS: (\\d+)"), "symbol");

        assertEquals("1", found == null ? null : found.group(1));
    }

    @Test
    void singleIsNullWhenNoLineMatches() {
        assertNull(new NotificationLines("a\r\nb").single(Pattern.compile("VS: \\d+"), "symbol"));
    }

    @Test
    void singleRefusesTwoMatchingLines() {
        NotificationLines lines = new NotificationLines("VS: 1\r\nVS: 2");

        assertThrows(Unparsed.class, () -> lines.single(Pattern.compile("VS: \\d+"), "symbol"));
    }

    @Test
    void labelledReadsValuesStripped() {
        Map<String, String> fields =
                new NotificationLines(" VS: 1 \r\nKS:  308").labelled(LABELLED, Set.of(), Set.of());

        assertEquals(Map.of("VS", "1", "KS", "308"), fields);
    }

    @Test
    void labelledTakesAnEmptyValueForAnAbsentField() {
        assertEquals(Map.of(), new NotificationLines("KS: ").labelled(LABELLED, Set.of(), Set.of()));
    }

    @Test
    void labelledRefusesALabelSeenTwiceEvenWhenOneIsEmpty() {
        NotificationLines lines = new NotificationLines("KS: \r\nKS: 1");

        assertThrows(Unparsed.class, () -> lines.labelled(LABELLED, Set.of(), Set.of()));
    }

    @Test
    void labelledLetsOnlyTheAllowedLabelsFollowFreeText() {
        NotificationLines allowed = new NotificationLines("VS: 1\r\nZpráva: x\r\nKód: 9");
        NotificationLines forged = new NotificationLines("Zpráva: x\r\nVS: 42");

        assertEquals(
                Map.of("VS", "1", "Zpráva", "x", "Kód", "9"),
                allowed.labelled(LABELLED, Set.of("Zpráva"), Set.of("Kód")));
        assertThrows(Unparsed.class, () -> forged.labelled(LABELLED, Set.of("Zpráva"), Set.of("Kód")));
    }

    @Test
    void requireNextPassesWhenTheExpectedLineFollows() {
        new NotificationLines("Od: Jan\r\nČástka: 1").requireNext(Pattern.compile("Od: .*"), "Částka:");
    }

    @Test
    void requireNextRefusesALineInBetween() {
        NotificationLines lines = new NotificationLines("Od: Jan\r\nVS: 42\r\nČástka: 1");

        assertThrows(Unparsed.class, () -> lines.requireNext(Pattern.compile("Od: .*"), "Částka:"));
    }

    @Test
    void requireNextRefusesTheLineBeingLast() {
        NotificationLines lines = new NotificationLines("Od: Jan");

        assertThrows(Unparsed.class, () -> lines.requireNext(Pattern.compile("Od: .*"), "Částka:"));
    }

    @Test
    void requireNextIgnoresABodyWithoutTheLine() {
        new NotificationLines("Částka: 1").requireNext(Pattern.compile("Od: .*"), "Částka:");
    }

    @Test
    void stackedReadsTheValueFromTheNextLine() {
        Map<String, String> fields =
                new NotificationLines("Účet:\r\n238***891\r\nČástka:\r\n1,00 Kč").stacked(STACKED, Set.of(), Set.of());

        assertEquals(Map.of("Účet", "238***891", "Částka", "1,00 Kč"), fields);
    }

    @Test
    void stackedTakesALabelFollowedByAnotherLabelForAnAbsentField() {
        Map<String, String> fields =
                new NotificationLines("Popis:\r\nOd:\r\n1/0100").stacked(STACKED, Set.of(), Set.of());

        assertEquals(Map.of("Od", "1/0100"), fields);
    }

    @Test
    void stackedTakesALabelOnTheLastLineForAnAbsentField() {
        assertEquals(Map.of(), new NotificationLines("Od:").stacked(STACKED, Set.of(), Set.of()));
    }

    @Test
    void stackedIgnoresALabelItWasNotGiven() {
        Map<String, String> fields =
                new NotificationLines("Neznámé:\r\nx\r\nOd:\r\n1/0100").stacked(STACKED, Set.of(), Set.of());

        assertEquals(Map.of("Od", "1/0100"), fields);
    }

    @Test
    void stackedRefusesALabelSeenTwice() {
        NotificationLines lines = new NotificationLines("Částka:\r\n1,00 Kč\r\nČástka:\r\n9 999,00 Kč");

        assertThrows(Unparsed.class, () -> lines.stacked(STACKED, Set.of(), Set.of()));
    }

    @Test
    void stackedLetsOnlyTheAllowedLabelsFollowFreeText() {
        NotificationLines forged = new NotificationLines("Popis:\r\nx\r\nČástka:\r\n9 999,00 Kč");

        assertThrows(Unparsed.class, () -> forged.stacked(STACKED, Set.of("Popis"), Set.of("Od")));
    }
}
