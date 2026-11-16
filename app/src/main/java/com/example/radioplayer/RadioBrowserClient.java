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
import java.util.Collections;
import java.util.Comparator;
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

    public static final class CountryInfo {
        public final String name;
        public final String isoCode;
        public final int stationCount;

        public CountryInfo(String name, String isoCode, int stationCount) {
            this.name = name;
            this.isoCode = isoCode;
            this.stationCount = stationCount;
        }

        @Override
        public String toString() {
            if (isoCode == null || isoCode.isEmpty()) {
                return name;
            }
            return name + " (" + stationCount + ")";
        }
    }

    public static final class TagInfo {
        public final String name;
        public final int stationCount;

        public TagInfo(String name, int stationCount) {
            this.name = name;
            this.stationCount = stationCount;
        }

        @Override
        public String toString() {
            if (stationCount <= 0) {
                return name;
            }
            return name + " (" + stationCount + ")";
        }
    }

    private RadioBrowserClient() {}

    public static List<RadioStationParser.RadioStation> searchStations(String query) throws IOException {
        return searchStations(query, null, null);
    }

    public static List<RadioStationParser.RadioStation> searchStations(String query, String countryCode) throws IOException {
        return searchStations(query, countryCode, null);
    }

    public static List<RadioStationParser.RadioStation> searchStations(String query, String countryCode, String tag) throws IOException {
        String trimmedQuery = query != null ? query.trim() : "";
        String trimmedCountry = countryCode != null ? countryCode.trim() : "";
        String trimmedTag = tag != null ? tag.trim() : "";

        StringBuilder path = new StringBuilder("/json/stations/search?limit=60&hidebroken=true&order=clickcount&reverse=true");
        if (!trimmedQuery.isEmpty()) {
            path.append("&name=").append(URLEncoder.encode(trimmedQuery, "UTF-8"));
        }
        if (!trimmedCountry.isEmpty()) {
            path.append("&countrycode=").append(URLEncoder.encode(trimmedCountry, "UTF-8"));
        }
        if (!trimmedTag.isEmpty()) {
            path.append("&tag=").append(URLEncoder.encode(trimmedTag, "UTF-8"));
        }

        String json = fetchFromAnyHost(path.toString());
        return parseRadioBrowserJson(json);
    }

    public static List<TagInfo> fetchTags() throws IOException {
        String json = fetchFromAnyHost("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=100");
        List<TagInfo> tags = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").trim();
                int count = obj.optInt("stationcount", 0);
                if (name.isEmpty() || count <= 0) continue;
                tags.add(new TagInfo(name, count));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse genres", e);
        }
        Collections.sort(tags, Comparator.comparing(t -> t.name.toLowerCase(Locale.US)));
        return tags;
    }

    public static List<CountryInfo> fetchCountries() throws IOException {
        String json = fetchFromAnyHost("/json/countries?order=name&hidebroken=true");
        List<CountryInfo> countries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").trim();
                String iso = obj.optString("iso_3166_1", "").trim();
                int count = obj.optInt("stationcount", 0);
                if (name.isEmpty() || iso.isEmpty() || count <= 0) continue;
                countries.add(new CountryInfo(name, iso, count));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse countries", e);
        }
        Collections.sort(countries, Comparator.comparing(c -> c.name.toLowerCase(Locale.US)));
        return countries;
    }

    private static String fetchFromAnyHost(String path) throws IOException {
        IOException lastError = null;
        for (String host : API_HOSTS) {
            try {
                return fetchText(host + path);
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
