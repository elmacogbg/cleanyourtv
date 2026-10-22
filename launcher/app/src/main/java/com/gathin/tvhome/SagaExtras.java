package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This fork's two additions to Home: today's weather on the left under the clock, the same numbers Njord's
 * summary view shows (see {@link Weather}), and news headlines in a column on the right that rolls upward, one
 * headline at a time. Both are off unless the theme's config.json names a place ({@code weather_name}, optionally
 * {@code weather_lat}/{@code weather_lon}) or a feed ({@code news_rss}). Off means nothing runs: no thread, no
 * network.
 *
 * <p>Text uses three sizes and no others, so the panels read as one with the clock row: LABEL is the date line's
 * style (14sp, regular, capitals, spaced), BODY carries every value and headline, DISPLAY is the temperature.
 *
 * <p>Network work happens on one background thread; the views are only touched on the main thread. Fetches
 * repeat while Home is in the foreground and stop with it. A failed fetch keeps the last good data on screen
 * and is retried sooner than a normal refresh.
 */
final class SagaExtras {

    // ---- the page grid -------------------------------------------------------------------------------------
    // Header: the clock on the left, the departures opposite it where the pills were. Two cells under it, each
    // CELL_DP wide with a GUTTER_DP gap: the weather, the news. The dock, then the pill bar (buttons_bottom).
    static final int CELL_DP = 400, GUTTER_DP = 40, PAGE_MARGIN_DP = 60;
    /** Room under the dock for the pill bar: its 42dp pills, BAR_MARGIN_DP under them, and a gap above. */
    static final float BAR_ROOM_DP = 50, BAR_MARGIN_DP = 14;

    /** Theme key buttons_bottom: the pills in a bar under the dock, the app library one of them. */
    static boolean buttonsBottom(Context ctx) {
        JSONObject json = Config.overlayJson(ctx);
        return json != null && json.optBoolean("buttons_bottom", false);
    }

    // ---- the type scale ------------------------------------------------------------------------------------
    private static final int LABEL_SP = 14, BODY_SP = 17, DISPLAY_SP = 34;
    private static final float LABEL_SPACING = 0.14f;   // the date line's letterSpacing
    private static final int GOLD = 0xFFD4A848;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xA6FFFFFF;           // the date line's colour; labels and secondary values

    private static final long WEATHER_EVERY_MS = 30 * 60_000L;   // Njord's cache lifetime
    private static final long NEWS_EVERY_MS = 15 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long ROLL_EVERY_MS = 6_000L;
    private static final long ROLL_MS = 700L;
    private static final int NEWS_MAX = 15;
    private static final long TRANSIT_EVERY_MS = 60_000L;   // two stops: two calls a minute, Sleipner allows 60
    private static final int TRANSIT_NEXT = 2;
    private static final String PREFS = "saga_extras";

    private final Context ctx;
    private final LinearLayout weatherPanel, column, newsPanel, transitPanel;
    /** The header's top: level with the greeting above the clock. */
    private static final int HEADER_TOP_DP = 30;
    private List<List<Transit.Departure>> lastDepartures;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final SharedPreferences prefs;
    private final Typeface regular, light;

    private final String placeName, newsUrl;
    private final boolean weatherOn, newsOn, transitOn;
    private final List<String> transitStops = new ArrayList<>();
    private final String transitTo, transitLabel, transitApi;
    /** The owner's own Västtrafik keys, when given: Home then asks Västtrafik directly instead of Sleipner. */
    private final String vtId, vtSecret;
    private volatile String vtToken;
    private volatile long vtTokenUntil;
    /** Stop name to Västtrafik gid, looked up once and kept in prefs. */
    private final java.util.Map<String, String> gids = new java.util.concurrent.ConcurrentHashMap<>();
    /** From the theme, or geocoded from the name the way Njord found it; NaN until known. */
    private volatile double lat, lon;

    private ExecutorService worker;
    private boolean running;
    private Weather.Summary weather;
    private long weatherAt, newsAt;
    private List<String> headlines = new ArrayList<>();
    private LinearLayout newsList;
    private FrameLayout newsWindow;
    private final int newsDefaultHeight;
    private boolean rolling;

