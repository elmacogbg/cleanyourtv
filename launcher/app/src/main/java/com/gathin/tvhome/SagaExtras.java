package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
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

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This fork's two additions to Home: today's weather under the clock, merged the way Njord does it (see
 * {@link Weather}), and a ticker of news headlines along the bottom edge. Both are off unless the theme's
 * config.json names a place ({@code weather_lat}, {@code weather_lon}, {@code weather_name}) or a feed
 * ({@code news_rss}). Off means nothing runs: no thread, no network.
 *
 * <p>Network work happens on one background thread; the views are only touched on the main thread. Fetches
 * repeat while Home is in the foreground and stop with it. A failed fetch keeps the last good data on screen
 * and is retried sooner than a normal refresh.
 */
final class SagaExtras {

    private static final int GOLD = 0xFFD4A848;
    private static final int DIM = 0x99FFFFFF;
    private static final long WEATHER_EVERY_MS = 30 * 60_000L;
    private static final long NEWS_EVERY_MS = 15 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int NEWS_MAX = 15;

    private final Context ctx;
    private final LinearLayout weatherPanel;
    private final TextView ticker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ZoneId zone = ZoneId.systemDefault();
    private final float density;

    private final String placeName, newsUrl;
    private final double lat, lon;
    private final boolean weatherOn, newsOn;

    private ExecutorService worker;
    private boolean running;
    private Weather.Summary weather;
    private long weatherAt, newsAt;

    SagaExtras(Context ctx, LinearLayout weatherPanel, TextView ticker) {
        this.ctx = ctx;
        this.weatherPanel = weatherPanel;
        this.ticker = ticker;
        this.density = ctx.getResources().getDisplayMetrics().density;
        JSONObject json = Config.overlayJson(ctx);
        double la = json != null ? json.optDouble("weather_lat", Double.NaN) : Double.NaN;
        double lo = json != null ? json.optDouble("weather_lon", Double.NaN) : Double.NaN;
        lat = la;
        lon = lo;
        weatherOn = !Double.isNaN(la) && !Double.isNaN(lo);
        placeName = json != null ? json.optString("weather_name", "").trim() : "";
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

    /** Called with the clock, once a minute: dims the periods that are over. */
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
                s = Weather.fetch(lat, lon, zone);
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

    private void renderWeather(Weather.Summary s) {
        // A summary from yesterday (Home stayed up across midnight) is not today's weather: hide it and fetch.
        if (!s.date.equals(java.time.LocalDate.now(zone))) {
            weatherPanel.setVisibility(View.GONE);
            weather = null;
            weatherAt = 0;
            scheduleWeather(0);
            return;
        }
        weatherPanel.removeAllViews();
        Typeface medium = font(R.font.outfit_medium), light = font(R.font.outfit_light);

        // HJUVIK · 94 % SÄKERHET · YR SMHI OPEN-METEO
        StringBuilder kicker = new StringBuilder(placeName.isEmpty() ? "Väder" : placeName);
        if (s.confidence != null) kicker.append("  ·  ").append(s.confidence).append(" % säkerhet");
        TextView k = text(kicker.toString().toUpperCase(Locale.getDefault()), 12, 0xB3FFFFFF, medium);
        k.setLetterSpacing(0.22f);
        weatherPanel.addView(k);

        // ⛅ 8° / 12°    7 m/s SV · 0,4 mm
        String nowGlyph = currentGlyph(s);
        SpannableStringBuilder head = new SpannableStringBuilder();
        if (!nowGlyph.isEmpty()) head.append(nowGlyph).append("  ");
        if (s.tempMin != null && s.tempMax != null) head.append(s.tempMin + "° / " + s.tempMax + "°");
        TextView h = text(head, 34, 0xFFFFFFFF, light);
        h.setIncludeFontPadding(false);
        lp(h).topMargin = dp(8);
        weatherPanel.addView(h);

        String detail = windText(s.wind, s.windDir) + rainText(s.precip, "  ·  ");
        if (!detail.isEmpty()) {
            TextView d = text(detail, 15, DIM, medium);
            lp(d).topMargin = dp(6);
            weatherPanel.addView(d);
        }

        // The four six-hour periods, Njord's expanded day. Periods that are over fade.
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        row.setLayoutParams(rowLp);
        int hour = LocalTime.now(zone).getHour();
        for (Weather.Period p : s.periods) {
            boolean over = hour >= p.slot + 6;
            boolean current = hour >= p.slot && !over;
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setMinimumWidth(dp(92));
            col.setAlpha(over ? 0.35f : 1f);
            col.addView(text(Weather.slotLabel(p.slot), 12, current ? GOLD : DIM, medium));
            String glyph = Weather.glyph(p.symbol, p.precip);
            String temp = p.tempMin == null ? "–" : p.tempMin.equals(p.tempMax) ? p.tempMin + "°"
                    : p.tempMin + "–" + p.tempMax + "°";
            TextView t = text((glyph.isEmpty() ? "" : glyph + " ") + temp, 17, 0xFFFFFFFF, light);
            lp(t).topMargin = dp(4);
            col.addView(t);
            String sub = (p.wind == null ? "" : p.wind + " m/s") + rainText(p.precip, "  ");
            if (!sub.isEmpty()) {
                TextView w = text(sub, 12, DIM, medium);
                lp(w).topMargin = dp(2);
                col.addView(w);
            }
            row.addView(col);
        }
        if (row.getChildCount() > 0) weatherPanel.addView(row);
        weatherPanel.setVisibility(View.VISIBLE);
    }

    /** The glyph of the period we are in now, so the headline says what it looks like outside. */
    private String currentGlyph(Weather.Summary s) {
        int hour = LocalTime.now(zone).getHour();
        for (Weather.Period p : s.periods) {
            if (hour >= p.slot && hour < p.slot + 6) return Weather.glyph(p.symbol, p.precip);
        }
        return "";
    }

    private static String windText(Integer wind, Double dir) {
        if (wind == null) return "";
        String c = Weather.compass(dir);
        return wind + " m/s" + (c.isEmpty() ? "" : " " + c);
    }

    /** "0,4 mm" with a leading separator, or nothing when it stays dry (under 0.1 mm). */
    private static String rainText(Double mm, String sep) {
        if (mm == null || mm < 0.1) return "";
        return sep + String.format(new Locale("sv", "SE"), "%.1f mm", mm);
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
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        // The bullets between headlines in gold, the one accent.
        for (int i = line.indexOf('•'); i >= 0; i = line.indexOf('•', i + 1)) {
            sb.setSpan(new ForegroundColorSpan(GOLD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // Setting the same text again would restart the marquee from the left edge for nothing.
        if (TextUtils.equals(ticker.getText().toString(), line)) return;
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
