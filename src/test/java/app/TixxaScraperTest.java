package app;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TixxaScraperTest {

    private static final String BASE = "https://tixxa.co/concert-123/";

    private static final Event EVENT = new Event(
            "tixxa", "BTS", "Bangkok", "2026-12-03", "https://tixxa.co/concert-123");

    private static final String TEXT =
            "03 12/26 Zone W3O: Seat OL 08, OL 21 Ticket original price 3,300.00 THB "
            + "Quantity 2 Tickets Allow partial purchase SPayLater 10,339.62 THB / Ticket";

    private static String listing(
            String section, String originalPrice, String currency, int qty, String price) {

        return "03 12/26 " + section
               + " Ticket original price " + originalPrice + " " + currency
               + " Quantity " + qty + (qty == 1 ? " Ticket" : " Tickets")
               + " Allow partial purchase " + price + " " + currency + " / Ticket";
    }

    private static Lot lot(String id) {
        return TixxaScraper.parseLot(EVENT, BASE + id, TEXT);
    }

    // ------------------------------------------------------------------
    // Разбор лота
    // ------------------------------------------------------------------

    @Nested
    class ParseLot {

        @Test
        void parsesActualListingIncludingPluralTickets() {
            Lot lot = lot("456");

            assertAll(
                    () -> assertEquals("tixxa:456", lot.lotId()),
                    () -> assertEquals(2, lot.qty()),
                    () -> assertEquals(10339.62, lot.price(), 0.001),
                    () -> assertEquals("THB", lot.currency()),
                    () -> assertEquals("номинал 3300.00 THB", lot.category()),
                    () -> assertNull(lot.maxRun())
            );
        }

        @Test
        void parsesSingularTicket() {
            Lot lot = TixxaScraper.parseLot(
                    EVENT, BASE + "1", listing("Zone A", "1,500.00", "THB", 1, "4,200.00"));

            assertAll(
                    () -> assertEquals(1, lot.qty()),
                    () -> assertEquals(4200.00, lot.price(), 0.001)
            );
        }

        @Test
        void parsesPriceWithoutThousandsSeparator() {
            Lot lot = TixxaScraper.parseLot(
                    EVENT, BASE + "2", listing("Zone A", "950.00", "THB", 3, "950.00"));

            assertAll(
                    () -> assertEquals(950.00, lot.price(), 0.001),
                    () -> assertEquals("номинал 950.00 THB", lot.category())
            );
        }

        @Test
        void parsesPriceWithSeveralThousandsSeparators() {
            Lot lot = TixxaScraper.parseLot(
                    EVENT, BASE + "3", listing("VIP", "1,000.00", "THB", 2, "1,234,567.89"));

            assertEquals(1234567.89, lot.price(), 0.001);
        }

        @Test
        void parsesOtherCurrency() {
            Lot lot = TixxaScraper.parseLot(
                    EVENT, BASE + "4", listing("Zone B", "120.00", "USD", 4, "310.50"));

            assertAll(
                    () -> assertEquals("USD", lot.currency()),
                    () -> assertEquals("номинал 120.00 USD", lot.category()),
                    () -> assertEquals(4, lot.qty())
            );
        }

        @Test
        void normalizesWhitespaceAndNewlines() {
            String messy = "  " + TEXT.replace(" ", "\n  ") + "\n";

            Lot lot = TixxaScraper.parseLot(EVENT, BASE + "5", messy);

            assertAll(
                    () -> assertEquals(2, lot.qty()),
                    () -> assertEquals(10339.62, lot.price(), 0.001),
                    () -> assertEquals("номинал 3300.00 THB", lot.category())
            );
        }

        @Test
        void lotIdComesFromHref() {
            assertNotEquals(lot("456").lotId(), lot("457").lotId());
            assertEquals("tixxa:98765", lot("98765").lotId());
        }

        @Test
        void acceptsEventUrlWithTrailingSlash() {
            Event event = new Event(
                    "tixxa", "BTS", "Bangkok", "2026-12-03", "https://tixxa.co/concert-123/");

            Lot lot = TixxaScraper.parseLot(event, BASE + "456", TEXT);

            assertEquals("tixxa:456", lot.lotId());
        }

        @Test
        void invalidListingIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(EVENT, BASE + "456", "unexpected DOM"));
        }

        @Test
        void emptyListingIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(EVENT, BASE + "456", ""));
        }

        @Test
        void listingWithoutPriceIsRejected() {
            String noPrice = "03 12/26 Zone A Ticket original price 3,300.00 THB "
                             + "Quantity 2 Tickets Allow partial purchase";

            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(EVENT, BASE + "456", noPrice));
        }

        @Test
        void foreignEventListingIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(
                            EVENT, "https://tixxa.co/concert-999/456", TEXT));
        }

        @Test
        void eventIdThatOnlyEndsWithSameDigitsIsRejected() {
            // id события 123, а ссылка относится к событию 1123
            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(
                            EVENT, "https://tixxa.co/concert-1123/456", TEXT));
        }

        @Test
        void eventUrlWithoutIdIsRejected() {
            Event noId = new Event(
                    "tixxa", "BTS", "Bangkok", "2026-12-03", "https://tixxa.co/concert");

            assertThrows(IllegalArgumentException.class,
                    () -> TixxaScraper.parseLot(noId, "https://tixxa.co/concert/456", TEXT));
        }
    }

    // ------------------------------------------------------------------
    // Пагинация
    // ------------------------------------------------------------------

    @Nested
    class Pagination {

        @Test
        void pageWithLotsContinues() {
            assertEquals(TixxaScraper.PageState.CONTINUE,
                    TixxaScraper.classifyPage(1, false, 6));
            assertEquals(TixxaScraper.PageState.CONTINUE,
                    TixxaScraper.classifyPage(32, false, 1));
        }

        @Test
        void shortPageWithLotsDoesNotEndScrape() {
            // раньше 5 лотов на странице считались концом выгрузки
            for (int lots = 1; lots <= 6; lots++) {
                assertEquals(TixxaScraper.PageState.CONTINUE,
                        TixxaScraper.classifyPage(3, false, lots));
            }
        }

        @Test
        void emptyMarkerAfterLotsFinishes() {
            assertEquals(TixxaScraper.PageState.FINISH,
                    TixxaScraper.classifyPage(33, true, 0));
            assertEquals(TixxaScraper.PageState.FINISH,
                    TixxaScraper.classifyPage(2, true, 0));
        }

        @Test
        void emptyMarkerOnFirstPageMeansEventWithoutLots() {
            assertEquals(TixxaScraper.PageState.EMPTY_EVENT,
                    TixxaScraper.classifyPage(1, true, 0));
        }

        @Test
        void lotsWinOverEmptyMarker() {
            assertEquals(TixxaScraper.PageState.CONTINUE,
                    TixxaScraper.classifyPage(5, true, 3));
        }

        @Test
        void pageWithoutLotsAndWithoutMarkerIsAnError() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> TixxaScraper.classifyPage(7, false, 0));

            assertTrue(ex.getMessage().contains("страница 7"));
        }

        @Test
        void firstPageWithoutLotsAndWithoutMarkerIsAnError() {
            assertThrows(IllegalStateException.class,
                    () -> TixxaScraper.classifyPage(1, false, 0));
        }

        @Test
        void repeatedPageIsDetectedOnlyAfterFirst() {
            assertTrue(TixxaScraper.isRepeatedPage(2, 0));
            assertTrue(TixxaScraper.isRepeatedPage(33, 0));
            assertFalse(TixxaScraper.isRepeatedPage(2, 1));
            assertFalse(TixxaScraper.isRepeatedPage(2, 6));
            assertFalse(TixxaScraper.isRepeatedPage(1, 0));
        }

        @Test
        void pageUrlAddsQueryParameter() {
            assertEquals(
                    "https://tixxa.co/en/3-dec-bts-world-tour-arirang-in-bangkok-1119?page=2",
                    TixxaScraper.pageUrl(
                            "https://tixxa.co/en/3-dec-bts-world-tour-arirang-in-bangkok-1119", 2));
        }

        @Test
        void pageUrlAppendsToExistingQuery() {
            assertEquals(
                    "https://tixxa.co/concert-123?lang=en&page=32",
                    TixxaScraper.pageUrl("https://tixxa.co/concert-123?lang=en", 32));
        }
    }

    // ------------------------------------------------------------------
    // Частичная выгрузка
    // ------------------------------------------------------------------

    @Nested
    class PartialScrape {

        @Test
        void preservesCollectedLotsAndCause() {
            RuntimeException cause =
                    new IllegalStateException("Не удалось открыть страницу 32");

            TixxaScraper.PartialScrapeException exception =
                    new TixxaScraper.PartialScrapeException(
                            "Неполная выгрузка: 2 лота", cause, List.of(lot("456"), lot("457")));

            assertAll(
                    () -> assertEquals(
                            List.of("tixxa:456", "tixxa:457"),
                            exception.getPartialLots().stream().map(Lot::lotId).toList()),
                    () -> assertSame(cause, exception.getCause()),
                    () -> assertEquals("Неполная выгрузка: 2 лота", exception.getMessage())
            );
        }

        @Test
        void isRuntimeException() {
            assertInstanceOf(RuntimeException.class,
                    new TixxaScraper.PartialScrapeException(
                            "x", new IllegalStateException("y"), List.of()));
        }

        @Test
        void allowsEmptyLotList() {
            TixxaScraper.PartialScrapeException exception =
                    new TixxaScraper.PartialScrapeException(
                            "Неполная выгрузка", new IllegalStateException("Ошибка"), List.of());

            assertTrue(exception.getPartialLots().isEmpty());
        }

        @Test
        void makesDefensiveCopyAndReturnsImmutableList() {
            List<Lot> original = new ArrayList<>();
            original.add(lot("456"));

            TixxaScraper.PartialScrapeException exception =
                    new TixxaScraper.PartialScrapeException(
                            "Неполная выгрузка", new IllegalStateException("Ошибка"), original);

            original.clear();

            assertAll(
                    () -> assertEquals(1, exception.getPartialLots().size()),
                    () -> assertThrows(UnsupportedOperationException.class,
                            () -> exception.getPartialLots().clear())
            );
        }

        @Test
        void canBeCaughtBeforeGenericException() {
            List<Lot> collected = new ArrayList<>();

            try {
                throw new TixxaScraper.PartialScrapeException(
                        "Ошибка страницы 32",
                        new IllegalStateException("Timeout"),
                        List.of(lot("456")));
            } catch (TixxaScraper.PartialScrapeException ex) {
                collected.addAll(ex.getPartialLots());
            } catch (Exception ex) {
                fail("Ошибка должна обрабатываться как частичная выгрузка");
            }

            assertEquals(List.of("tixxa:456"), collected.stream().map(Lot::lotId).toList());
        }
    }
}