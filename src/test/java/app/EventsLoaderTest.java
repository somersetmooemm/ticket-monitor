package app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsLoaderTest {
    @Test
    void bundledEventConfigurationLoads() throws Exception {
        var events = EventsLoader.load();
        assertEquals(20, events.size());
        assertTrue(events.stream().allMatch(e -> !e.url().isBlank() && !e.platform().isBlank()));
        assertTrue(events.stream().allMatch(e -> e.platform().equals("tixxa") || e.platform().equals("tixbay")));
    }
}
