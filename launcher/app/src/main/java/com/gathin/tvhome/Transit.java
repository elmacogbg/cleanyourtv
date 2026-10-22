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

    // ---- direct: Västtrafik Planera Resa v4 with the owner's own keys, the calls vitail_vasttrafik.py makes ----

    static final String VT_TOKEN_URL = "https://ext-api.vasttrafik.se/token";
    static final String VT_API = "https://ext-api.vasttrafik.se/pr/v4";

    /** "Basic base64(id:secret)" for the token request. */
    static String basicAuth(String clientId, String secret) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** {access_token, expires_in seconds}; null when the answer has no token. */
    static Object[] parseToken(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        String token = o.optString("access_token", "");
        if (token.isEmpty()) return null;
        return new Object[]{token, o.optLong("expires_in", 300)};
    }

    static String vtStopsUrl(String query) throws java.io.UnsupportedEncodingException {
        return VT_API + "/locations/by-text?q=" + URLEncoder.encode(query, "UTF-8") + "&types=stoparea&limit=8";
    }

    /** Journeys planned from 45 minutes ago on, as vitail's live_trips asks, so a late bus is still listed. */
    static String vtJourneysUrl(String fromGid, String toGid, OffsetDateTime now) {
        String when = now.minusMinutes(45).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        return VT_API + "/journeys?originGid=" + fromGid + "&destinationGid=" + toGid
                + "&dateTime=" + when + "&dateTimeRelatesTo=departure&limit=30";
    }

    /**
     * The stop's departure board from 30 minutes ago on, so a late bus is still listed: every line that leaves,
     * with no planner in between deciding which way to town is best.
     */
    static String vtDeparturesUrl(String stopGid, OffsetDateTime now) {
        String when = now.minusMinutes(30).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        return VT_API + "/stop-areas/" + stopGid + "/departures?startDateTime=" + when
                + "&timeSpanInMinutes=240&limit=60";
    }

    /**
     * /stop-areas/{gid}/departures in Sleipner's trip shape, so {@link #parseTrips} reads it. A departure whose
     * direction is one of {@code homeStops} (the stops on the screen) is heading out to them, not in, and is
     * dropped; nothing else is assumed about where a line goes.
     */
    static String departuresAsTrips(String body, List<String> homeStops) throws Exception {
        return departuresAsTrips(body, homeStops, null);
    }

    static String departuresAsTrips(String body, List<String> homeStops, StringBuilder seen) throws Exception {
        JSONArray results = new JSONObject(body).optJSONArray("results");
        JSONArray trips = new JSONArray();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                String planned = r.optString("plannedTime", "");
                if (planned.isEmpty()) continue;
                JSONObject sj = r.optJSONObject("serviceJourney");
                JSONObject line = sj == null ? null : sj.optJSONObject("line");
                String name = line == null ? "" : line.optString("shortName", line.optString("name", ""));
                String direction = sj == null ? "" : sj.optString("direction", "");
                boolean out = headsTo(direction, homeStops);
                // What the board said, so a missing bus can be explained: "34 13:33 Amhult" or "x6 ... (ut)".
                if (seen != null) seen.append(i == 0 ? "" : " | ").append(name).append(' ')
                        .append(planned.length() >= 16 ? planned.substring(11, 16) : planned).append(' ')
                        .append(direction).append(out ? " (ut)" : "");
                if (out) continue;
                JSONObject t = new JSONObject();
                t.put("departed", false);
                t.put("cancelled", r.optBoolean("isCancelled", false) || r.optBoolean("isPartCancelled", false));
                t.put("lines", new JSONArray().put(name));
                t.put("depPlanned", planned);
                t.put("depEstimated", r.optString("estimatedTime", ""));
                trips.put(t);
            }
        }
        JSONObject out = new JSONObject();
        out.put("trips", trips);
        return out.toString();
    }

    /** "Hjuvik" or "Hjuvik via Torslanda" heads to Hjuvik: the end stop is the part before " via ". */
    static boolean headsTo(String direction, List<String> stops) {
        String end = direction.split(" via ", 2)[0].trim();
        for (String s : stops) if (end.equalsIgnoreCase(s.trim())) return true;
        return false;
    }

    /** /locations/by-text: the first stop area's gid, or null. */
    static String parseVtStopGid(String body) throws Exception {
        JSONArray results = new JSONObject(body).optJSONArray("results");
        if (results == null) return null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            String gid = r == null ? "" : r.optString("gid", "");
            if (gid.matches("\\d{16}")) return gid;
        }
        return null;
    }

    /**
     * /journeys turned into Sleipner's trip shape (vitail_vasttrafik._trip) so {@link #parseTrips} reads both: the
     * first leg's departure, its line, departed, and cancelled when any leg is.
     */
    static String journeysAsTrips(String body) throws Exception {
        JSONArray results = new JSONObject(body).optJSONArray("results");
        JSONArray trips = new JSONArray();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                JSONArray legs = r == null ? null : r.optJSONArray("tripLegs");
                if (legs == null || legs.length() == 0) continue;   // walk-only: no vehicle to wait for
                JSONObject first = legs.optJSONObject(0);
                JSONObject origin = first == null ? null : first.optJSONObject("origin");
                String planned = origin == null ? "" : origin.optString("plannedTime", "");
                if (planned.isEmpty()) continue;
                boolean cancelled = false;
                JSONArray lines = new JSONArray();
                for (int j = 0; j < legs.length(); j++) {
                    JSONObject leg = legs.optJSONObject(j);
                    if (leg == null) continue;
                    cancelled |= leg.optBoolean("isCancelled", false) || leg.optBoolean("isPartCancelled", false);
                    JSONObject sj = leg.optJSONObject("serviceJourney");
                    JSONObject line = sj == null ? null : sj.optJSONObject("line");
                    String name = line == null ? "" : line.optString("shortName", line.optString("name", ""));
                    if (!name.isEmpty()) lines.put(name);
                }
                JSONObject t = new JSONObject();
                t.put("departed", r.optBoolean("isDeparted", false));
                t.put("cancelled", cancelled);
                t.put("lines", lines);
                t.put("depPlanned", planned);
                t.put("depEstimated", origin.optString("estimatedTime", ""));
                trips.put(t);
            }
        }
        JSONObject out = new JSONObject();
        out.put("trips", trips);
        return out.toString();
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
