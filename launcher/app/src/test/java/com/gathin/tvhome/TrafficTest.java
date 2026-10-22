package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class TrafficTest {

    private static final long NOW = 1761134400000L;   // 2026-10-22 12:00 UTC-ish, the test's own clock

    private static String msg(int priority, int category, long created, String title, String text) {
        return "{\"priority\":" + priority + ",\"category\":" + category + ",\"createddate\":\"/Date(" + created
                + "+0200)/\",\"title\":\"" + title + "\",\"description\":\"" + text + "\"}";
    }

    @Test
    public void seriousRecentFirstPlannedAndMinorLeftOut() throws Exception {
        String body = "{\"messages\":["
                + msg(3, 0, NOW - 60_000, "Väg 155", "Kö vid Torslanda.") + ","
                + msg(1, 0, NOW - 60_000, "Oscarsleden", "Olycka, ett körfält avstängt.") + ","
                + msg(2, 2, NOW - 60_000, "E6", "Vägarbete i natt.") + ","
                + msg(5, 0, NOW - 60_000, "Väg 40", "Djur på vägen.") + ","
                + msg(2, 0, NOW - 7 * 3_600_000L, "E20", "Gammal kö.")
                + "]}";
        List<String> t = Traffic.parse(body, NOW, 5);
        assertEquals(2, t.size());
        assertEquals("Oscarsleden  ·  Olycka, ett körfält avstängt.", t.get(0));
        assertEquals("Väg 155  ·  Kö vid Torslanda.", t.get(1));
    }

    @Test
    public void readsSrDates() {
        assertEquals(1761134400000L, Traffic.msDate("/Date(1761134400000+0200)/"));
        assertEquals(0, Traffic.msDate(""));
    }
}
