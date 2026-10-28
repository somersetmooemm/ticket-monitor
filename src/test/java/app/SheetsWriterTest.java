package app;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SheetsWriterTest {
    private static final Event EVENT = new Event("tixbay", "BTS", "Bangkok", "2026-12-12", "https://ticket.example/123");
    private static final String NOW = "2026-10-28 13:00";

    private static Lot lot(String id, double price, Integer maxRun) {
        return new Lot(EVENT, id, "VIP", 2, "Zone 1", price, "THB", maxRun, null, EVENT.url());
    }
    private static List<Object> prior(String id, double price, String time) {
        return new ArrayList<>(Arrays.asList("", "BTS", "Bangkok", "2026-12-12", "tixbay", "VIP", 2, "Zone 1", price, "THB", "", "", EVENT.url(), time, id, EVENT.url()));
    }

    @Test void failedEventPreservesOldPriceAndTimestampAndMarksUnverified() {
        var rows = SheetsWriter.prepareRows(List.of(prior("tixbay:1", 3000, "2026-10-27 09:00")), List.of(), Set.of(EVENT.url()), NOW);
        assertEquals(2, rows.size());
        assertEquals("НЕ ПРОВЕРЕНО", rows.get(1).get(0));
        assertEquals(3000.0, rows.get(1).get(8));
        assertEquals("2026-10-27 09:00", rows.get(1).get(13));
    }

    @Test void newLotIsMarkedNewOnSubsequentRun() {
        var rows = SheetsWriter.prepareRows(List.of(prior("tixbay:1", 3000, "old")), List.of(lot("tixbay:2", 2000, null)), Set.of(), NOW);
        assertEquals("НОВЫЙ", rows.get(1).get(0));
        assertEquals("", rows.get(1).get(10));
        assertEquals(NOW, rows.get(1).get(13));
    }

    @Test void priceDropIsMarked() {
        var rows = SheetsWriter.prepareRows(List.of(prior("tixbay:1", 3000, "old")), List.of(lot("tixbay:1", 2900, 2)), Set.of(), NOW);
        assertEquals("↓ подешевел", rows.get(1).get(0));
        assertEquals(Integer.valueOf(2), rows.get(1).get(10));
    }

    @Test void firstRunDoesNotMarkEveryLotNew() {
        var rows = SheetsWriter.prepareRows(List.of(), List.of(lot("tixbay:1", 2900, null)), Set.of(), NOW);
        assertEquals("", rows.get(1).get(0));
    }

    @Test void removedLotDoesNotSurviveSuccessfulRefresh() {
        var rows = SheetsWriter.prepareRows(List.of(prior("tixbay:old", 3000, "old")), List.of(lot("tixbay:new", 2900, null)), Set.of(), NOW);
        assertEquals(2, rows.size());
        assertEquals("tixbay:new", rows.get(1).get(14));
    }
}
