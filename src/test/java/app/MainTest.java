package app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test void thresholdStartsAtQuarterOfEvents() {
        assertFalse(Main.tooManyFailures(20, 4));
        assertTrue(Main.tooManyFailures(20, 5));
        assertTrue(Main.tooManyFailures(3, 1));
        assertFalse(Main.tooManyFailures(3, 0));
    }
    @Test void invalidCountersFailFast() {
        assertThrows(IllegalArgumentException.class, () -> Main.tooManyFailures(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Main.tooManyFailures(3, 4));
    }
}