    SagaExtras(Context ctx, LinearLayout weatherPanel, LinearLayout column) {
        this.ctx = ctx;
        this.weatherPanel = weatherPanel;
        this.column = column;
        this.density = ctx.getResources().getDisplayMetrics().density;
        // The right-hand cell holds the news. The departures go in the header opposite the clock, where you look
        // for the time: what you leave for sits next to what time it is.
        this.newsPanel = new LinearLayout(ctx);
        newsPanel.setOrientation(LinearLayout.VERTICAL);
        newsPanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        newsPanel.setVisibility(View.GONE);
        column.addView(newsPanel);
        this.transitPanel = new LinearLayout(ctx);
        transitPanel.setOrientation(LinearLayout.VERTICAL);
        transitPanel.setVisibility(View.GONE);
        if (column.getParent() instanceof FrameLayout) {
            // CELL_DP wide at the page margin: its left edge lines up with the news cell under it, and the
            // stop/departure columns get the room they need instead of running off the screen.
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(CELL_DP),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            tlp.topMargin = dp(HEADER_TOP_DP);
            tlp.setMarginEnd(dp(PAGE_MARGIN_DP));
            ((FrameLayout) column.getParent()).addView(transitPanel, tlp);
        }
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.regular = font(R.font.outfit_regular);
        this.light = font(R.font.outfit_light);
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
        // transit_stops: ["Majvik", "Hjuvik"], transit_to: "Centralstationen", transit_label: "Mot Göteborg"
        org.json.JSONArray stops = json != null ? json.optJSONArray("transit_stops") : null;
        if (stops != null) {
            for (int i = 0; i < stops.length(); i++) {
                String name = stops.optString(i, "").trim();
                if (!name.isEmpty()) transitStops.add(name);
            }
        }
        transitTo = json != null ? json.optString("transit_to", "").trim() : "";
        transitLabel = json != null ? json.optString("transit_label", "").trim() : "";
        String api = json != null ? json.optString("transit_api", "").trim() : "";
        transitApi = api.startsWith("https://") ? api : Transit.DEFAULT_API;
        transitOn = !transitStops.isEmpty() && !transitTo.isEmpty();
        importVasttrafikKeys();
        vtId = prefs.getString("vt_id", "");
        vtSecret = prefs.getString("vt_secret", "");
        for (String name : transitStops) loadGid(name);
        loadGid(transitTo);
        weatherPanel.setVisibility(View.GONE);
        column.setVisibility(View.GONE);
        // The column ends where the weather ends; the layout's height is the fallback without weather.
        ViewGroup.LayoutParams np = column.getLayoutParams();
        newsDefaultHeight = np.height > 0 ? np.height : dp(210);
        np.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        column.setLayoutParams(np);
        weatherPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) main.post(this::fitNews);
        });
    }

    boolean enabled() {
        return weatherOn || newsOn || transitOn;
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
            scheduleRoll();
        }
        if (transitOn) fetchTransit();
    }

    /** Home went to the background: no more fetches and no rolling until it is back. */
    void stop() {
        running = false;
        main.removeCallbacks(weatherTick);
        main.removeCallbacks(newsTick);
        main.removeCallbacks(rollTick);
        main.removeCallbacks(transitTick);
        if (newsList != null) {
            newsList.animate().cancel();
            newsList.setTranslationY(0);
        }
        rolling = false;
    }

    /** Called with the clock, once a minute: moves the gold mark and dims the periods that are over. */
    void minute() {
        if (weather != null) renderWeather(weather);
    }

    /** The race-weekend panel sits in the weather's spot; it wins while it is shown. */
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

        // HJUVIK  ·  SÄKERHET 99 %: Njord's agreement badge, named; a bare percentage next to a place says nothing.
        String kicker = (placeName.isEmpty() ? "Väder" : placeName)
                + (s.confidence != null ? "  ·  Säkerhet " + s.confidence + " %" : "");
        weatherPanel.addView(label(kicker, DIM));

        // [icon] 11° / 12°: the icon is the period we are in now, in gold, so it says what it looks like outside.
        TextView h = text(Weather.temp(s.tempMin, s.tempMax), DISPLAY_SP, WHITE, light);
        h.setIncludeFontPadding(false);
        withIcon(h, currentIcon(s), 40, GOLD, 14);
        lp(h).topMargin = dp(10);
        weatherPanel.addView(h);

        // Njord's rows, in two lines so the panel stays on its half of the screen.
        SpannableStringBuilder line1 = new SpannableStringBuilder(), line2 = new SpannableStringBuilder();
        row(line1, "Känns", Weather.temp(s.feelsMin, s.feelsMax));
        row(line1, "Vind", Weather.wind(s.wind, s.windDir));
        row(line1, "UV", s.uv == null ? Weather.NA : String.valueOf(s.uv));
        // Dry: no rain row at all, rather than a dash or "0 mm" to read past.
        if (s.rain != null && !s.rain.equals("0 mm")) row(line2, "Regn", s.rain);
        row(line2, "Fukt.", s.humidity == null ? Weather.NA : s.humidity + "%");
        TextView l1 = text(line1, BODY_SP, WHITE, light);
        lp(l1).topMargin = dp(14);
        weatherPanel.addView(l1);
        TextView l2 = text(line2, BODY_SP, WHITE, light);
        lp(l2).topMargin = dp(4);
        weatherPanel.addView(l2);

        // The expanded day's four periods. The one we are in has a gold label; the ones that are over fade.
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
            col.setMinimumWidth(dp(CELL_DP / 4));
            col.setAlpha(over ? 0.35f : 1f);
            col.addView(label(Weather.slotLabel(p.slot), current ? GOLD : DIM));
            TextView t = text(Weather.temp(p.tempMin, p.tempMax), BODY_SP, WHITE, light);
            withIcon(t, Weather.icon(p.symbol, p.precip), 20, WHITE, 6);
            lp(t).topMargin = dp(6);
            col.addView(t);
            TextView w = text(Weather.wind(p.wind, p.windDir), BODY_SP, DIM, light);
            lp(w).topMargin = dp(2);
            col.addView(w);
            String rain = Weather.periodRain(p.precip);
            if (!Weather.NA.equals(rain)) col.addView(text(rain, BODY_SP, DIM, light));   // dry: nothing
            grid.addView(col);
        }
        if (grid.getChildCount() > 0) weatherPanel.addView(grid);
        weatherPanel.setVisibility(View.VISIBLE);
    }

    /**
     * "LABEL value": the label at LABEL size and colour inside a BODY line, so both share one baseline. A
     * TypefaceSpan taking a Typeface needs Android 9, so the label keeps the line's font; size and colour carry it.
     */
    private void row(SpannableStringBuilder sb, String label, String value) {
        if (sb.length() > 0) sb.append("     ");
        int start = sb.length();
        sb.append(label.toUpperCase(Locale.getDefault()));
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(DIM), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan((float) LABEL_SP / BODY_SP), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
            case "rain_light": return R.drawable.wx_rain;   // one rain icon for all rain: the light and heavy ones
            case "rain": return R.drawable.wx_rain;
            case "rain_heavy": return R.drawable.wx_rain;   // are streaks that read as noise at TV size
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
                    showNews(result);
                    scheduleNews(NEWS_EVERY_MS);
                } else {
                    scheduleNews(RETRY_MS);
                }
            });
        });
    }

    /** The column: a header, then the headlines in a clipped window that {@link #roll} moves up. */
    private void showNews(List<String> titles) {
        if (titles.equals(headlines) && newsList != null) return;   // same feed: keep the roll where it is
        headlines = new ArrayList<>(titles);
        newsPanel.animate().cancel();
        newsPanel.removeAllViews();
        newsPanel.addView(label("Nyheter  ·  SVT", DIM));

        // The headline crossing the window's lower edge fades out instead of being sliced, so the column can run
        // down to the weather's last line. Drawn here rather than with View's fading edge, which Android skipped:
        // the children go into a layer and a gradient erases the bottom of it (DST_OUT), over any wallpaper.
        FrameLayout window = new FrameLayout(ctx) {
            private final android.graphics.Paint fade = new android.graphics.Paint();

            {
                fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            }

            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                int w = getWidth(), h = getHeight(), len = Math.min(dp(48), h / 2);
                if (len <= 0) {
                    super.dispatchDraw(canvas);
                    return;
                }
                int saved = canvas.saveLayer(0, 0, w, h, null);
                super.dispatchDraw(canvas);
                fade.setShader(new android.graphics.LinearGradient(0, h - len, 0, h, 0x00000000, 0xFF000000,
                        android.graphics.Shader.TileMode.CLAMP));
                canvas.drawRect(0, h - len, w, h, fade);
                canvas.restoreToCount(saved);
            }
        };
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        wlp.topMargin = dp(12);
        window.setLayoutParams(wlp);
        newsWindow = window;
        window.setClipChildren(true);
        newsList = new LinearLayout(ctx);
        newsList.setOrientation(LinearLayout.VERTICAL);
        newsList.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (String title : headlines) newsList.addView(headline(title));
        window.addView(newsList);
        newsPanel.addView(window);
        newsPanel.setVisibility(View.VISIBLE);
        column.setVisibility(View.VISIBLE);
        rolling = false;
        newsList.post(this::fitNews);
        scheduleRoll();
    }

    /** One headline: a gold bullet and up to two lines of BODY text, the second line hanging under the first word. */
    private TextView headline(String title) {
        String bullet = "\u2022  ";
        SpannableStringBuilder sb = new SpannableStringBuilder(bullet + title);
        sb.setSpan(new ForegroundColorSpan(GOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = text(sb, BODY_SP, WHITE, light);
        int indent = Math.round(t.getPaint().measureText(bullet));
        sb.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, sb.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        t.setText(sb);
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(14);
        t.setLayoutParams(p);
        return t;
    }

    /**
     * Sizes the headline window so the news cell ends level with the bottom of the weather cell, or at the
     * layout's height without weather. The headline crossing that edge fades out through the window's
     * fading edge.
     */
    private void fitNews() {
        if (newsWindow == null || newsPanel.getChildCount() == 0) return;
        View header = newsPanel.getChildAt(0);
        if (header.getHeight() == 0) {         // not laid out yet: try again after this frame
            newsPanel.post(this::fitNews);
            return;
        }
        int total = weatherPanel.getVisibility() == View.VISIBLE && weatherPanel.getHeight() > 0
                ? weatherPanel.getHeight() : newsDefaultHeight;
        LinearLayout.LayoutParams wlp = (LinearLayout.LayoutParams) newsWindow.getLayoutParams();
        int height = Math.max(0, total - header.getHeight() - wlp.topMargin);
        if (wlp.height != height) {
            wlp.height = height;
            newsWindow.setLayoutParams(wlp);
        }
    }

    private final Runnable rollTick = this::roll;

    private void scheduleRoll() {
        main.removeCallbacks(rollTick);
        if (running && headlines.size() > 1) main.postDelayed(rollTick, ROLL_EVERY_MS);
    }

    /**
     * Moves the list up by its first headline, then puts that headline last and the list back in place, so the
     * column keeps rolling through the feed without ever running out. One slide every few seconds, not a
     * continuous crawl: a headline is read, then the next comes.
     */
    private void roll() {
        if (!running || rolling || newsList == null || newsList.getChildCount() < 2) {
            scheduleRoll();
            return;
        }
        View first = newsList.getChildAt(0);
        int step = first.getHeight() + ((LinearLayout.LayoutParams) first.getLayoutParams()).bottomMargin;
        if (step <= 0) {
            scheduleRoll();
            return;
        }
        rolling = true;
        newsList.animate().translationY(-step).setDuration(ROLL_MS).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        newsList.animate().setListener(null);
                        if (newsList.getChildCount() > 1 && newsList.getChildAt(0) == first) {
                            newsList.removeViewAt(0);
                            newsList.addView(first);
                        }
                        newsList.setTranslationY(0);
                        rolling = false;
                        scheduleRoll();
                    }
                });
    }

    // ---- transit -------------------------------------------------------------------------------------------

    private final Runnable transitTick = this::fetchTransit;

    /**
     * vasttrafik.json ({"client_id": "...", "client_secret": "..."}), pushed once next to the theme with adb, is
     * moved into the launcher's private prefs and deleted: the keys do not stay on shared storage, and they are
     * never in the theme or in git.
     */
    private void importVasttrafikKeys() {
        java.io.File dir = Config.dir(ctx);
        java.io.File f = dir == null ? null : new java.io.File(dir, "vasttrafik.json");
        if (f == null || !f.isFile() || f.length() > 4096) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            JSONObject o = new JSONObject(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            String id = o.optString("client_id", "").trim(), secret = o.optString("client_secret", "").trim();
            if (!id.isEmpty() && !secret.isEmpty()) {
                prefs.edit().putString("vt_id", id).putString("vt_secret", secret).commit();
            }
        } catch (Exception e) {
            Http.log("vasttrafik.json", e);
        }
        if (!f.delete()) Http.log("vasttrafik.json", new java.io.IOException("could not delete " + f));
    }

    private boolean direct() {
        return !vtId.isEmpty() && !vtSecret.isEmpty();
    }

    /** Worker thread: a valid "Bearer …" header, fetching a token when the last one is about to run out. */
    private String bearer() throws Exception {
        if (vtToken != null && System.currentTimeMillis() < vtTokenUntil) return "Bearer " + vtToken;
        Object[] t = Transit.parseToken(Http.postForm(Transit.VT_TOKEN_URL, Transit.basicAuth(vtId, vtSecret),
                "grant_type=client_credentials"));
        if (t == null) throw new java.io.IOException("Västtrafik gave no token");
        vtToken = (String) t[0];
        vtTokenUntil = System.currentTimeMillis() + Math.max((Long) t[1] - 60, 30) * 1000L;
        return "Bearer " + vtToken;
    }

    /** Worker thread: one GET, direct with the token or through Sleipner; a 401 drops the token for next time. */
    private String transitGet(String directUrl, String sleipnerUrl) throws Exception {
        if (!direct()) return Http.get(sleipnerUrl);
        try {
            return Http.get(directUrl, bearer());
        } catch (java.io.IOException e) {
            if (String.valueOf(e.getMessage()).startsWith("HTTP 401")) vtToken = null;
            throw e;
        }
    }

    private void loadGid(String name) {
        String gid = prefs.getString("gid_" + name, null);
        if (gid != null) gids.put(name, gid);
    }

    /** Worker thread: the stop's gid, from prefs or one lookup through Sleipner. */
    private String gid(String name) throws Exception {
        String gid = gids.get(name);
        if (gid != null) return gid;
        String body = transitGet(Transit.vtStopsUrl(name), Transit.stopsUrl(transitApi, name));
        gid = direct() ? Transit.parseVtStopGid(body) : Transit.parseStopGid(body);
        if (gid != null) {
            gids.put(name, gid);
            prefs.edit().putString("gid_" + name, gid).apply();
        }
        return gid;
    }

    /** Every minute while Home is up: the next two departures from each stop towards the destination. */
    private void fetchTransit() {
        if (!running) return;
        worker.execute(() -> {
            List<List<Transit.Departure>> rows = new ArrayList<>();
            boolean any = false;
            try {
                String to = gid(transitTo);
                for (String stop : transitStops) {
                    List<Transit.Departure> next = null;
                    try {
                        String from = to == null ? null : gid(stop);
                        if (from != null && !from.equals(to)) {
                            OffsetDateTime now = OffsetDateTime.now();
                            String body = transitGet(Transit.vtJourneysUrl(from, to, now),
                                    Transit.tripsUrl(transitApi, from, to));
                            next = Transit.parseTrips(direct() ? Transit.journeysAsTrips(body) : body, now,
                                    TRANSIT_NEXT);
                            any = true;
                        }
                    } catch (Exception e) {
                        Http.log("transit " + stop, e);
                    }
                    rows.add(next);
                }
            } catch (Exception e) {
                Http.log("transit", e);
            }
            boolean ok = any;
            main.post(() -> {
                if (ok) renderTransit(rows);
                main.removeCallbacks(transitTick);
                if (running) main.postDelayed(transitTick, TRANSIT_EVERY_MS);
            });
        });
    }

    /**
     * MOT GÖTEBORG, then a small table, one row per stop and the same column for every stop's first and second
     * departure so the times line up: "HJUVIK   34  12:02 · 7 min   34  13:02". The first departure says how long
     * is left, which is the question you ask the screen; a late bus shows its real time with the delay in gold; a
     * cancelled one is struck through. A stop whose fetch failed keeps its last row.
     */
    private void renderTransit(List<List<Transit.Departure>> rows) {
        ZoneId zone = ZoneId.systemDefault();
        if (lastDepartures != null) {
            for (int i = 0; i < rows.size() && i < lastDepartures.size(); i++) {
                if (rows.get(i) == null) rows.set(i, lastDepartures.get(i));
            }
        }
        lastDepartures = rows;
        String header = transitLabel.isEmpty() ? "Mot " + transitTo : transitLabel;
        transitPanel.removeAllViews();
        transitPanel.addView(label(header, DIM));
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < transitStops.size(); i++) {
            List<Transit.Departure> next = rows.get(i);
            if (next == null) continue;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            row.setLayoutParams(rlp);
            TextView name = label(transitStops.get(i), DIM);
            name.setMinWidth(dp(96));
            row.addView(name);
            if (next.isEmpty()) {
                row.addView(text("Inga avgångar", BODY_SP, DIM, light));
            }
            for (int k = 0; k < next.size(); k++) {
                TextView cell = text(departure(next.get(k), k == 0, now, zone), BODY_SP, WHITE, light);
                cell.setMinWidth(dp(k == 0 ? 152 : 0));
                row.addView(cell);
            }
            transitPanel.addView(row);
        }
        transitPanel.setVisibility(transitPanel.getChildCount() > 1 ? View.VISIBLE : View.GONE);
    }

    /** "34  12:02 · 7 min": the line dim, the time white, a delay or cancellation in gold, minutes for the first. */
    private static CharSequence departure(Transit.Departure d, boolean first, OffsetDateTime now, ZoneId zone) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!d.line.isEmpty()) {
            sb.append(d.line).append("  ");
            sb.setSpan(new ForegroundColorSpan(DIM), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int t = sb.length();
        sb.append(d.clock(zone));
        if (d.cancelled) {
            sb.setSpan(new android.text.style.StrikethroughSpan(), t, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int c = sb.length();
            sb.append(" inställd");
            sb.setSpan(new ForegroundColorSpan(GOLD), c, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sb;
        }
        if (d.minutesLate() >= 1) {
            int c = sb.length();
            sb.append(" +").append(String.valueOf(d.minutesLate()));
            sb.setSpan(new ForegroundColorSpan(GOLD), c, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        long minutes = java.time.Duration.between(now, d.when()).toMinutes();
        if (first && minutes >= 0 && minutes < 60) {
            int c = sb.length();
            sb.append(minutes == 0 ? "  \u00B7 nu" : "  \u00B7 " + minutes + " min");
            sb.setSpan(new ForegroundColorSpan(DIM), c, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    // ---- small view helpers --------------------------------------------------------------------------------

    /** The LABEL style: the date line's 14sp regular capitals with its letter spacing. */
    private TextView label(String s, int color) {
        TextView t = text(s.toUpperCase(Locale.getDefault()), LABEL_SP, color, regular);
        t.setLetterSpacing(LABEL_SPACING);
        return t;
    }

    private TextView text(CharSequence s, int sp, int color, Typeface face) {
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
