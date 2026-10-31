package app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SheetsWriterTest {
    private static final Rates RATES = new Rates(java.util.Map.of("THB", 2.5, "USD", 80.0));
    private static final Event E = new Event("tixbay", "BTS", "Bangkok", "2026-12-12", "https://ticket.example/123");

    private static Lot lot(String id, double price) {
        return new Lot(E, id, "VIP", 2, "Zone 1", price, "THB", null, null, E.url());
    }

    @Test
    void writesOnlyCurrentLots() {
        var rows = SheetsWriter.prepareRows(List.of(lot("new:1", 2000)), "2026-10-28 13:00", RATES);
        assertEquals(2, rows.size());
        assertEquals("new:1", rows.get(1).get(14));
        assertEquals("BTS", rows.get(1).get(0));
        assertEquals("2026-10-28 13:00", rows.get(1).get(13));
    }

    @Test
    void emptyCollectionProducesHeaderOnlyForClearedSheet() {
        var rows = SheetsWriter.prepareRows(List.of(), "now", RATES);
        assertEquals(1, rows.size());
        assertEquals("Концерт", rows.get(0).get(0));
        assertEquals("Итоговая стоимость за 1 шт. (₽)", rows.get(0).get(9));
        assertTrue(rows.get(0).stream().noneMatch("Статус"::equals));
    }

    @Test
    void priceAndUnknownAdjacentSeatsArePreserved() {
        var rows = SheetsWriter.prepareRows(List.of(lot("new:2", 1234.5)), "now", RATES);
        assertEquals(1234.5, rows.get(1).get(7));
        assertEquals("", rows.get(1).get(10));
    }

    @Test
    void sortByPriceWithinEvent() {
        var rows = SheetsWriter.prepareRows(List.of(lot("expensive", 3000), lot("cheap", 1000)), "now", RATES);
        assertEquals("cheap", rows.get(1).get(14));
        assertEquals("expensive", rows.get(2).get(14));
    }

    private static Lot lotQty(int qty, double price, String cur) {
        return new Lot(E, "x", "VIP", qty, "Zone 1", price, cur, null, null, E.url());
    }

    @Test
    void totalPerTicketIsPriceInRubPlus32000() {
        // 4000 THB * 2.5 = 10000 + 32000, не зависит от количества в лоте
        assertEquals(42000L, Cost.totalRub(lotQty(1, 4000, "THB"), RATES));
        assertEquals(42000L, Cost.totalRub(lotQty(2, 4000, "THB"), RATES));
        assertEquals(42000L, Cost.totalRub(lotQty(5, 4000, "THB"), RATES));
    }

    @Test
    void unknownCurrencyGivesEmptyTotal() {
        assertEquals("", Cost.totalRub(lotQty(1, 100, "XYZ"), RATES));
    }

    @Test
    void parsesCbrJson() throws Exception {
        var r = Rates.parseCbr("{\"Valute\":{\"USD\":{\"Nominal\":1,\"Value\":80.0},\"THB\":{\"Nominal\":10,\"Value\":25.0}}}");
        assertEquals(2.5, r.rub("THB"));
        assertEquals(80.0, r.rub("USD"));
    }

    @Test
    void clientRowsHaveSectorQtyAndTotalWithFee() {
        Event e = new Event("tixbay", "BTS", "Bangkok", "Таиланд", "2026-12-12", "https://ticket.example/123");
        Lot l = new Lot(e, "x", "VIP", 3, "Zone 1", 4000, "THB", null, null, e.url());
        var rows = SheetsWriter.prepareClientRows(List.of(l), RATES);
        assertEquals(List.of("Название концерта", "Страна", "Сектор", "Количество", "Итоговая сумма с комиссией (₽)"), rows.get(0));
        assertEquals(List.of("BTS", "Таиланд", "Zone 1", 3, 126000L), rows.get(1)); // 3 * (4000*2.5 + 32000)
    }

    @Test
    void clientTotalIsEmptyWithoutRate() {
        var rows = SheetsWriter.prepareClientRows(List.of(lotQty(2, 100, "XYZ")), RATES);
        assertEquals("", rows.get(1).get(4));
    }
}
