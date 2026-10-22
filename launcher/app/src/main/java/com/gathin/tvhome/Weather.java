package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Today's weather exactly as Njord (beta.vitail.app/njord, njord/njord.js in elmacogbg/vitail) shows it in its
 * summary view: YR, SMHI and Open-Meteo merged with medians, a confidence score from how closely they agree, and
 * the expanded day's four six-hour periods. It is a line-by-line port so the TV and the web app show the same
 * numbers: every source grouped by the place's local date and hour (YR's and SMHI's UTC timestamps converted to
 * the zone Open-Meteo reports), today's Open-Meteo row built from the remaining hours so it covers the same
 * hours as YR and SMHI, and "today" being Open-Meteo's first day. Parsing and merging are plain Java for the JVM
 * tests; only {@link #fetch} touches the network.
 */
final class Weather {

    static final int[] SLOTS = {0, 6, 12, 18};
    /** njord.js CONF_TEMP_SIGMA and CONF_WIND_SIGMA. */
    private static final double TEMP_SIGMA = 1.75, WIND_SIGMA = 2.5;

    private Weather() {}

    /** One source's day row (njord.js "days" entries). Null fields: the source had no value. */
    static final class Day {
        String date;
        Integer tempMin, tempMax, windMax, feelsMin, feelsMax, humidity, uv;
        Double windDir, precipMm;
    }

    /** One source's six-hour period (njord.js buildPeriodEntry). */
    static final class PeriodEntry {
        String date;
        int slot;
        Integer tempMin, tempMax, windMax;
        Double windDir, precipMm;
        /** YR's symbol_code for the period, for the icon; Njord shows none. */
        String symbol;
    }

    static final class Source {
        final String name;
        final List<Day> days = new ArrayList<>();
        final List<PeriodEntry> periods = new ArrayList<>();
        /** Open-Meteo only: the place's IANA zone, which YR and SMHI are grouped in. */
        String timezone;

        Source(String name) { this.name = name; }
    }

    /** One merged period of the expanded day. */
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

    /** Njord's summary block for one day. Null fields show as "—". */
    static final class Summary {
        String date;
        Integer tempMin, tempMax, feelsMin, feelsMax, wind, humidity, uv, confidence;
        Double windDir;
        /** buildSummaryPrecipLine, already formatted: "2.3 mm (2.1–8.3)". */
        String rain;
        final List<Period> periods = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    /** Per-date and per-period accumulators, njord.js byDate / byPeriod. */
    private static final class Acc {
        final List<Double> temps = new ArrayList<>(), winds = new ArrayList<>(), dirs = new ArrayList<>(),
                humidities = new ArrayList<>(), feels = new ArrayList<>();
        double precip;
        String date, symbol;
        int slot;
    }

    /** api.met.no locationforecast/2.0/compact, njord.js parseYr: UTC timestamps grouped in {@code zone}. */
    static Source parseYr(String body, ZoneId zone) throws Exception {
        Source src = new Source("YR");
        Map<String, Acc> byDate = new TreeMap<>(), byPeriod = new TreeMap<>();
        JSONArray ts = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            LocalDateTime local = local(e.getString("time"), zone);
            Acc d = acc(byDate, local.toLocalDate().toString(), 0);
            Acc p = acc(byPeriod, local.toLocalDate().toString(), (local.getHour() / 6) * 6);
            JSONObject data = e.getJSONObject("data");
            JSONObject inst = data.getJSONObject("instant").getJSONObject("details");
            Double t = num(inst, "air_temperature"), w = num(inst, "wind_speed"),
                    dir = num(inst, "wind_from_direction"), hum = num(inst, "relative_humidity");
            add(d, t, w, dir, hum);
            add(p, t, w, dir, null);
            JSONObject p1 = data.optJSONObject("next_1_hours"), p6 = data.optJSONObject("next_6_hours");
            double amt = 0;
            if (p1 != null && p1.optJSONObject("details") != null) {
                amt = p1.getJSONObject("details").optDouble("precipitation_amount", 0);
            } else if (p6 != null && p6.optJSONObject("details") != null) {
                amt = p6.getJSONObject("details").optDouble("precipitation_amount", 0);
            }
            if (Double.isNaN(amt)) amt = 0;
            d.precip += amt;
            p.precip += amt;
            JSONObject sym = p6 != null ? p6 : p1;
            if (p.symbol == null && sym != null && sym.optJSONObject("summary") != null) {
                String code = sym.getJSONObject("summary").optString("symbol_code", "");
                if (!code.isEmpty()) p.symbol = code;
            }
        }
        finish(src, byDate, byPeriod, false);
        return src;
    }

    /** SMHI snow1g/version/1 point forecast, njord.js parseSmhi: UTC timestamps grouped in {@code zone}. */
    static Source parseSmhi(String body, ZoneId zone) throws Exception {
        Source src = new Source("SMHI");
        Map<String, Acc> byDate = new TreeMap<>(), byPeriod = new TreeMap<>();
        JSONArray ts = new JSONObject(body).getJSONArray("timeSeries");
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.optJSONObject(i);
            if (e == null || !e.has("time") || e.optJSONObject("data") == null) continue;
            LocalDateTime local = local(e.getString("time"), zone);
            Acc d = acc(byDate, local.toLocalDate().toString(), 0);
            Acc p = acc(byPeriod, local.toLocalDate().toString(), (local.getHour() / 6) * 6);
            JSONObject row = e.getJSONObject("data");
            double prc = row.optDouble("precipitation_amount_mean", 0);
            if (Double.isNaN(prc)) prc = 0;
            Double t = num(row, "air_temperature"), w = num(row, "wind_speed"), dir = num(row, "wind_direction"),
                    hum = num(row, "relative_humidity");
            add(d, t, w, dir, hum);
            add(p, t, w, dir, null);
            d.precip += prc;
            p.precip += prc;
        }
        finish(src, byDate, byPeriod, true);
        return src;
    }

    /**
     * Open-Meteo in the place's own time zone, njord.js parseOpenMeteo. Today's row covers the same hours as YR's
     * and SMHI's, from the current hour to midnight, built from the hourly values; later days use the daily
     * endpoint, which covers the same full day as the others. UV only exists daily.
     */
    static Source parseOpenMeteo(String body, Instant now) throws Exception {
        Source src = new Source("Open-Meteo");
        JSONObject json = new JSONObject(body);
        String tz = json.optString("timezone", "");
        src.timezone = tz.isEmpty() ? null : tz;
        LocalDateTime nowLocal = ZonedDateTime.ofInstant(now, zone(src.timezone)).toLocalDateTime();
        String today = nowLocal.toLocalDate().toString();
        JSONObject h = json.optJSONObject("hourly");
        Acc rest = new Acc();
        if (h != null) {
            JSONArray ht = h.getJSONArray("time");
            for (int i = 0; i < ht.length(); i++) {
                String t = ht.getString(i);
                if (!t.startsWith(today) || hour(t) < nowLocal.getHour()) continue;
                Double temp = num(h.optJSONArray("temperature_2m"), i), feels = num(h.optJSONArray("apparent_temperature"), i);
                Double w = num(h.optJSONArray("windspeed_10m"), i), dir = num(h.optJSONArray("winddirection_10m"), i);
                Double rain = num(h.optJSONArray("precipitation"), i);
                if (temp != null) rest.temps.add(temp);
                if (w != null) rest.winds.add(w);
                if (dir != null) rest.dirs.add(dir);
                if (feels != null) rest.feels.add(feels);
                if (rain != null) rest.precip += rain;
            }
        }
        JSONObject daily = json.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        for (int i = 0; i < time.length(); i++) {
            Day d = new Day();
            d.date = time.getString(i);
            d.tempMin = round(num(daily.optJSONArray("temperature_2m_min"), i));
            d.tempMax = round(num(daily.optJSONArray("temperature_2m_max"), i));
            Double sum = num(daily.optJSONArray("precipitation_sum"), i);
            d.precipMm = fixed1(sum == null ? 0 : sum);
            d.windMax = round(num(daily.optJSONArray("windspeed_10m_max"), i));
            d.windDir = num(daily.optJSONArray("winddirection_10m_dominant"), i);
            d.feelsMin = round(num(daily.optJSONArray("apparent_temperature_min"), i));
            d.feelsMax = round(num(daily.optJSONArray("apparent_temperature_max"), i));
            d.uv = round(num(daily.optJSONArray("uv_index_max"), i));
            if (d.date.equals(today) && !rest.temps.isEmpty()) {
                d.tempMin = (int) Math.round(Collections.min(rest.temps));
                d.tempMax = (int) Math.round(Collections.max(rest.temps));
                d.precipMm = fixed1(rest.precip);
                if (!rest.winds.isEmpty()) d.windMax = (int) Math.round(Collections.max(rest.winds));
                Double dir = circularMean(rest.dirs);
                if (dir != null) d.windDir = dir;
                if (!rest.feels.isEmpty()) {
                    d.feelsMin = (int) Math.round(Collections.min(rest.feels));
                    d.feelsMax = (int) Math.round(Collections.max(rest.feels));
                }
            }
            if (d.date.compareTo(today) < 0) continue;   // Open-Meteo's own today is the first day shown
            src.days.add(d);
        }
        if (h != null) {
            Map<String, Acc> byPeriod = new TreeMap<>();
            JSONArray ht = h.getJSONArray("time");
            for (int i = 0; i < ht.length(); i++) {
                String t = ht.getString(i);
                Acc p = acc(byPeriod, t.substring(0, 10), slot(t));
                add(p, num(h.optJSONArray("temperature_2m"), i), num(h.optJSONArray("windspeed_10m"), i),
                        num(h.optJSONArray("winddirection_10m"), i), null);
                Double rain = num(h.optJSONArray("precipitation"), i);
                if (rain != null) p.precip += rain;
            }
            for (Acc p : byPeriod.values()) src.periods.add(period(p));
        }
        return src;
    }

    private static int slot(String iso) {
        return (hour(iso) / 6) * 6;
    }

    private static int hour(String iso) {
        return iso.length() >= 13 ? Integer.parseInt(iso.substring(11, 13)) : 0;
    }

    /** A UTC timestamp from YR or SMHI as the place's local date and time. */
    static LocalDateTime local(String isoUtc, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.parse(isoUtc), zone).toLocalDateTime();
    }

    /** Open-Meteo's zone for the place, or the TV's own when it is missing or unknown. */
    static ZoneId zone(String tz) {
        try {
            if (tz != null) return ZoneId.of(tz);
        } catch (Exception ignored) {
            // an id this Android does not know
        }
        return ZoneId.systemDefault();
    }

    private static Acc acc(Map<String, Acc> map, String date, int slot) {
        // Zero-padded slot so the TreeMap sorts ":06" before ":12", in date order like Object.keys(...).sort().
        String key = date + ":" + String.format(Locale.ROOT, "%02d", slot);
        Acc a = map.get(key);
        if (a == null) {
            a = new Acc();
            a.date = date;
            a.slot = slot;
            map.put(key, a);
        }
        return a;
    }

    private static void add(Acc a, Double t, Double w, Double dir, Double hum) {
        if (t != null) a.temps.add(t);
        if (w != null) a.winds.add(w);
        if (dir != null) a.dirs.add(dir);
        if (hum != null) a.humidities.add(hum);
        if (t != null && w != null) a.feels.add(feelsLike(t, w));
    }

    /** njord.js: dates sorted, first ten; SMHI drops days without temperatures and reads a windless day as 0. */
    private static void finish(Source src, Map<String, Acc> byDate, Map<String, Acc> byPeriod, boolean smhi) {
        int n = 0;
        for (Acc b : byDate.values()) {
            if (n++ >= 10) break;
            if (b.temps.isEmpty() && smhi) continue;
            Day d = new Day();
            d.date = b.date;
            d.tempMin = b.temps.isEmpty() ? null : (int) Math.round(Collections.min(b.temps));
            d.tempMax = b.temps.isEmpty() ? null : (int) Math.round(Collections.max(b.temps));
            d.precipMm = fixed1(b.precip);
            d.windMax = b.winds.isEmpty() ? (smhi ? Integer.valueOf(0) : null) : (int) Math.round(Collections.max(b.winds));
            d.windDir = circularMean(b.dirs);
            d.feelsMin = b.feels.isEmpty() ? null : (int) Math.round(Collections.min(b.feels));
            d.feelsMax = b.feels.isEmpty() ? null : (int) Math.round(Collections.max(b.feels));
            d.humidity = b.humidities.isEmpty() ? null : (int) Math.round(mean(b.humidities));
            src.days.add(d);
        }
        for (Acc p : byPeriod.values()) src.periods.add(period(p));
    }

    private static PeriodEntry period(Acc b) {
        PeriodEntry p = new PeriodEntry();
        p.date = b.date;
        p.slot = b.slot;
        p.tempMin = b.temps.isEmpty() ? null : (int) Math.round(Collections.min(b.temps));
        p.tempMax = b.temps.isEmpty() ? null : (int) Math.round(Collections.max(b.temps));
        p.windMax = b.winds.isEmpty() ? null : (int) Math.round(Collections.max(b.winds));
        p.windDir = circularMean(b.dirs);
        p.precipMm = fixed1(b.precip);
        p.symbol = b.symbol;
        return p;
    }

    /** njord.js computeFeelsLike: wind chill below 10 °C and above 4.8 km/h, otherwise the air temperature. */
    static double feelsLike(double t, double windMs) {
        double v = windMs * 3.6;
        if (t >= 10 || v <= 4.8) return t;
        return Math.round(13.12 + 0.6215 * t - 11.37 * Math.pow(v, 0.16) + 0.3965 * t * Math.pow(v, 0.16));
    }

    /** Android's org.json throws checked exceptions from getDouble, so read through opt and NaN. */
    private static Double num(JSONObject o, String key) {
        double v = o.optDouble(key, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    private static Double num(JSONArray a, int i) {
        if (a == null) return null;
        double v = a.optDouble(i, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    // ---- merging (njord.js appendSummaryDayBlock and appendPeriodsGrid) ------------------------------------

    /** Day 0 of every source, as Njord's first summary row; null when no source answered. */
    static Summary summarize(List<Source> all) {
        List<Source> sources = new ArrayList<>();
        for (Source s : all) if (s != null && !s.days.isEmpty()) sources.add(s);
        if (sources.isEmpty()) return null;
        Summary out = new Summary();
        out.date = refDate(sources);

        List<Double> mins = new ArrayList<>(), maxs = new ArrayList<>(), fmins = new ArrayList<>(),
                fmaxs = new ArrayList<>(), winds = new ArrayList<>(), dirs = new ArrayList<>(), rain = new ArrayList<>(),
                hums = new ArrayList<>(), uvs = new ArrayList<>(), mids = new ArrayList<>();
        for (Source s : sources) {
            out.sources.add(s.name);
            Day d = s.days.get(0);
            if (d.tempMin != null && d.tempMax != null) {
                mins.add((double) d.tempMin);
                maxs.add((double) d.tempMax);
                mids.add((d.tempMin + d.tempMax) / 2.0);
            }
            if (d.feelsMin != null && d.feelsMax != null) {
                fmins.add((double) d.feelsMin);
                fmaxs.add((double) d.feelsMax);
            }
            if (d.windMax != null) winds.add((double) d.windMax);
            if (d.windDir != null) dirs.add(d.windDir);
            if (d.precipMm != null) rain.add(d.precipMm);
            if (d.humidity != null) hums.add((double) d.humidity);
            if (d.uv != null) uvs.add((double) d.uv);
        }
        out.tempMin = round(median(mins));
        out.tempMax = round(median(maxs));
        out.feelsMin = round(median(fmins));
        out.feelsMax = round(median(fmaxs));
        out.wind = round(median(winds));
        out.windDir = winds.isEmpty() ? null : circularMean(dirs);
        out.humidity = round(median(hums));
        out.uv = round(median(uvs));
        out.rain = rainLine(rain);
        out.confidence = agreement(mids, winds);

        for (int slot : SLOTS) {
            List<Double> pmin = new ArrayList<>(), pmax = new ArrayList<>(), pw = new ArrayList<>(),
                    pd = new ArrayList<>(), pp = new ArrayList<>();
            String symbol = null;
            for (Source s : sources) {
                for (PeriodEntry p : s.periods) {
                    if (!p.date.equals(out.date) || p.slot != slot) continue;
                    if (p.tempMin != null) pmin.add((double) p.tempMin);
                    if (p.tempMax != null) pmax.add((double) p.tempMax);
                    if (p.windMax != null) pw.add((double) p.windMax);
                    if (p.windDir != null) pd.add(p.windDir);
                    if (p.precipMm != null) pp.add(p.precipMm);
                    if (symbol == null) symbol = p.symbol;
                    break;
                }
            }
            if (pmin.isEmpty() && pw.isEmpty()) continue;
            out.periods.add(new Period(slot, round(median(pmin)), round(median(pmax)), round(median(pw)),
                    circularMean(pd), median(pp), symbol));
        }
        return out;
    }

    /** njord.js refSrc = data.openmeteo || data.yr || data.smhi: whose first day is "today". */
    private static String refDate(List<Source> sources) {
        for (String name : new String[]{"Open-Meteo", "YR", "SMHI"}) {
            for (Source s : sources) if (s.name.equals(name)) return s.days.get(0).date;
        }
        return sources.get(0).days.get(0).date;
    }

    /** njord.js buildSummaryPrecipLine: the median, plus the sources' range when it is wider than 0.55 mm. */
    static String rainLine(List<Double> mm) {
        if (mm.isEmpty()) return null;
        if (mm.size() == 1) return js(fixed1(mm.get(0))) + " mm";
        double lo = Collections.min(mm), hi = Collections.max(mm);
        String s = js(fixed1(median(mm))) + " mm";
        if (hi - lo > 0.55) s += " (" + js(fixed1(lo)) + "–" + js(fixed1(hi)) + ")";
        return s;
    }

    /** njord.js computeDayAgreement: Gaussian on the spread of temperature midpoints and peak winds. */
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

    static double stdDev(List<Double> v) {
        int n = v.size();
        if (n < 2) return 0;
        double m = mean(v), ss = 0;
        for (double x : v) ss += (x - m) * (x - m);
        return Math.sqrt(ss / (n - 1));
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) s += x;
        return s / v.size();
    }

    static Double median(List<Double> v) {
        if (v.isEmpty()) return null;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int m = s.size() / 2;
        return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
    }

    /** njord.js circularMeanDeg; null when the directions cancel out, so no misleading "N". */
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

    /** JavaScript's Math.round for the values Njord rounds (half toward +∞). */
    private static Integer round(Double d) {
        return d == null ? null : (int) Math.floor(d + 0.5);
    }

    /** +x.toFixed(1): exact binary value, half up. */
    static double fixed1(double d) {
        return new BigDecimal(d).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** How JavaScript prints a number: 2.3, 2, 0.5. */
    static String js(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    // ---- presentation (njord.js fmtTemp, fmtWind, degreesToCompass, slotLabel) -----------------------------

    static final String NA = "—";

    static String temp(Integer min, Integer max) {
        return min == null || max == null ? NA : min + "° / " + max + "°";
    }

    static String wind(Integer ms, Double dir) {
        if (ms == null) return NA;
        String c = compass(dir);
        return ms + " m/s" + (c.isEmpty() ? "" : " " + c);
    }

    /** A period's rain: "2.3 mm", or "—" when it stays dry, as in Njord's grid. */
    static String periodRain(Double mm) {
        return mm != null && mm > 0 ? String.format(Locale.ROOT, "%.1f mm", mm) : NA;
    }

    /** Wind comes FROM this direction; Njord's English letters (it shows them in Swedish too). */
    static String compass(Double deg) {
        if (deg == null || deg.isNaN()) return "";
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) Math.round(deg / 45) % 8];
    }

    static String slotLabel(int slot) {
        return String.format(Locale.ROOT, "%02d–%02d", slot, slot + 6);
    }

    /**
     * Which Material Symbols weather icon (res/drawable/wx_*.xml) fits YR's symbol_code, or "" for none. Without a
     * symbol only rain is worth an icon. Specific before generic: "heavyrainandthunder" is a thunderstorm,
     * "lightsleet" is sleet, not rain.
     */
    static String icon(String symbol, Double precip) {
        if (symbol != null) {
            boolean night = symbol.endsWith("_night");
            String s = symbol.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
            if (s.contains("thunder")) return "thunder";
            if (s.contains("sleet")) return "sleet";
            if (s.contains("snow")) return s.startsWith("heavy") ? "snow_heavy" : "snow";
            if (s.contains("rain")) return s.startsWith("heavy") ? "rain_heavy" : s.startsWith("light") ? "rain_light" : "rain";
            if (s.equals("fog")) return "fog";
            if (s.equals("cloudy")) return "cloud";
            if (s.equals("partlycloudy")) return night ? "partly_night" : "partly_day";
            if (s.equals("clearsky") || s.equals("fair")) return night ? "clear_night" : "sunny";
        }
        if (precip != null && precip >= 0.3) return "rain";
        return "";
    }

    // ---- network -------------------------------------------------------------------------------------------

    static String yrUrl(double lat, double lon) {
        return String.format(Locale.ROOT,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
    }

    static String smhiUrl(double lat, double lon) {
        return String.format(Locale.ROOT,
                "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%.2f/lat/%.2f/data.json",
                lon, lat);
    }

    static String openMeteoUrl(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + js(lat) + "&longitude=" + js(lon)
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,windspeed_10m_max"
                + ",winddirection_10m_dominant,apparent_temperature_min,apparent_temperature_max,uv_index_max"
                + "&hourly=temperature_2m,windspeed_10m,winddirection_10m,precipitation,apparent_temperature"
                + "&windspeed_unit=ms&timezone=auto&forecast_days=2";
    }

    /** njord.js searchLocation: Open-Meteo's geocoder, the same place Njord saved when Hjuvik was searched for. */
    static String geocodeUrl(String name) throws java.io.UnsupportedEncodingException {
        return "https://geocoding-api.open-meteo.com/v1/search?name=" + java.net.URLEncoder.encode(name, "UTF-8")
                + "&count=5&language=sv";
    }

    /** The first match's {lat, lon}, or null. */
    static double[] parseGeocode(String body) throws Exception {
        JSONArray results = new JSONObject(body).optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.getJSONObject(0);
        double lat = r.optDouble("latitude", Double.NaN), lon = r.optDouble("longitude", Double.NaN);
        return Double.isNaN(lat) || Double.isNaN(lon) ? null : new double[]{lat, lon};
    }

    /** All three sources, each allowed to fail on its own. Blocking: call it off the main thread. */
    static Summary fetch(double lat, double lon) {
        // Open-Meteo first: its answer names the zone YR and SMHI are grouped in.
        Source om = null;
        try {
            om = parseOpenMeteo(Http.get(openMeteoUrl(lat, lon)), Instant.now());
        } catch (Exception e) {
            Http.log("Open-Meteo", e);
        }
        ZoneId zone = zone(om != null ? om.timezone : null);
        List<Source> sources = new ArrayList<>();
        try {
            sources.add(parseYr(Http.get(yrUrl(lat, lon)), zone));
        } catch (Exception e) {
            Http.log("YR", e);
        }
        try {
            sources.add(parseSmhi(Http.get(smhiUrl(lat, lon)), zone));
        } catch (Exception e) {
            Http.log("SMHI", e);
        }
        sources.add(om);
        return summarize(sources);
    }
}
