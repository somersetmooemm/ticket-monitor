
package app;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SheetsWriterTest {

    private static final Rates RATES = new Rates(
            Map.of(
                    "THB", 2.5,
                    "USD", 80.0
            )
    );

    private static final Event E = new Event(
            "tixbay",
            "BTS",
            "Bangkok",
            "2026-12-12",
            "https://ticket.example/123"
    );

    private static Lot lot(String id, double price) {
        return new Lot(
                E, id, "VIP", 2, "Zone 1",
                price, "THB", null, null, E.url()
        );
    }

    private static Lot lotQty(int qty, double price, String cur) {
        return new Lot(
                E, "x", "VIP", qty, "Zone 1",
                price, cur, null, null, E.url()
        );
    }

    @Test
    void writesOnlyCurrentLots() {
        var rows = SheetsWriter.prepareRows(
                List.of(lot("new:1", 2000)),
                "2026-10-28 13:00",
                RATES
        );

        assertEquals(2, rows.size());
        assertEquals("new:1", rows.get(1).get(14));
        assertEquals("BTS", rows.get(1).get(0));
        assertEquals("2026-10-28 13:00", rows.get(1).get(13));
    }

    @Test
    void emptyCollectionProducesHeaderOnlyForClearedSheet() {
        var rows = SheetsWriter.prepareRows(
                List.of(), "now", RATES
        );

        assertEquals(1, rows.size());
        assertEquals("Концерт", rows.get(0).get(0));
        assertEquals(
                "Итоговая стоимость за 1 шт. (₽)",
                rows.get(0).get(9)
        );
        assertTrue(
                rows.get(0).stream().noneMatch("Статус"::equals)
        );
    }

    @Test
    void priceAndUnknownAdjacentSeatsArePreserved() {
        var rows = SheetsWriter.prepareRows(
                List.of(lot("new:2", 1234.5)),
                "now",
                RATES
        );

        assertEquals(1234.5, rows.get(1).get(7));
        assertEquals("", rows.get(1).get(10));
    }

    @Test
    void sortByPriceWithinEvent() {
        var rows = SheetsWriter.prepareRows(
                List.of(
                        lot("expensive", 3000),
                        lot("cheap", 1000)
                ),
                "now",
                RATES
        );

        assertEquals("cheap", rows.get(1).get(14));
        assertEquals("expensive", rows.get(2).get(14));
    }

    @Test
    void totalPerTicketIncludesMarkupAndFee() {
        // 4000 THB * (2.5 * 1.10) + 32000 = 43000
        // Количество билетов не влияет на цену одного.

        assertAll(
                () -> assertEquals(
                        43_000L,
                        Cost.totalRub(lotQty(1, 4000, "THB"), RATES)
                ),
                () -> assertEquals(
                        43_000L,
                        Cost.totalRub(lotQty(2, 4000, "THB"), RATES)
                ),
                () -> assertEquals(
                        43_000L,
                        Cost.totalRub(lotQty(5, 4000, "THB"), RATES)
                )
        );
    }

    @Test
    void roundsUpToNextThousandAfterMarkupAndFee() {
        Rates oneToOne = new Rates(Map.of("THB", 1.0));

        // 25963 * 1.10 + 32000 = 60559.30 -> 61000
        // 26000 * 1.10 + 32000 = 60600.00 -> 61000
        // 26001 * 1.10 + 32000 = 60601.10 -> 61000

        assertAll(
                () -> assertEquals(
                        61_000L,
                        Cost.totalRub(lotQty(1, 25963, "THB"), oneToOne)
                ),
                () -> assertEquals(
                        61_000L,
                        Cost.totalRub(lotQty(1, 26000, "THB"), oneToOne)
                ),
                () -> assertEquals(
                        61_000L,
                        Cost.totalRub(lotQty(1, 26001, "THB"), oneToOne)
                ),
                () -> assertEquals(
                        61_000L,
                        Cost.totalRub(lotQty(5, 25963, "THB"), oneToOne)
                )
        );
    }

    @Test
    void roundsUpWhenCrossingThousandBoundary() {
        Rates oneToOne = new Rates(Map.of("THB", 1.0));

        // 26363 * 1.10 + 32000 = 60999.30 -> 61000
        // 26364 * 1.10 + 32000 = 61000.40 -> 62000

        assertEquals(
                61_000L,
                Cost.totalRub(lotQty(1, 26363, "THB"), oneToOne)
        );

        assertEquals(
                62_000L,
                Cost.totalRub(lotQty(1, 26364, "THB"), oneToOne)
        );
    }

    @Test
    void rubCurrencyDoesNotHaveMarkup() {
        // Для RUB курс 1.0 без наценки 10%.
        // 10000 + 32000 = 42000

        assertEquals(
                42_000L,
                Cost.totalRub(lotQty(1, 10000, "RUB"), RATES)
        );
    }

    @Test
    void unknownCurrencyThrowsException() {
        Lot unknown = lotQty(1, 100, "XYZ");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> Cost.totalRub(unknown, RATES)
        );

        assertEquals(
                "Не найден курс валюты: XYZ",
                exception.getMessage()
        );
    }

    @Test
    void parsesCbrJson() throws Exception {
        String json = """
                {
                  "Valute": {
                    "USD": {
                      "Nominal": 1,
                      "Value": 80.0
                    },
                    "THB": {
                      "Nominal": 10,
                      "Value": 25.0
                    }
                  }
                }
                """;

        Rates rates = Rates.parseCbr(json);

        assertAll(
                () -> assertEquals(2.5, rates.rub("THB")),
                () -> assertEquals(80.0, rates.rub("USD"))
        );
    }

    @Test
    void clientRowsHaveSectorQtyAndTotalWithMarkupAndFee() {
        Event event = new Event(
                "tixbay",
                "BTS",
                "Bangkok",
                "Таиланд",
                "2026-12-12",
                "https://ticket.example/123"
        );

        Lot lot = new Lot(
                event,
                "x",
                "VIP",
                3,
                "Zone 1",
                4000,
                "THB",
                null,
                null,
                event.url()
        );

        var rows = SheetsWriter.prepareClientRows(
                List.of(lot),
                RATES
        );

        assertEquals(
                List.of(
                        "Название концерта",
                        "Страна",
                        "Дата",
                        "Сектор",
                        "Количество",
                        "Итоговая стоимость за 1 шт. (₽)"
                ),
                rows.get(0)
        );

        // 4000 * (2.5 * 1.10) + 32000 = 43000

        assertEquals(
                List.of(
                        "BTS",
                        "Таиланд",
                        "2026-12-12",
                        "Zone 1",
                        3,
                        43_000L
                ),
                rows.get(1)
        );
    }

    @Test
    void clientTotalThrowsWhenRateMissing() {
        Lot unknown = lotQty(2, 100, "XYZ");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SheetsWriter.prepareClientRows(
                        List.of(unknown),
                        RATES
                )
        );

        assertEquals("Не найден курс валюты: XYZ", exception.getMessage());
    }
}