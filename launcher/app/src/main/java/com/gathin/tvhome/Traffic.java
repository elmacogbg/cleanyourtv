package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Road traffic from Sveriges Radio's open API (api.sr.se, no key): the messages P4 reads on air, such as queues on
 * väg 155 or a crash on Oscarsleden. Only what is serious and recent: priority 1 to 3 (1 is very serious, 5 is
 * minor), road and public transport, not planned roadworks. Plain Java for the JVM tests.
 */
final class Traffic {

    /** SR's categories: 0 road traffic, 1 public transport, 2 planned disruption, 3 other. */
    private static final int PLANNED = 2;
    private static final int MAX_PRIORITY = 3;
    private static final long MAX_AGE_MS = 6 * 3_600_000L;
    private static final Pattern MS_DATE = Pattern.compile("/Date\\((\\d+)");

    private Traffic() {}

    static String url(String area) throws java.io.UnsupportedEncodingException {
        return "https://api.sr.se/api/v2/traffic/messages?format=json&pagination=false&trafficareaname="
                + URLEncoder.encode(area, "UTF-8");
    }

    /**
     * "Väg 155 vid Öckerö · Kö i båda riktningar efter olycka", most serious first, at most {@code max}. A message
     * older than six hours is left out: SR removes cleared ones, but a forgotten one should not stay for days.
     */
    static List<String> parse(String body, long nowMs, int max) throws Exception {
        JSONArray messages = new JSONObject(body).optJSONArray("messages");
        List<JSONObject> keep = new ArrayList<>();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null || m.optInt("category", 0) == PLANNED) continue;
                int priority = m.optInt("priority", 5);
                if (priority < 1 || priority > MAX_PRIORITY) continue;
                long created = msDate(m.optString("createddate", ""));
                if (created > 0 && nowMs - created > MAX_AGE_MS) continue;
                keep.add(m);
            }
        }
        keep.sort((a, b) -> Integer.compare(a.optInt("priority", 5), b.optInt("priority", 5)));
        List<String> out = new ArrayList<>();
        for (JSONObject m : keep) {
            if (out.size() == max) break;
            String title = News.clean(m.optString("title", "")), text = News.clean(m.optString("description", ""));
            String line;
            if (text.isEmpty()) line = title;
            else if (title.isEmpty() || text.startsWith(title)) line = text;   // the text often repeats the road
            else line = title + "  ·  " + text;
            if (!line.isEmpty() && !out.contains(line)) out.add(line);
        }
        return out;
    }

    /** SR's "/Date(1761134400000+0200)/": the milliseconds, 0 when unreadable. */
    static long msDate(String s) {
        Matcher m = MS_DATE.matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
