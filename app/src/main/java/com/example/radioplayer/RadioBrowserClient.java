package com.example.radioplayer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Queries the public radio-browser.info API and fetches remote .m3u/.pls/.xml playlists.
 */
public final class RadioBrowserClient {
    private static final String[] API_HOSTS = {
            "https://de1.api.radio-browser.info",
            "https://at1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info"
    };
    private static final String USER_AGENT = "RadioPlayer/1.0 (Android)";

    private RadioBrowserClient() {}

    public static List<RadioStationParser.RadioStation> searchStations(String query) throws IOException {
        String encoded = URLEncoder.encode(query != null ? query.trim() : "", "UTF-8");
        String path = "/json/stations/search?name=" + encoded + "&limit=40&hidebroken=true&order=clickcount&reverse=true";

        IOException lastError = null;
        for (String host : API_HOSTS) {
            try {
                String json = fetchText(host + path);
                return parseRadioBrowserJson(json);
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("Unable to reach radio-browser.info");
    }

    public static String findStationFavicon(String streamUrl, String stationName) {
        if (streamUrl != null && !streamUrl.trim().isEmpty()) {
            try {
                String encodedUrl = URLEncoder.encode(streamUrl.trim(), "UTF-8");
                String fav = queryFirstFavicon("/json/stations/byurl?url=" + encodedUrl);
                if (fav != null && !fav.isEmpty()) return fav;
            } catch (Exception ignored) {}
        }
        if (stationName != null && !stationName.trim().isEmpty()) {
            try {
                List<RadioStationParser.RadioStation> matches = searchStations(stationName.trim());
                for (RadioStationParser.RadioStation s : matches) {
                    if (s.favicon != null && !s.favicon.trim().isEmpty()) {
                        return s.favicon.trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String queryFirstFavicon(String path) throws IOException {
        for (String host : API_HOSTS) {
            try {
                String json = fetchText(host + path);
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    String fav = obj.optString("favicon", "").trim();
                    if (!fav.isEmpty()) return fav;
                }
                return "";
            } catch (Exception ignored) {}
        }
        return "";
    }

    public static List<RadioStationParser.RadioStation> findStationVariants(String stationName) throws IOException {
        if (stationName == null || stationName.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return searchStations(stationName.trim());
    }

    private static List<RadioStationParser.RadioStation> parseRadioBrowserJson(String json) {
        List<RadioStationParser.RadioStation> results = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String streamUrl = obj.optString("url_resolved", "").trim();
                if (streamUrl.isEmpty()) {
                    streamUrl = obj.optString("url", "").trim();
                }
                if (streamUrl.isEmpty()) continue;

                String name = obj.optString("name", "").trim();
                if (name.isEmpty()) name = streamUrl;

                RadioStationParser.RadioStation station = new RadioStationParser.RadioStation();
                station.title = name;
                station.name = name;
                station.source = streamUrl;
                String homepage = obj.optString("homepage", "").trim();
                station.url = homepage.isEmpty() ? streamUrl : homepage;
                station.favicon = obj.optString("favicon", "").trim();

                String country = obj.optString("countrycode", "").trim();
                String codec = obj.optString("codec", "").trim();
                int bitrate = obj.optInt("bitrate", 0);
                String tags = obj.optString("tags", "").trim();

                StringBuilder meta = new StringBuilder();
                if (!country.isEmpty()) meta.append(country);
                if (!codec.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(codec);
                    if (bitrate > 0) meta.append(" ").append(bitrate).append("k");
                }
                if (!tags.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append(tags);
                }
                station.tags = meta.toString();
                results.add(station);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    public static List<RadioStationParser.RadioStation> fetchPlaylistFromUrl(String urlString) throws IOException {
        String content = fetchText(urlString);
        return RadioStationParser.parseAuto(content, urlString);
    }

    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        int q = lower.indexOf('?');
        if (q != -1) lower = lower.substring(0, q);
        return lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.endsWith(".xml");
    }

    public static String fetchText(String urlString) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }
}
