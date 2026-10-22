package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This fork's two additions to Home: today's weather under the clock, the same numbers Njord's summary view shows
 * (see {@link Weather}), and a ticker of news headlines along the bottom edge. Both are off unless the theme's
 * config.json names a place ({@code weather_name}, optionally {@code weather_lat}/{@code weather_lon}) or a feed
 * ({@code news_rss}). Off means nothing runs: no thread, no network.
 *
 * <p>Network work happens on one background thread; the views are only touched on the main thread. Fetches
 * repeat while Home is in the foreground and stop with it. A failed fetch keeps the last good data on screen
 * and is retried sooner than a normal refresh.
 */
final class SagaExtras {

    private static final int GOLD = 0xFFD4A848;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x99FFFFFF;
    private static final int LABEL = 0x80FFFFFF;
    private static final long WEATHER_EVERY_MS = 30 * 60_000L;   // Njord's cache lifetime
    private static final long NEWS_EVERY_MS = 15 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int NEWS_MAX = 15;
    private static final String PREFS = "saga_extras";

    private final Context ctx;
    private final LinearLayout weatherPanel;
    private final TextView ticker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final SharedPreferences prefs;

    private final String placeName, newsUrl;
    private final boolean weatherOn, newsOn;
    /** From the theme, or geocoded from the name the way Njord found it; NaN until known. */
    private volatile double lat, lon;

    private ExecutorService worker;
    private boolean running;
    private Weather.Summary weather;
    private long weatherAt, newsAt;

    SagaExtras(Context ctx, LinearLayout weatherPanel, TextView ticker) {
        this.ctx = ctx;
        this.weatherPanel = weatherPanel;
        this.ticker = ticker;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject json = Config.overlayJson(ctx);
        placeName = json != null ? json.optString("weather_name", "").trim() : "";
        double la = json != null ? json.optDouble("weather_lat", Double.NaN) : Double.NaN;
        double lo = json != null ? json.optDouble("weather_lon", Double.NaN) : Double.NaN;
        if ((Double.isNaN(la) || Double.isNaN(lo)) && !placeName.isEmpty()
                && placeName.equals(prefs.getString("geo_name", null))) {
            // Geocoded before: no lookup at every start.
            la = Double.longBitsToDouble(prefs.getLong("geo_lat", Double.doubleToLongBits(Double.NaN)));
            lo = Double.longBitsToDouble(prefs.getLong("geo_lon", Double.doubleToLongBits(Double.NaN)));
        }
        lat = la;
        lon = lo;
        weatherOn = !placeName.isEmpty() || (!Double.isNaN(la) && !Double.isNaN(lo));
        String url = json != null ? json.optString("news_rss", "").trim() : "";
        newsOn = url.startsWith("https://");
        newsUrl = url;
        weatherPanel.setVisibility(View.GONE);
        ticker.setVisibility(View.GONE);
    }

    boolean enabled() {
        return weatherOn || newsOn;
    }

