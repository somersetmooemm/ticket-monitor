package app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TixbayScraperTest {
    private static final Event EVENT = new Event("tixbay", "BTS", "Bangkok", "2026-12-12", "https://tixbay.com/?eventId=123");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TixbayScraper scraper = new TixbayScraper();

    @Test void unknownAdjacentSeatsRemainUnknown() throws Exception {
        var json = mapper.readTree(""" 
            {"sellingAmount":4,"salePrice":2250,"currencyVO":{"code":"THB"},"areaName":"537区 HH排 座位隨機"}
            """);
        Lot lot = scraper.toLot(EVENT, "tixbay:42", json);
        assertNull(lot.maxRun());
        assertEquals("нет данных", lot.runs());
        assertEquals("Зона 537, ряд HH", lot.place());
        assertFalse(lot.place().contains("места подряд"));
        assertEquals(2250, lot.price());
        assertEquals(4, lot.qty());
    }

    @Test void adjacentOptionsAreExtracted() throws Exception {
        var json = mapper.readTree("""
            {"sellingAmount":4,"salePrice":1000,"availableNumbers":[1,2,4],"currencyVO":{"code":"THB"}}
            """);
        Lot lot = scraper.toLot(EVENT, "tixbay:43", json);
        assertEquals(Integer.valueOf(4), lot.maxRun());
        assertEquals("1, 2, 4", lot.runs());
    }

    @Test void emptyAdjacentOptionsRemainUnknown() throws Exception {
        var json = mapper.readTree("""
            {"sellingAmount":2,"salePrice":1000,"availableNumbers":[]}
            """);
        assertNull(scraper.toLot(EVENT, "tixbay:44", json).maxRun());
    }

    @Test void areaFormattingDoesNotPromiseSeats() {
        assertEquals("зона и ряд не указаны", TixbayScraper.place(""));
        assertEquals("Зона 537, ряд HH", TixbayScraper.place("537区 HH排 座位隨機"));
    }
}
