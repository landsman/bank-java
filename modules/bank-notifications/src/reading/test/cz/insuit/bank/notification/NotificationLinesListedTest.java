package cz.insuit.bank.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cz.insuit.bank.notification.BankNotificationException.Unparsed;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link NotificationLines#listed}, branch by branch: "- label: value" items, one or several to a line. */
class NotificationLinesListedTest {

    private static final Set<String> LABELS = Set.of("účet protistrany", "banka protistrany", "datum", "VS");

    @Test
    void readsOneItemToALine() {
        assertEquals(Map.of("VS", "123"), new NotificationLines("- VS: 123").listed(LABELS));
    }

    @Test
    void readsSeveralItemsOnOneLine() {
        Map<String, String> fields = new NotificationLines(
                        "- účet protistrany: 9876543211 - banka protistrany: 0800 - datum: 23.06.2026 18:43")
                .listed(LABELS);

        assertEquals(
                Map.of("účet protistrany", "9876543211", "banka protistrany", "0800", "datum", "23.06.2026 18:43"),
                fields);
    }

    @Test
    void readsOnlyLinesThatStartWithADash() {
        assertEquals(Map.of(), new NotificationLines("VS: 123").listed(LABELS));
    }

    @Test
    void ignoresALabelItWasNotGiven() {
        assertEquals(Map.of("VS", "1"), new NotificationLines("- neznámé: x - VS: 1").listed(LABELS));
    }

    @Test
    void refusesALabelSeenTwice() {
        NotificationLines lines = new NotificationLines("- VS: 1\r\n- VS: 42");

        assertThrows(Unparsed.class, () -> lines.listed(LABELS));
    }

    @Test
    void takesAnEmptyValueForAnAbsentField() {
        assertEquals(Map.of(), new NotificationLines("- VS: ").listed(LABELS));
    }
}
