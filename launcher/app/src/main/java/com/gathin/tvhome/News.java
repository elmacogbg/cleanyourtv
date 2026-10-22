package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headlines for the news ticker, from an RSS 2.0 feed (SVT Nyheter by default). Titles only: the ticker is
 * something to glance at from the sofa, not a reader. Parsed with a regex rather than XmlPullParser so it runs in
 * JVM tests; an RSS item title is simple enough for that.
 */
final class News {

    private static final Pattern ITEM = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("^\\s*<!\\[CDATA\\[(.*?)]]>\\s*$", Pattern.DOTALL);
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private News() {}

    /** The feed's item titles in order, at most {@code max}, blank and duplicate titles left out. */
    static List<String> titles(String rss, int max) {
        List<String> out = new ArrayList<>();
        Matcher item = ITEM.matcher(rss);
        while (item.find() && out.size() < max) {
            Matcher title = TITLE.matcher(item.group(1));
            if (!title.find()) continue;
            String t = clean(title.group(1));
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    static String clean(String raw) {
        Matcher c = CDATA.matcher(raw);
        String s = c.matches() ? c.group(1) : decode(raw);
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static String decode(String s) {
        StringBuffer sb = new StringBuffer();
        Matcher m = NUMERIC.matcher(s);
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /** One line for the marquee: "Rubrik   •   Rubrik   •   …". */
    static String tickerLine(List<String> titles) {
        return String.join("     •     ", titles);
    }

    /** Blocking: call it off the main thread. */
    static List<String> fetch(String url, int max) throws Exception {
        return titles(Http.get(url), max);
    }
}
