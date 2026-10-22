package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

public class TransitTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-22T12:00:00+02:00");
    private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    // Sleipner's /api/trips: a late bus planned before now, an on-time one, one already gone, one cancelled.
    private static final String TRIPS = "{\"trips\":["
            + "{\"departed\":false,\"cancelled\":false,\"lines\":[\"22\"],\"depPlanned\":\"2026-10-22T11:55:00+02:00\",\"depEstimated\":\"2026-10-22T12:03:00+02:00\"},"
            + "{\"departed\":false,\"cancelled\":false,\"lines\":[\"22\",\"X1\"],\"depPlanned\":\"2026-10-22T12:25:00+02:00\",\"depEstimated\":null},"
            + "{\"departed\":true,\"cancelled\":false,\"lines\":[\"22\"],\"depPlanned\":\"2026-10-22T11:40:00+02:00\",\"depEstimated\":\"2026-10-22T11:41:00+02:00\"},"
            + "{\"departed\":false,\"cancelled\":true,\"lines\":[\"114\"],\"depPlanned\":\"2026-10-22T12:10:00+02:00\",\"depEstimated\":null}"
            + "],\"now\":\"2026-10-22T10:00:00+00:00\"}";

    @Test
    public void nextTwoBySoonestRealTime() throws Exception {
        List<Transit.Departure> d = Transit.parseTrips(TRIPS, NOW, 2);
        assertEquals(2, d.size());
        assertEquals("22", d.get(0).line);
        assertEquals("12:03", d.get(0).clock(STOCKHOLM));
        assertEquals(8, d.get(0).minutesLate());
        assertEquals("114", d.get(1).line);
        assertTrue(d.get(1).cancelled);
    }

    @Test
    public void onTimeAndUnknownEstimateAreNotLate() throws Exception {
        Transit.Departure later = Transit.parseTrips(TRIPS, NOW, 5).get(2);
        assertEquals("12:25", later.clock(STOCKHOLM));
        assertEquals(0, later.minutesLate());
    }

    @Test
    public void stopGidIsTheFirstValidOne() throws Exception {
        assertEquals("9021014004230000", Transit.parseStopGid(
                "{\"stops\":[{\"gid\":\"x\",\"name\":\"?\"},{\"gid\":\"9021014004230000\",\"name\":\"Hjuvik, Göteborg\"}]}"));
        assertNull(Transit.parseStopGid("{\"stops\":[]}"));
    }

    @Test
    public void emptyOrMissingTrips() throws Exception {
        assertEquals(0, Transit.parseTrips("{}", NOW, 2).size());
    }
}
