package app;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SheetsGuardTest {
    private static final Event E = new Event("tixxa", "BTS", "Bangkok", "2026-12-05", "https://tixxa.co/concert-123");

    private static List<List<Object>> old(int count) {
        List<List<Object>> rows = new ArrayList<>();
        for (int i=0; i<count; i++) {
            List<Object> r = new ArrayList<>(Collections.nCopies(16, ""));
            r.set(8, 1000); r.set(13, "2026-10-27 12:00"); r.set(14, "tixxa:" + i); r.set(15, E.url());
            rows.add(r);
        }
        return rows;
    }
    private static List<Lot> lots(int from, int to) {
        List<Lot> result = new ArrayList<>();
        for (int i=from; i<to; i++) result.add(new Lot(E, "tixxa:" + i, "cat", 1, "place", 1000, "THB", null, null, E.url()+"/"+i));
        return result;
    }

    @Test void dropFrom146To59PreservesOldData() {
        var guarded = SheetsWriter.detectSuspectEvents(old(146), lots(0,59), Set.of());
        assertTrue(guarded.contains(E.url()));
        var rows = SheetsWriter.prepareRows(old(146), List.of(), guarded, "2026-10-28 13:00");
        assertEquals(147, rows.size());
        assertEquals("НЕ ПРОВЕРЕНО", rows.get(1).get(0));
        assertEquals("2026-10-27 12:00", rows.get(1).get(13));
    }

    @Test void smallNormalChangeAllowed() {
        assertTrue(SheetsWriter.detectSuspectEvents(old(100), lots(0,90), Set.of()).isEmpty());
    }

    @Test void newlyAddedListingsDoNotHideBigLossOfOldIds() {
        var incoming = new ArrayList<>(lots(0,40));
        incoming.addAll(lots(100,200));
        assertTrue(SheetsWriter.detectSuspectEvents(old(100), incoming, Set.of()).contains(E.url()));
    }

    @Test void firstRunAndSmallEventNotBlocked() {
        assertTrue(SheetsWriter.detectSuspectEvents(List.of(), lots(0,4), Set.of()).isEmpty());
        assertTrue(SheetsWriter.detectSuspectEvents(old(5), lots(0,1), Set.of()).isEmpty());
    }

    @Test void explicitFailureAlwaysPreserved() {
        assertTrue(SheetsWriter.detectSuspectEvents(old(100), lots(0,100), Set.of(E.url())).contains(E.url()));
    }
}
