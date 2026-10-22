package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Next departures from a few stops towards one destination, through Sleipner's API on beta.vitail.app
 * (vitail_web/sleipner.py in elmacogbg/vitail). Sleipner holds the Västtrafik keys and calls Planera Resa v4, so
 * no secret ever reaches the TV. {@code /api/trips} without a time returns the trips that have not left yet,
 * including late ones planned up to 45 minutes ago, with Västtrafik's real-time estimate when there is one.
 * Parsing is plain Java for the JVM tests; only {@link Http} touches the network.
 */
final class Transit {

    static final String DEFAULT_API = "https://beta.vitail.app/sleipner/api";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private Transit() {}

    /** One departure from the stop, as the TV shows it. */
    static final class Departure {
        final String line;
        final OffsetDateTime planned, estimated;
        final boolean cancelled;

        Departure(String line, OffsetDateTime planned, OffsetDateTime estimated, boolean cancelled) {
            this.line = line;
            this.planned = planned;
            this.estimated = estimated;
            this.cancelled = cancelled;
        }

        /** When it actually leaves: the estimate when Västtrafik has one. */
        OffsetDateTime when() {
            return estimated != null ? estimated : planned;
        }

        /** Whole minutes late (estimate after plan), 0 when on time or unknown. */
        long minutesLate() {
            if (estimated == null) return 0;
            long m = Duration.between(planned, estimated).toMinutes();
            return Math.max(0, m);
        }

        String clock(ZoneId zone) {
            return when().atZoneSameInstant(zone).format(HH_MM);
        }
    }

    static String stopsUrl(String api, String query) throws java.io.UnsupportedEncodingException {
        return api + "/stops?q=" + URLEncoder.encode(query, "UTF-8");
    }

    static String tripsUrl(String api, String fromGid, String toGid) {
        return api + "/trips?from=" + fromGid + "&to=" + toGid;
    }

    /** The first stop area Västtrafik matches for the name: its 16-digit gid, or null. */
    static String parseStopGid(String body) throws Exception {
        JSONArray stops = new JSONObject(body).optJSONArray("stops");
        if (stops == null) return null;
        for (int i = 0; i < stops.length(); i++) {
            JSONObject s = stops.optJSONObject(i);
            String gid = s == null ? "" : s.optString("gid", "");
            if (gid.matches("\\d{16}")) return gid;
        }
        return null;
    }

    /**
     * The next {@code max} departures that have not left, soonest (by real time) first. A cancelled trip stays in
     * the list, marked, so the screen says so instead of quietly showing the one after it.
     */
    static List<Departure> parseTrips(String body, OffsetDateTime now, int max) throws Exception {
        JSONArray trips = new JSONObject(body).optJSONArray("trips");
        List<Departure> out = new ArrayList<>();
        if (trips == null) return out;
        for (int i = 0; i < trips.length(); i++) {
            JSONObject t = trips.optJSONObject(i);
            if (t == null || t.optBoolean("departed", false)) continue;
            OffsetDateTime planned = time(t.optString("depPlanned", ""));
            if (planned == null) continue;
            OffsetDateTime estimated = time(t.optString("depEstimated", ""));
            JSONArray lines = t.optJSONArray("lines");
            String line = lines != null && lines.length() > 0 ? lines.optString(0, "") : "";
            Departure d = new Departure(line, planned, estimated, t.optBoolean("cancelled", false));
            if (d.when().isBefore(now.minusMinutes(1))) continue;   // left already, Västtrafik just has not said so
            out.add(d);
        }
        out.sort(Comparator.comparing(Departure::when));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static OffsetDateTime time(String iso) {
        if (iso == null || iso.isEmpty() || iso.equals("null")) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
