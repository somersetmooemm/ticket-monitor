package app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SheetsWriterTest {
    private static final Event E = new Event("tixbay", "BTS", "Bangkok", "2026-12-12", "https://ticket.example/123");

    private static Lot lot(String id, double price) {
        return new Lot(E, id, "VIP", 2, "Zone 1", price, "THB", null, null, E.url());
    }

    @Test
    void writesOnlyCurrentLots() {
        var rows = SheetsWriter.prepareRows(List.of(lot("new:1", 2000)), "2026-10-28 13:00");
        assertEquals(2, rows.size());
        assertEquals("new:1", rows.get(1).get(14));
        assertEquals("", rows.get(1).get(0));
        assertEquals("2026-10-28 13:00", rows.get(1).get(13));
    }

    @Test
    void emptyCollectionProducesHeaderOnlyForClearedSheet() {
        var rows = SheetsWriter.prepareRows(List.of(), "now");
        assertEquals(1, rows.size());
    }

    @Test
    void priceAndUnknownAdjacentSeatsArePreserved() {
        var rows = SheetsWriter.prepareRows(List.of(lot("new:2", 1234.5)), "now");
        assertEquals(1234.5, rows.get(1).get(8));
        assertEquals("", rows.get(1).get(10));
    }

    @Test
    void sortByPriceWithinEvent() {
        var rows = SheetsWriter.prepareRows(List.of(lot("expensive", 3000), lot("cheap", 1000)), "now");
        assertEquals("cheap", rows.get(1).get(14));
        assertEquals("expensive", rows.get(2).get(14));
    }
}
