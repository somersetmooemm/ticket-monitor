package app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TixxaScraperTest {
    private static final Event EVENT = new Event("tixxa", "BTS", "Bangkok", "2026-12-03", "https://tixxa.co/concert-123");
    private static final String TEXT = "03 12/26 Zone W3O: Seat OL 08, OL 21 Ticket original price 3,300.00 THB Quantity 2 Tickets Allow partial purchase SPayLater 10,339.62 THB / Ticket";

    @Test void parsesActualListingIncludingPluralTickets() {
        Lot lot = TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-123/456", TEXT);
        assertEquals("tixxa:456", lot.lotId());
        assertEquals(2, lot.qty());
        assertEquals(10339.62, lot.price(), 0.001);
        assertEquals("THB", lot.currency());
        assertEquals("номинал 3300.00 THB", lot.category());
        assertNull(lot.maxRun());
    }

    @Test void invalidListingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-123/456", "unexpected DOM"));
    }

    @Test void foreignEventListingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TixxaScraper.parseLot(EVENT, "https://tixxa.co/concert-999/456", TEXT));
    }

    @Test void emptyOrRepeatedPageIsRejected() {
        assertThrows(IllegalStateException.class, () -> TixxaScraper.requireNewLots(2, 0));
        assertDoesNotThrow(() -> TixxaScraper.requireNewLots(2, 6));
    }
}
