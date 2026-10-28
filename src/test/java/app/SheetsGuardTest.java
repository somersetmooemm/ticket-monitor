package app;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests: full replacement; old listings are never restored. */
class SheetsGuardTest {
    private static final Event E = new Event("tixxa", "BTS", "Bangkok", "2026-12-05", "https://tixxa.co/concert-123");

    private static List<Lot> lots(int from, int to) {
        List<Lot> result = new ArrayList<>();
        for (int i = from; i < to; i++) {
            result.add(new Lot(E, "tixxa:" + i, "cat", 1, "place", 1000, "THB", null, null, E.url() + "/" + i));
        }
        return result;
    }

    @Test void droppingFrom146To59WritesOnly59CurrentLots() {
        var rows = SheetsWriter.prepareRows(lots(0, 59), "2026-10-28 13:00");
        assertEquals(60, rows.size()); // header + 59 current lots
        assertEquals("tixxa:0", rows.get(1).get(14));
        assertEquals("tixxa:58", rows.get(59).get(14));
        assertTrue(rows.stream().skip(1).noneMatch(r -> "НЕ ПРОВЕРЕНО".equals(r.get(0))));
    }

    @Test void emptyCurrentRunProducesOnlyHeader() {
        var rows = SheetsWriter.prepareRows(List.of(), "2026-10-28 13:00");
        assertEquals(1, rows.size());
        assertEquals("Статус", rows.get(0).get(0));
    }

    @Test void freshLotsReplacePriorIdsWithoutMerging() {
        var rows = SheetsWriter.prepareRows(lots(100, 102), "2026-10-28 13:00");
        assertEquals(3, rows.size());
        assertEquals("tixxa:100", rows.get(1).get(14));
        assertEquals("tixxa:101", rows.get(2).get(14));
    }

    @Test void currentRunHasNoRetainedFailureStatus() {
        var rows = SheetsWriter.prepareRows(lots(0, 2), "2026-10-28 13:00");
        assertEquals("", rows.get(1).get(0));
        assertEquals("2026-10-28 13:00", rows.get(1).get(13));
    }
}
