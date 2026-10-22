package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The weather panel's and news ticker's only network code: one GET, short timeouts, a size cap. */
final class Http {

    /** YR's terms of service ask for a User-Agent that names the app and a contact. */
    static final String USER_AGENT = "tvhome-saga/1.0 github.com/elmacogbg/cleanyourtv";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private Http() {}

    static String get(String url) throws IOException {
        return request(url, null, null, null);
    }

    /** GET with an Authorization header (Västtrafik's bearer token). */
    static String get(String url, String authorization) throws IOException {
        return request(url, authorization, null, null);
    }

    /** POST a form body, for Västtrafik's token endpoint. */
    static String postForm(String url, String authorization, String form) throws IOException {
        return request(url, authorization, "application/x-www-form-urlencoded", form);
    }

    private static String request(String url, String authorization, String contentType, String body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                try (java.io.OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("response larger than 4 MB: " + url);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Failures are quiet on screen (the panel keeps its last data or stays hidden) but not in the log. */
    static void log(String what, Exception e) {
        try {
            android.util.Log.w("SagaExtras", what + ": " + e);
        } catch (Throwable ignored) {
            // JVM unit tests have no android.util.Log.
        }
    }
}
