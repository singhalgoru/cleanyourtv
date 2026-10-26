package io.github.singhalgoru.watchroom;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

final class Catalog {
    enum Kind { TV, MOVIES }

    static final class Title {
        final String name, year, genre, rating, outlet, age, link, artwork, summary;
        final Kind kind;

        Title(Kind kind, String name, String year, String genre, String rating,
                String outlet, String age, String link, String artwork, String summary) {
            this.kind = kind;
            this.name = name;
            this.year = year;
            this.genre = genre;
            this.rating = rating;
            this.outlet = outlet;
            this.age = age;
            this.link = link;
            this.artwork = artwork;
            this.summary = summary;
        }
    }

    static String searchUrl(Kind kind, String query) throws java.io.UnsupportedEncodingException {
        String text = query.trim();
        if (text.isEmpty() || text.length() > 80) {
            throw new IllegalArgumentException("Search must contain 1-80 characters");
        }
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        if (kind == Kind.TV) return "https://api.tvmaze.com/search/shows?q=" + encoded;
        // Apple's movie/entity filters can return empty data; its general search is heterogeneous.
        return "https://itunes.apple.com/search?term=" + encoded + "&country=IN&limit=50";
    }

    static List<Title> parse(Kind kind, String json) throws JSONException {
        JSONArray rows = kind == Kind.TV ? new JSONArray(json)
                : new JSONObject(json).getJSONArray("results");
        List<Title> result = new ArrayList<>();
        for (int i = 0; i < rows.length() && result.size() < 24; i++) {
            JSONObject row = rows.getJSONObject(i);
            if (kind == Kind.TV) {
                JSONObject show = row.getJSONObject("show");
                String rating = "";
                JSONObject scores = show.optJSONObject("rating");
                if (scores != null && !scores.isNull("average")) {
                    double score = scores.getDouble("average");
                    if (!Double.isFinite(score) || score < 0 || score > 10) {
                        throw new JSONException("Invalid TVmaze rating");
                    }
                    rating = String.format(Locale.ROOT, "%.1f", score);
                }
                JSONObject channel = show.optJSONObject("webChannel");
                if (channel == null) channel = show.optJSONObject("network");
                String outlet = channel == null ? "" : optional(channel, "name");
                if (channel != null && channel.optJSONObject("country") != null) {
                    outlet += " (" + channel.getJSONObject("country").getString("name") + ")";
                }
                JSONArray genres = show.optJSONArray("genres");
                List<String> names = new ArrayList<>();
                if (genres != null) {
                    for (int j = 0; j < genres.length(); j++) names.add(genres.getString(j));
                }
                result.add(new Title(kind, required(show, "name"), year(show, "premiered"),
                        String.join(", ", names), rating, outlet, "",
                        checkedLink(required(show, "url"), kind), artwork(show), optional(show, "summary")));
            } else if ("feature-movie".equals(row.optString("kind"))) {
                result.add(new Title(kind, required(row, "trackName"), year(row, "releaseDate"),
                        optional(row, "primaryGenreName"), "", "",
                        optional(row, "contentAdvisoryRating"),
                        checkedLink(required(row, "trackViewUrl"), kind), "", ""));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String required(JSONObject row, String key) throws JSONException {
        if (row.isNull(key)) throw new JSONException("Missing " + key);
        String value = row.getString(key).trim();
        if (value.isEmpty() || "null".equals(value)) throw new JSONException("Missing " + key);
        return value;
    }

    private static String optional(JSONObject row, String key) throws JSONException {
        return row.isNull(key) || !row.has(key) ? "" : row.getString(key);
    }

    private static String artwork(JSONObject show) throws JSONException {
        JSONObject image = show.optJSONObject("image");
        if (image == null || image.isNull("medium")) return "";
        String address = image.getString("medium");
        try {
            URI uri = new URI(address);
            if (!"https".equals(uri.getScheme()) || !"static.tvmaze.com".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !uri.getPath().startsWith("/uploads/images/medium_portrait/")) {
                throw new JSONException("Unexpected artwork address");
            }
        } catch (URISyntaxException e) {
            throw new JSONException("Invalid artwork address");
        }
        return address;
    }

    private static String year(JSONObject row, String key) throws JSONException {
        if (row.isNull(key) || !row.has(key)) return "";
        String value = row.getString(key);
        try {
            if (value.length() < 10) throw new DateTimeParseException("Short date", value, 0);
            return Integer.toString(LocalDate.parse(value.substring(0, 10)).getYear());
        } catch (DateTimeParseException e) {
            throw new JSONException("Invalid catalog date: " + key);
        }
    }

    static String checkedLink(String link, Kind kind) throws JSONException {
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            boolean allowed = kind == Kind.TV
                    ? "www.tvmaze.com".equals(host) || "tvmaze.com".equals(host)
                    : "itunes.apple.com".equals(host) || "tv.apple.com".equals(host);
            if (!allowed || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new JSONException("Unexpected catalog link");
            }
            return link;
        } catch (URISyntaxException e) {
            throw new JSONException("Invalid catalog link");
        }
    }

    static final class Client {
        private static final int MAX_BYTES = 1_500_000;
        private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();

        List<Title> search(Kind kind, String query) throws IOException, JSONException {
            return parse(kind, new String(read(searchUrl(kind, query), MAX_BYTES), StandardCharsets.UTF_8));
        }

        byte[] read(String address, int maximumBytes) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Watchroom/1.0 AndroidTV");
            connection.setRequestProperty("Accept", "application/json");
            active.set(connection);
            try {
                interrupted();
                int code = connection.getResponseCode();
                if (code == 429) throw new IOException("Provider rate limit; wait before refreshing");
                if (code != 200) throw new IOException("Provider returned HTTP " + code);
                if (connection.getContentLengthLong() > maximumBytes) {
                    throw new IOException("Catalog response is too large");
                }
                try (InputStream input = connection.getInputStream();
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        interrupted();
                        if (bytes.size() + n > maximumBytes) throw new IOException("Provider response is too large");
                        bytes.write(buffer, 0, n);
                    }
                    interrupted();
                    return bytes.toByteArray();
                }
            } finally {
                active.compareAndSet(connection, null);
                connection.disconnect();
            }
        }

        void cancel() {
            HttpURLConnection connection = active.getAndSet(null);
            if (connection != null) connection.disconnect();
        }

        private static void interrupted() throws InterruptedIOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Request cancelled");
        }
    }
}