    /** Home came to the foreground: refresh whatever is due, then keep refreshing. */
    void start() {
        if (!enabled() || running) return;
        running = true;
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "saga-extras");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long now = System.currentTimeMillis();
        if (weatherOn) {
            if (now - weatherAt >= WEATHER_EVERY_MS) fetchWeather();
            else scheduleWeather(WEATHER_EVERY_MS - (now - weatherAt));
        }
        if (newsOn) {
            if (now - newsAt >= NEWS_EVERY_MS) fetchNews();
            else scheduleNews(NEWS_EVERY_MS - (now - newsAt));
            ticker.setSelected(true);   // the marquee only runs on a selected (or focused) TextView
        }
    }

    /** Home went to the background: no more fetches until it is back. */
    void stop() {
        running = false;
        main.removeCallbacks(weatherTick);
        main.removeCallbacks(newsTick);
        ticker.setSelected(false);
    }

    /** Called with the clock, once a minute: moves the gold mark and dims the periods that are over. */
    void minute() {
        if (weather != null) renderWeather(weather);
    }

    /** The race-weekend panel sits in the same spot; it wins while it is shown. */
    void setCovered(boolean covered) {
        if (weatherOn && weather != null) weatherPanel.setVisibility(covered ? View.GONE : View.VISIBLE);
    }

    // ---- weather -------------------------------------------------------------------------------------------

    private final Runnable weatherTick = this::fetchWeather;

    private void scheduleWeather(long delay) {
        main.removeCallbacks(weatherTick);
        if (running) main.postDelayed(weatherTick, delay);
    }

    private void fetchWeather() {
        if (!running) return;
        worker.execute(() -> {
            Weather.Summary s = null;
            try {
                if (Double.isNaN(lat) || Double.isNaN(lon)) geocode();
                if (!Double.isNaN(lat) && !Double.isNaN(lon)) s = Weather.fetch(lat, lon);
            } catch (Exception e) {
                Http.log("weather", e);
            }
            Weather.Summary result = s;
            main.post(() -> {
                if (result != null) {
                    weather = result;
                    weatherAt = System.currentTimeMillis();
                    renderWeather(result);
                    scheduleWeather(WEATHER_EVERY_MS);
                } else {
                    scheduleWeather(RETRY_MS);
                }
            });
        });
    }

    /** Worker thread: looks the name up once, as Njord's search did, and keeps the answer. */
    private void geocode() throws Exception {
        double[] ll = Weather.parseGeocode(Http.get(Weather.geocodeUrl(placeName)));
        if (ll == null) return;
        lat = ll[0];
        lon = ll[1];
        prefs.edit().putString("geo_name", placeName)
                .putLong("geo_lat", Double.doubleToLongBits(ll[0]))
                .putLong("geo_lon", Double.doubleToLongBits(ll[1])).apply();
    }

    private void renderWeather(Weather.Summary s) {
        // A summary from yesterday (Home stayed up across midnight) is not today's weather: hide it and fetch.
        if (!LocalDate.now().toString().equals(s.date)) {
            weatherPanel.setVisibility(View.GONE);
            weather = null;
            weatherAt = 0;
            scheduleWeather(0);
            return;
        }
        weatherPanel.removeAllViews();
        Typeface medium = font(R.font.outfit_medium), light = font(R.font.outfit_light);

        // HJUVIK  ·  98 %
        StringBuilder kicker = new StringBuilder(placeName.isEmpty() ? "Väder" : placeName);
        if (s.confidence != null) kicker.append("  ·  ").append(s.confidence).append(" %");
        TextView k = text(kicker.toString().toUpperCase(Locale.getDefault()), 12, 0xB3FFFFFF, medium);
        k.setLetterSpacing(0.22f);
        weatherPanel.addView(k);

        // [icon] 11° / 12°: the icon is the period we are in now, in gold, so it says what it looks like outside.
        TextView h = text(Weather.temp(s.tempMin, s.tempMax), 34, WHITE, light);
        h.setIncludeFontPadding(false);
        withIcon(h, currentIcon(s), 40, GOLD, 14);
        lp(h).topMargin = dp(8);
        weatherPanel.addView(h);

        // KÄNNS 10° / 12°   REGN 2.3 mm (2.1–8.3)   VIND 8 m/s S   FUKT. 89%   UV 1, Njord's rows on one line.
        SpannableStringBuilder rows = new SpannableStringBuilder();
        row(rows, "KÄNNS", Weather.temp(s.feelsMin, s.feelsMax));
        row(rows, "REGN", s.rain == null ? Weather.NA : s.rain);
        row(rows, "VIND", Weather.wind(s.wind, s.windDir));
        row(rows, "FUKT.", s.humidity == null ? Weather.NA : s.humidity + "%");
        row(rows, "UV", s.uv == null ? Weather.NA : String.valueOf(s.uv));
        TextView d = text(rows, 15, WHITE, medium);
        lp(d).topMargin = dp(10);
        weatherPanel.addView(d);

        // The expanded day's four periods. The one we are in is marked in gold; the ones that are over fade.
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(16);
        grid.setLayoutParams(gridLp);
        int hour = LocalTime.now().getHour();
        for (Weather.Period p : s.periods) {
            boolean over = hour >= p.slot + 6;
            boolean current = hour >= p.slot && !over;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setMinimumWidth(dp(118));
            col.setAlpha(over ? 0.35f : 1f);
            col.addView(text(Weather.slotLabel(p.slot), 12, current ? GOLD : DIM, medium));
            TextView t = text(Weather.temp(p.tempMin, p.tempMax), 17, WHITE, light);
            withIcon(t, Weather.icon(p.symbol, p.precip), 22, WHITE, 8);
            lp(t).topMargin = dp(4);
            col.addView(t);
            TextView w = text(Weather.wind(p.wind, p.windDir), 12, DIM, medium);
            lp(w).topMargin = dp(2);
            col.addView(w);
            TextView r = text(Weather.periodRain(p.precip), 12, DIM, medium);
            lp(r).topMargin = dp(1);
            col.addView(r);
            grid.addView(col);
        }
        if (grid.getChildCount() > 0) weatherPanel.addView(grid);
        weatherPanel.setVisibility(View.VISIBLE);
    }

    /** "LABEL value" with the label dim, as Njord's grey row labels; a gap before every row but the first. */
    private static void row(SpannableStringBuilder sb, String label, String value) {
        if (sb.length() > 0) sb.append("     ");
        int start = sb.length();
        sb.append(label);
        sb.setSpan(new ForegroundColorSpan(LABEL), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ").append(value);
    }

    /** The icon of the period we are in now. */
    private static String currentIcon(Weather.Summary s) {
        int hour = LocalTime.now().getHour();
        for (Weather.Period p : s.periods) {
            if (hour >= p.slot && hour < p.slot + 6) return Weather.icon(p.symbol, p.precip);
        }
        return "";
    }

    /** Material Symbols weather icons, res/drawable/wx_*.xml; 0 for a key without one. */
    private static int iconRes(String key) {
        switch (key) {
            case "sunny": return R.drawable.wx_sunny;
            case "clear_night": return R.drawable.wx_clear_night;
            case "partly_day": return R.drawable.wx_partly_day;
            case "partly_night": return R.drawable.wx_partly_night;
            case "cloud": return R.drawable.wx_cloud;
            case "fog": return R.drawable.wx_fog;
            case "rain_light": return R.drawable.wx_rain_light;
            case "rain": return R.drawable.wx_rain;
            case "rain_heavy": return R.drawable.wx_rain_heavy;
            case "sleet": return R.drawable.wx_sleet;
            case "snow": return R.drawable.wx_snow;
            case "snow_heavy": return R.drawable.wx_snow_heavy;
            case "thunder": return R.drawable.wx_thunder;
            default: return 0;
        }
    }

    /** Puts the icon before the text, tinted; nothing when the key has no icon. */
    private void withIcon(TextView t, String key, int sizeDp, int color, int gapDp) {
        int res = iconRes(key);
        if (res == 0) return;
        Drawable d = ctx.getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        t.setCompoundDrawablesRelative(d, null, null, null);
        t.setCompoundDrawablePadding(dp(gapDp));
        t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    }

    // ---- news ----------------------------------------------------------------------------------------------

    private final Runnable newsTick = this::fetchNews;

    private void scheduleNews(long delay) {
        main.removeCallbacks(newsTick);
        if (running) main.postDelayed(newsTick, delay);
    }

    private void fetchNews() {
        if (!running) return;
        worker.execute(() -> {
            List<String> titles = null;
            try {
                titles = News.fetch(newsUrl, NEWS_MAX);
            } catch (Exception e) {
                Http.log("news", e);
            }
            List<String> result = titles;
            main.post(() -> {
                if (result != null && !result.isEmpty()) {
                    newsAt = System.currentTimeMillis();
                    showTicker(result);
                    scheduleNews(NEWS_EVERY_MS);
                } else {
                    scheduleNews(RETRY_MS);
                }
            });
        });
    }

    private void showTicker(List<String> titles) {
        String line = News.tickerLine(titles);
        // Setting the same text again would restart the marquee from the left edge for nothing.
        if (TextUtils.equals(ticker.getText().toString(), line)) return;
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        // The bullets between headlines in gold, the one accent.
        for (int i = line.indexOf('•'); i >= 0; i = line.indexOf('•', i + 1)) {
            sb.setSpan(new ForegroundColorSpan(GOLD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ticker.setText(sb);
        ticker.setVisibility(View.VISIBLE);
        ticker.setSelected(running);
    }

    // ---- small view helpers --------------------------------------------------------------------------------

    private TextView text(CharSequence s, float sp, int color, Typeface face) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setGravity(Gravity.START);
        if (face != null) t.setTypeface(face);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private static LinearLayout.LayoutParams lp(View v) {
        return (LinearLayout.LayoutParams) v.getLayoutParams();
    }

    private Typeface font(int id) {
        try {
            return ctx.getResources().getFont(id);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
