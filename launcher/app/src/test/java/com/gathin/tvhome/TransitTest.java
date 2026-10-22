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

    // Västtrafik /journeys, the shape vitail_vasttrafik.py reads: a two-leg trip with a cancelled second leg.
    private static final String JOURNEYS = "{\"results\":["
            + "{\"isDeparted\":false,\"tripLegs\":[{\"origin\":{\"plannedTime\":\"2026-10-22T12:05:00+02:00\",\"estimatedTime\":\"2026-10-22T12:07:00+02:00\"},"
            + "\"serviceJourney\":{\"line\":{\"shortName\":\"22\"}}},{\"isCancelled\":true,\"origin\":{\"plannedTime\":\"2026-10-22T12:30:00+02:00\"},"
            + "\"serviceJourney\":{\"line\":{\"shortName\":\"16\"}}}]},"
            + "{\"isDeparted\":false,\"tripLegs\":[]},"
            + "{\"isDeparted\":false,\"tripLegs\":[{\"origin\":{\"plannedTime\":\"2026-10-22T12:35:00+02:00\"},\"serviceJourney\":{\"line\":{\"name\":\"Buss 22\"}}}]}"
            + "]}";

    @Test
    public void directJourneysReadLikeSleipnerTrips() throws Exception {
        List<Transit.Departure> d = Transit.parseTrips(Transit.journeysAsTrips(JOURNEYS), NOW, 2);
        assertEquals(2, d.size());
        assertEquals("22", d.get(0).line);
        assertEquals("12:07", d.get(0).clock(STOCKHOLM));
        assertEquals(2, d.get(0).minutesLate());
        assertTrue(d.get(0).cancelled);
        assertEquals("Buss 22", d.get(1).line);
    }

    @Test
    public void directHelpers() throws Exception {
        assertEquals("Basic aWQ6c2VjcmV0", Transit.basicAuth("id", "secret"));
        assertEquals("abc", Transit.parseToken("{\"access_token\":\"abc\",\"expires_in\":86400}")[0]);
        assertNull(Transit.parseToken("{}"));
        assertEquals("9021014004230000", Transit.parseVtStopGid("{\"results\":[{\"gid\":\"9021014004230000\"}]}"));
        assertEquals("https://ext-api.vasttrafik.se/pr/v4/journeys?originGid=1&destinationGid=2"
                        + "&dateTime=2026-10-22T09:15:00Z&dateTimeRelatesTo=departure&limit=30",
                Transit.vtJourneysUrl("1", "2", NOW));
    }

    @Test
    public void emptyOrMissingTrips() throws Exception {
        assertEquals(0, Transit.parseTrips("{}", NOW, 2).size());
    }

    // /stop-areas/{gid}/departures at Majvik: the 34 in from Hjuvik, the x6, and a 34 out to Hjuvik.
    private static final String BOARD = "{\"results\":["
            + "{\"plannedTime\":\"2026-10-22T12:04:00+02:00\",\"estimatedTime\":\"2026-10-22T12:05:00+02:00\",\"isCancelled\":false,"
            + "\"serviceJourney\":{\"direction\":\"Eketrägatan, Påstigning fram\",\"line\":{\"shortName\":\"34\"}}},"
            + "{\"plannedTime\":\"2026-10-22T12:36:00+02:00\",\"isCancelled\":false,"
            + "\"serviceJourney\":{\"direction\":\"Göteborg, Landvetter centrum, Påstigning fram\",\"line\":{\"shortName\":\"X6\"}}},"
            + "{\"plannedTime\":\"2026-10-22T12:10:00+02:00\",\"isCancelled\":false,"
            + "\"serviceJourney\":{\"direction\":\"Hjuvik, Påstigning fram\",\"line\":{\"shortName\":\"34\"}}}"
            + "]}";

    @Test
    public void boardShowsEveryLineInButNotOutToTheHomeStops() throws Exception {
        List<Transit.Departure> d = Transit.parseTrips(
                Transit.departuresAsTrips(BOARD, java.util.Arrays.asList("Majvik", "Hjuvik")), NOW, 2);
        assertEquals(2, d.size());
        assertEquals("34", d.get(0).line);
        assertEquals("12:05", d.get(0).clock(STOCKHOLM));
        assertEquals("X6", d.get(1).line);
    }

    @Test
    public void headsToReadsTheEndStopBeforeVia() {
        assertTrue(Transit.headsTo("Hjuvik via Torslanda", java.util.Arrays.asList("hjuvik")));
        assertTrue(!Transit.headsTo("Amhult via Hjuvik", java.util.Arrays.asList("Hjuvik")));
        assertTrue(Transit.headsTo("Lilla Varholmen, Påstigning fram", java.util.Arrays.asList("Lilla Varholmen")));
        assertTrue(!Transit.headsTo("Göteborg, Landvetter centrum, Påstigning fram",
                java.util.Arrays.asList("Hjuvik", "Lilla Varholmen")));
    }
}
