package app;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TixxaScraperTest {
    private static final Event EVENT = new Event("tixxa", "BTS", "Bangkok", "2026-12-03", "https://tixxa.co/concert-123");
    private static final String TEXT = "03 12/26 Zone W3O: Seat OL 08, OL 21 Ticket original price 3,300.00 THB Quantity 2 Tickets Allow partial purchase SPayLater 10,339.62 THB / Ticket";

    @Test
    void parsesActualListingIncludingPluralTickets() {
        Lot lot = TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-123/456", TEXT);
        assertEquals("tixxa:456", lot.lotId());
        assertEquals(2, lot.qty());
        assertEquals(10339.62, lot.price(), 0.001);
        assertEquals("THB", lot.currency());
        assertEquals("номинал 3300.00 THB", lot.category());
        assertNull(lot.maxRun());
    }

    @Test
    void invalidListingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-123/456", "unexpected DOM"));
    }

    @Test
    void foreignEventListingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-999/456", TEXT));
    }

    @Test
    void emptyOrRepeatedPageIsRejected() {
        assertThrows(IllegalStateException.class, () -> TixxaScraper.requireNewLots(2, 0));
        assertDoesNotThrow(() -> TixxaScraper.requireNewLots(2, 6));
    }

    @Test
    void unknownPaginationOnFullPageDoesNotStopScrape() {
        assertFalse(TixxaScraper.isLastShortPage(0, 6, false));
        assertFalse(TixxaScraper.isLastShortPage(0, 6, true));
    }

    @Test
    void shortPageWithoutNextCanEndScrape() {
        assertTrue(TixxaScraper.isLastShortPage(0, 3, false));
        assertFalse(TixxaScraper.isLastShortPage(0, 3, true));
        assertFalse(TixxaScraper.isLastShortPage(5, 3, false));
    }

    @Test
    void fullLastPageWithCurrentMarkerIsAccepted() {
        assertTrue(TixxaScraper.shouldFinishAtFullPage(0, 2, 6, true, false, false, false));
    }

    @Test
    void fullLastPageWithDisabledNextIsAccepted() {
        assertTrue(TixxaScraper.shouldFinishAtFullPage(0, 2, 6, false, false, false, true));
    }

    @Test
    void visibleNextPreventsPrematureCompletion() {
        assertFalse(TixxaScraper.shouldFinishAtFullPage(0, 2, 6, true, true, false, false));
        assertFalse(TixxaScraper.shouldFinishAtFullPage(0, 2, 6, true, false, true, false));
    }

    @Test
    void unknownPaginationCannotBeAssumedFinished() {
        assertFalse(TixxaScraper.shouldFinishAtFullPage(0, 2, 6, false, false, false, false));
    }

    @Test
    void shortPageIsHandledBySeparateCompletionRule() {
        assertFalse(TixxaScraper.shouldFinishAtFullPage(0, 2, 3, true, false, false, false));
        assertTrue(TixxaScraper.isLastShortPage(0, 3, false));
    }

    @Test
    void advertisedPagesCannotBeOverriddenByUnknownPageRule() {
        assertFalse(TixxaScraper.shouldFinishAtFullPage(5, 2, 6, true, false, false, true));
    }


    @Test
    void partialScrapeExceptionPreservesCollectedLots() {
        Lot lot = TixxaScraper.parseLot(
                EVENT,
                "https://tixxa.co/concert-123/456",
                TEXT
        );

        RuntimeException cause =
                new IllegalStateException("Не удалось открыть страницу 32");

        TixxaScraper.PartialScrapeException exception =
                new TixxaScraper.PartialScrapeException(
                        "Неполная выгрузка: 1 лот",
                        cause,
                        List.of(lot)
                );

        assertAll(
                () -> assertEquals(1, exception.getPartialLots().size()),
                () -> assertEquals("tixxa:456",
                        exception.getPartialLots().get(0).lotId()),
                () -> assertEquals(2,
                        exception.getPartialLots().get(0).qty()),
                () -> assertSame(cause, exception.getCause())
        );
    }

    @Test
    void partialScrapeExceptionPreservesAllCollectedLots() {
        Lot first = TixxaScraper.parseLot(
                EVENT,
                "https://tixxa.co/concert-123/456",
                TEXT
        );

        Lot second = TixxaScraper.parseLot(
                EVENT,
                "https://tixxa.co/concert-123/457",
                TEXT
        );

        TixxaScraper.PartialScrapeException exception =
                new TixxaScraper.PartialScrapeException(
                        "Неполная выгрузка",
                        new IllegalStateException("Ошибка пагинации"),
                        List.of(first, second)
                );

        assertEquals(2, exception.getPartialLots().size());

        assertEquals(
                List.of("tixxa:456", "tixxa:457"),
                exception.getPartialLots()
                        .stream()
                        .map(Lot::lotId)
                        .toList()
        );
    }

    @Test
    void partialScrapeExceptionContainsOriginalError() {
        IllegalStateException cause = new IllegalStateException(
                "tixxa: не удалось открыть страницу 32; "
                + "прочитано 31, лотов 186"
        );

        TixxaScraper.PartialScrapeException exception =
                new TixxaScraper.PartialScrapeException(
                        "Неполная выгрузка: 186 лотов. "
                        + cause.getMessage(),
                        cause,
                        List.of()
                );

        assertAll(
                () -> assertTrue(
                        exception.getMessage().contains("186 лотов")
                ),
                () -> assertTrue(
                        exception.getMessage().contains("страницу 32")
                ),
                () -> assertSame(cause, exception.getCause())
        );
    }

    @Test
    void partialScrapeExceptionMakesDefensiveCopy() {
        Lot lot = TixxaScraper.parseLot(
                EVENT,
                "https://tixxa.co/concert-123/456",
                TEXT
        );

        List<Lot> original = new ArrayList<>();
        original.add(lot);

        TixxaScraper.PartialScrapeException exception =
                new TixxaScraper.PartialScrapeException(
                        "Неполная выгрузка",
                        new IllegalStateException("Ошибка"),
                        original
                );

        original.clear();

        assertEquals(1, exception.getPartialLots().size());

        assertThrows(
                UnsupportedOperationException.class,
                () -> exception.getPartialLots().clear()
        );
    }

    @Test
    void partialScrapeExceptionCanBeCaughtSeparately() {
        Lot lot = TixxaScraper.parseLot(
                EVENT,
                "https://tixxa.co/concert-123/456",
                TEXT
        );

        List<Lot> collected = new ArrayList<>();

        try {
            throw new TixxaScraper.PartialScrapeException(
                    "Ошибка страницы 32",
                    new IllegalStateException("Timeout"),
                    List.of(lot)
            );
        } catch (TixxaScraper.PartialScrapeException ex) {
            collected.addAll(ex.getPartialLots());
        } catch (Exception ex) {
            fail("Ошибка должна обрабатываться как частичная выгрузка");
        }

        assertEquals(1, collected.size());
        assertEquals("tixxa:456", collected.get(0).lotId());
    }
}
