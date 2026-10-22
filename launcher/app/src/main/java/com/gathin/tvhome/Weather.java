package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Today's weather as Njord (beta.vitail.app/njord) shows it: YR, SMHI and Open-Meteo side by side, merged with
 * medians, plus a confidence score from how closely the sources agree. The day is split into Njord's four
 * six-hour periods. Parsing and merging are plain Java so they run in JVM tests; only {@link #fetch} touches the
 * network.
 *
 * <p>Unlike Njord, every source is grouped by the TV's local date and hour. Njord slices YR's and SMHI's UTC
 * timestamps directly, which puts the first hours of a Swedish day on the day before.
 */
final class Weather {

    static final int[] SLOTS = {0, 6, 12, 18};
    /** Njord's CONF_TEMP_SIGMA and CONF_WIND_SIGMA. */
    private static final double TEMP_SIGMA = 1.75, WIND_SIGMA = 2.5;

    private Weather() {}

    /** One source's hourly rows, grouped into a day and its four periods. */
    static final class Bucket {
        final List<Double> temps = new ArrayList<>();
        final List<Double> winds = new ArrayList<>();
        final List<Double> dirs = new ArrayList<>();
        double precip;
        /** YR's symbol for the period (the first one seen), or null. */
        String symbol;

        Integer tempMin() { return temps.isEmpty() ? null : (int) Math.round(Collections.min(temps)); }
        Integer tempMax() { return temps.isEmpty() ? null : (int) Math.round(Collections.max(temps)); }
        Integer windMax() { return winds.isEmpty() ? null : (int) Math.round(Collections.max(winds)); }
        Double windDir() { return circularMean(dirs); }
        boolean isEmpty() { return temps.isEmpty() && winds.isEmpty(); }
    }

    /** One source's view of one day. */
    static final class Source {
        final String name;
        final Bucket day = new Bucket();
        final Map<Integer, Bucket> periods = new HashMap<>();

        Source(String name) { this.name = name; }

        Bucket period(int slot) {
            Bucket b = periods.get(slot);
            if (b == null) periods.put(slot, b = new Bucket());
            return b;
        }
    }

    /** One merged period: medians across the sources that have it. */
    static final class Period {
        final int slot;
        final Integer tempMin, tempMax, wind;
        final Double windDir, precip;
        final String symbol;

        Period(int slot, Integer tempMin, Integer tempMax, Integer wind, Double windDir, Double precip, String symbol) {
            this.slot = slot;
            this.tempMin = tempMin;
            this.tempMax = tempMax;
            this.wind = wind;
            this.windDir = windDir;
            this.precip = precip;
            this.symbol = symbol;
        }
    }

    /** Today, merged. Fields are null when no source had them. */
    static final class Summary {
        final LocalDate date;
        final Integer tempMin, tempMax, wind, confidence;
        final Double windDir, precip;
        final List<Period> periods;
        final List<String> sources;

        Summary(LocalDate date, Integer tempMin, Integer tempMax, Integer wind, Double windDir, Double precip,
                Integer confidence, List<Period> periods, List<String> sources) {
            this.date = date;
            this.tempMin = tempMin;
            this.tempMax = tempMax;
            this.wind = wind;
            this.windDir = windDir;
            this.precip = precip;
            this.confidence = confidence;
            this.periods = periods;
            this.sources = sources;
        }
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    /** api.met.no locationforecast/2.0/compact. */
    static Source parseYr(String body, LocalDate day, ZoneId zone) throws Exception {
        Source src = new Source("YR");
        JSONArray ts = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            LocalDateTime t = localTime(e.getString("time"), zone);
            if (!t.toLocalDate().equals(day)) continue;
            JSONObject data = e.getJSONObject("data");
            JSONObject inst = data.getJSONObject("instant").getJSONObject("details");
            JSONObject next1 = data.optJSONObject("next_1_hours");
            JSONObject next6 = data.optJSONObject("next_6_hours");
            JSONObject step = next1 != null ? next1 : next6;
            double precip = 0;
            if (step != null && step.optJSONObject("details") != null) {
                precip = step.getJSONObject("details").optDouble("precipitation_amount", 0);
            }
            String symbol = null;
            JSONObject sym = next6 != null ? next6 : next1;
            if (sym != null && sym.optJSONObject("summary") != null) {
                symbol = sym.getJSONObject("summary").optString("symbol_code", null);
            }
            add(src, t, num(inst, "air_temperature"), num(inst, "wind_speed"), num(inst, "wind_from_direction"),
                    precip, symbol);
        }
        return src;
    }

    /** SMHI snow1g/version/1 point forecast, the endpoint Njord uses. */
    static Source parseSmhi(String body, LocalDate day, ZoneId zone) throws Exception {
        Source src = new Source("SMHI");
        JSONArray ts = new JSONObject(body).getJSONArray("timeSeries");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.optJSONObject(i);
            if (e == null || !e.has("time") || e.optJSONObject("data") == null) continue;
            LocalDateTime t = localTime(e.getString("time"), zone);
            if (!t.toLocalDate().equals(day)) continue;
            JSONObject d = e.getJSONObject("data");
            add(src, t, num(d, "air_temperature"), num(d, "wind_speed"), num(d, "wind_direction"),
                    d.optDouble("precipitation_amount_mean", 0), null);
        }
        return src;
    }

    /** Open-Meteo hourly forecast, asked for in the TV's time zone (see {@link #openMeteoUrl}). */
    static Source parseOpenMeteo(String body, LocalDate day, ZoneId zone) throws Exception {
        Source src = new Source("Open-Meteo");
        JSONObject h = new JSONObject(body).getJSONObject("hourly");
        JSONArray time = h.getJSONArray("time");
        JSONArray temp = h.getJSONArray("temperature_2m");
        JSONArray wind = h.getJSONArray("windspeed_10m");
        JSONArray dir = h.getJSONArray("winddirection_10m");
        JSONArray rain = h.getJSONArray("precipitation");
        for (int i = 0; i < time.length(); i++) {
            LocalDateTime t = localTime(time.getString(i), zone);
            if (!t.toLocalDate().equals(day)) continue;
            add(src, t, num(temp, i), num(wind, i), num(dir, i), rain.isNull(i) ? 0 : rain.getDouble(i), null);
        }
        return src;
    }

    private static void add(Source src, LocalDateTime t, Double temp, Double wind, Double dir, double precip,
                            String symbol) {
        Bucket p = src.period((t.getHour() / 6) * 6);
        for (Bucket b : new Bucket[]{src.day, p}) {
            if (temp != null) b.temps.add(temp);
            if (wind != null) b.winds.add(wind);
            if (dir != null) b.dirs.add(dir);
            if (!Double.isNaN(precip)) b.precip += precip;
        }
        if (p.symbol == null && symbol != null) p.symbol = symbol;
    }

    /** "2026-10-22T09:00:00Z" (YR, SMHI) as an instant, "2026-10-22T09:00" (Open-Meteo) as already local. */
    static LocalDateTime localTime(String iso, ZoneId zone) {
        if (iso.endsWith("Z") || iso.matches(".*[+-]\\d\\d:?\\d\\d$")) {
            return ZonedDateTime.ofInstant(Instant.parse(normaliseOffset(iso)), zone).toLocalDateTime();
        }
        return LocalDateTime.parse(iso.length() == 16 ? iso + ":00" : iso);
    }

    private static String normaliseOffset(String iso) {
        if (iso.endsWith("Z")) return iso;
        return java.time.OffsetDateTime.parse(iso).toInstant().toString();
    }

    /** Android's org.json throws checked exceptions from getDouble, so read through opt and NaN. */
    private static Double num(JSONObject o, String key) {
        double v = o.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private static Double num(JSONArray a, int i) {
        double v = a.optDouble(i, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    // ---- merging -------------------------------------------------------------------------------------------

    static Summary summarize(List<Source> all, LocalDate day) {
        List<Source> sources = new ArrayList<>();
        for (Source s : all) if (s != null && !s.day.isEmpty()) sources.add(s);
        if (sources.isEmpty()) return null;

        List<Double> mins = new ArrayList<>(), maxs = new ArrayList<>(), winds = new ArrayList<>(),
                dirs = new ArrayList<>(), precips = new ArrayList<>(), mids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Source s : sources) {
            names.add(s.name);
            Integer mn = s.day.tempMin(), mx = s.day.tempMax(), w = s.day.windMax();
            if (mn != null && mx != null) {
                mins.add((double) mn);
                maxs.add((double) mx);
                mids.add((mn + mx) / 2.0);
            }
            if (w != null) winds.add((double) w);
            Double d = s.day.windDir();
            if (d != null) dirs.add(d);
            precips.add(round1(s.day.precip));
        }

        List<Period> periods = new ArrayList<>();
        for (int slot : SLOTS) {
            List<Double> pmin = new ArrayList<>(), pmax = new ArrayList<>(), pw = new ArrayList<>(),
                    pd = new ArrayList<>(), pp = new ArrayList<>();
            String symbol = null;
            for (Source s : sources) {
                Bucket b = s.periods.get(slot);
                if (b == null || b.isEmpty()) continue;
                if (b.tempMin() != null) pmin.add((double) b.tempMin());
                if (b.tempMax() != null) pmax.add((double) b.tempMax());
                if (b.windMax() != null) pw.add((double) b.windMax());
                if (b.windDir() != null) pd.add(b.windDir());
                pp.add(round1(b.precip));
                if (symbol == null) symbol = b.symbol;
            }
            if (pmin.isEmpty() && pw.isEmpty()) continue;
            periods.add(new Period(slot, roundOrNull(median(pmin)), roundOrNull(median(pmax)),
                    roundOrNull(median(pw)), circularMean(pd), median(pp), symbol));
        }

        return new Summary(day, roundOrNull(median(mins)), roundOrNull(median(maxs)), roundOrNull(median(winds)),
                circularMean(dirs), median(precips), agreement(mids, winds), periods, names);
    }

    /** Njord's computeDayAgreement: Gaussian on the spread of temperature midpoints and peak winds. */
    static Integer agreement(List<Double> mids, List<Double> winds) {
        List<Double> parts = new ArrayList<>();
        if (mids.size() >= 2) parts.add(confidence(stdDev(mids), TEMP_SIGMA));
        if (winds.size() >= 2) parts.add(confidence(stdDev(winds), WIND_SIGMA));
        if (parts.isEmpty()) return null;
        double sum = 0;
        for (double p : parts) sum += p;
        return (int) Math.round(Math.min(100, Math.max(0, sum / parts.size())));
    }

    private static double confidence(double std, double sigma) {
        if (sigma <= 0 || std <= 0) return 100;
        return 100 * Math.exp(-(std * std) / (2 * sigma * sigma));
    }

    /** Sample standard deviation; 0 below two values. */
    static double stdDev(List<Double> v) {
        int n = v.size();
        if (n < 2) return 0;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= n;
        double ss = 0;
        for (double x : v) ss += (x - mean) * (x - mean);
        return Math.sqrt(ss / (n - 1));
    }

    static Double median(List<Double> v) {
        if (v.isEmpty()) return null;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }

    /** Mean wind direction; null when the directions cancel out, so no misleading "N". */
    static Double circularMean(List<Double> degs) {
        if (degs.isEmpty()) return null;
        double sx = 0, sy = 0;
        for (double d : degs) {
            sx += Math.cos(Math.toRadians(d));
            sy += Math.sin(Math.toRadians(d));
        }
        if (Math.sqrt(sx * sx + sy * sy) / degs.size() < 1e-6) return null;
        return (Math.toDegrees(Math.atan2(sy, sx)) + 360) % 360;
    }

    private static Integer roundOrNull(Double d) { return d == null ? null : (int) Math.round(d); }

    private static double round1(double d) { return Math.round(d * 10) / 10.0; }

    // ---- presentation helpers ------------------------------------------------------------------------------

    /** Wind comes FROM this direction; Swedish compass letters. */
    static String compass(Double deg) {
        if (deg == null) return "";
        return new String[]{"N", "NO", "O", "SO", "S", "SV", "V", "NV"}[(int) Math.round(deg / 45) % 8];
    }

    /** "00–06" as in Njord's period header. */
    static String slotLabel(int slot) {
        return String.format(java.util.Locale.ROOT, "%02d–%02d", slot, slot + 6);
    }

    /** A glyph for YR's symbol_code, or for rain/no rain when there is none. */
    static String glyph(String symbol, Double precip) {
        if (symbol != null) {
            String s = symbol.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
            if (s.contains("thunder")) return "⛈";          // ⛈
            if (s.contains("snow") || s.contains("sleet")) return "❄"; // ❄
            if (s.contains("rain")) return "☔";             // ☔
            if (s.equals("fog")) return "☁";                // ☁
            if (s.equals("cloudy")) return "☁";
            if (s.equals("partlycloudy") || s.equals("fair")) return "⛅"; // ⛅
            if (s.equals("clearsky")) return "☀";           // ☀
        }
        if (precip != null && precip >= 0.3) return "☔";
        return "";
    }

    // ---- network -------------------------------------------------------------------------------------------

    static String yrUrl(double lat, double lon) {
        return String.format(java.util.Locale.ROOT,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
    }

    static String smhiUrl(double lat, double lon) {
        return String.format(java.util.Locale.ROOT,
                "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%.2f/lat/%.2f/data.json",
                lon, lat);
    }

    static String openMeteoUrl(double lat, double lon, ZoneId zone) {
        return String.format(java.util.Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&hourly=temperature_2m,windspeed_10m,winddirection_10m,precipitation"
                        + "&windspeed_unit=ms&forecast_days=2&timezone=%s", lat, lon, zone.getId());
    }

    /**
     * All three sources for today, each allowed to fail on its own. Blocking: call it off the main thread.
     * YR's terms ask for an identifying User-Agent.
     */
    static Summary fetch(double lat, double lon, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        List<Source> sources = new ArrayList<>();
        try {
            sources.add(parseYr(Http.get(yrUrl(lat, lon)), today, zone));
        } catch (Exception e) {
            Http.log("YR", e);
        }
        try {
            sources.add(parseSmhi(Http.get(smhiUrl(lat, lon)), today, zone));
        } catch (Exception e) {
            Http.log("SMHI", e);
        }
        try {
            sources.add(parseOpenMeteo(Http.get(openMeteoUrl(lat, lon, zone)), today, zone));
        } catch (Exception e) {
            Http.log("Open-Meteo", e);
        }
        return summarize(sources, today);
    }
}
