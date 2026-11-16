package com.example.radioplayer;

import android.content.Context;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RadioStationParser {
    
    public static List<RadioStation> parse(Context context, int resourceId) {
        try (InputStream is = context.getResources().openRawResource(resourceId)) {
            return parseStations(is);
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public static List<RadioStation> parseStations(InputStream inputStream) {
        List<RadioStation> stations = new ArrayList<>();
        
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(inputStream, null);
            
            int eventType = parser.getEventType();
            RadioStation currentStation = null;
            String currentTag = "";
            
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = tagName;
                        if ("Station".equalsIgnoreCase(tagName)) {
                            currentStation = new RadioStation();
                            String titleAttr = parser.getAttributeValue(null, "title");
                            currentStation.title = titleAttr != null ? titleAttr.trim() : "";
                            currentStation.name = currentStation.title;
                            currentStation.url = parser.getAttributeValue(null, "url");
                            String favAttr = parser.getAttributeValue(null, "favorite");
                            currentStation.isFavorite = "true".equalsIgnoreCase(favAttr);
                            String tagsAttr = parser.getAttributeValue(null, "tags");
                            currentStation.tags = tagsAttr != null ? tagsAttr.trim() : "";
                            String faviconAttr = parser.getAttributeValue(null, "favicon");
                            currentStation.favicon = faviconAttr != null ? faviconAttr.trim() : "";
                        }
                        break;
                        
                    case XmlPullParser.TEXT:
                        if (currentStation != null && "Source".equalsIgnoreCase(currentTag)) {
                            String text = parser.getText();
                            if (text != null) {
                                currentStation.source = text.trim();
                                // If url is empty or looks like a web homepage, use source as url for PlaybackService
                                if (currentStation.url == null || currentStation.url.isEmpty() || currentStation.url.startsWith("www")) {
                                    currentStation.url = currentStation.source;
                                }
                            }
                        }
                        break;
                        
                    case XmlPullParser.END_TAG:
                        if ("Station".equalsIgnoreCase(tagName) && currentStation != null) {
                            stations.add(currentStation);
                            currentStation = null;
                        }
                        currentTag = "";
                        break;
                }
                eventType = parser.next();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return stations;
    }

    public static String serializeStationsXml(List<RadioStation> stations) throws Exception {
        XmlSerializer serializer = Xml.newSerializer();
        StringWriter writer = new StringWriter();
        serializer.setOutput(writer);
        serializer.startDocument("UTF-8", true);
        serializer.startTag("", "Screamer");
        serializer.attribute("", "version", "Screamer Radio Favorites");
        if (stations != null) {
            for (RadioStation s : stations) {
                if (s == null || s.source == null || s.source.trim().isEmpty()) continue;
                serializer.startTag("", "Station");
                serializer.attribute("", "title", s.title != null ? s.title : "");
                serializer.attribute("", "url", s.url != null ? s.url : "");
                if (s.isFavorite) {
                    serializer.attribute("", "favorite", "true");
                }
                if (s.tags != null && !s.tags.isEmpty()) {
                    serializer.attribute("", "tags", s.tags);
                }
                if (s.favicon != null && !s.favicon.isEmpty()) {
                    serializer.attribute("", "favicon", s.favicon);
                }
                serializer.startTag("", "Source");
                serializer.text(s.source.trim());
                serializer.endTag("", "Source");
                serializer.endTag("", "Station");
            }
        }
        serializer.endTag("", "Screamer");
        serializer.endDocument();
        return writer.toString();
    }

    public static List<RadioStation> parseAuto(String content, String hint) {
        if (content == null) return new ArrayList<>();
        String trimmed = content.trim();
        String lowerHint = hint != null ? hint.toLowerCase(Locale.US) : "";
        if (trimmed.startsWith("<") || lowerHint.endsWith(".xml")) {
            return parseStations(new ByteArrayInputStream(trimmed.getBytes(StandardCharsets.UTF_8)));
        }
        if (trimmed.toLowerCase(Locale.US).contains("[playlist]") || lowerHint.endsWith(".pls")) {
            return parsePls(trimmed);
        }
        return parseM3u(trimmed);
    }

    public static List<RadioStation> parseM3u(String content) {
        List<RadioStation> stations = new ArrayList<>();
        if (content == null) return stations;
        String pendingTitle = null;
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                if (comma != -1 && comma + 1 < line.length()) {
                    pendingTitle = line.substring(comma + 1).trim();
                }
            } else if (!line.startsWith("#")) {
                RadioStation s = new RadioStation();
                s.source = line;
                s.url = line;
                s.title = (pendingTitle != null && !pendingTitle.isEmpty()) ? pendingTitle : deriveTitleFromUrl(line);
                s.name = s.title;
                stations.add(s);
                pendingTitle = null;
            }
        }
        return stations;
    }

    public static List<RadioStation> parsePls(String content) {
        List<RadioStation> stations = new ArrayList<>();
        if (content == null) return stations;
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("[") || line.startsWith("#") || line.startsWith(";")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            String lowerKey = key.toLowerCase(Locale.US);
            if (lowerKey.startsWith("file")) {
                files.put(lowerKey.substring(4), val);
            } else if (lowerKey.startsWith("title")) {
                titles.put(lowerKey.substring(5), val);
            }
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String url = entry.getValue();
            if (url == null || url.isEmpty()) continue;
            String title = titles.get(entry.getKey());
            RadioStation s = new RadioStation();
            s.source = url;
            s.url = url;
            s.title = (title != null && !title.isEmpty()) ? title : deriveTitleFromUrl(url);
            s.name = s.title;
            stations.add(s);
        }
        return stations;
    }

    private static String deriveTitleFromUrl(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return (host != null && !host.isEmpty()) ? host : url;
        } catch (Exception e) {
            return url;
        }
    }
    
    public static class RadioStation implements Serializable {
        private static final long serialVersionUID = 1L;
        public String title;
        public String name; // Alias for title used in PlaybackService
        public String url;  // Stream URL or Website
        public String source; // Stream URL
        public String tags = ""; // Added for PlaybackService compatibility
        public String favicon = ""; // Added for PlaybackService compatibility
        public boolean isFavorite = false;
        
        @Override
        public String toString() {
            return title;
        }
    }
}
