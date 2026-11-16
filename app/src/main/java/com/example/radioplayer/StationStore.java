package com.example.radioplayer;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists stations in XML format in internal storage, migrating legacy stations.ser if present.
 */
public final class StationStore {
    private static final String XML_FILE = "stations.xml";
    private static final String LEGACY_SER_FILE = "stations.ser";

    private StationStore() {}

    @SuppressWarnings("unchecked")
    public static List<RadioStationParser.RadioStation> load(Context context) {
        List<RadioStationParser.RadioStation> stations = null;
        File xmlFile = new File(context.getFilesDir(), XML_FILE);

        if (xmlFile.exists()) {
            try (InputStream is = new FileInputStream(xmlFile)) {
                stations = RadioStationParser.parseStations(is);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (stations == null || stations.isEmpty()) {
            File legacyFile = new File(context.getFilesDir(), LEGACY_SER_FILE);
            if (legacyFile.exists()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(legacyFile))) {
                    stations = (List<RadioStationParser.RadioStation>) ois.readObject();
                    if (stations != null && !stations.isEmpty()) {
                        save(context, stations);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        if (stations == null || stations.isEmpty()) {
            try (InputStream is = context.getAssets().open("stations.xml")) {
                stations = RadioStationParser.parseStations(is);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (stations == null) {
            stations = new ArrayList<>();
        }
        sort(stations);
        return stations;
    }

    public static void save(Context context, List<RadioStationParser.RadioStation> stations) {
        try {
            String xml = RadioStationParser.serializeStationsXml(stations);
            File xmlFile = new File(context.getFilesDir(), XML_FILE);
            try (FileOutputStream fos = new FileOutputStream(xmlFile)) {
                fos.write(xml.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static int mergeStations(List<RadioStationParser.RadioStation> target, List<RadioStationParser.RadioStation> incoming) {
        if (target == null || incoming == null) return 0;
        Set<String> existingSources = new HashSet<>();
        for (RadioStationParser.RadioStation s : target) {
            if (s != null && s.source != null) {
                existingSources.add(s.source.trim());
            }
        }
        int added = 0;
        for (RadioStationParser.RadioStation s : incoming) {
            if (s == null || s.source == null) continue;
            String src = s.source.trim();
            if (src.isEmpty() || existingSources.contains(src)) continue;
            s.source = src;
            if (s.title == null || s.title.trim().isEmpty()) {
                s.title = src;
            }
            s.name = s.title;
            target.add(s);
            existingSources.add(src);
            added++;
        }
        sort(target);
        return added;
    }

    public static void sort(List<RadioStationParser.RadioStation> stations) {
        if (stations == null) return;
        Collections.sort(stations, (s1, s2) -> safeTitle(s1).compareToIgnoreCase(safeTitle(s2)));
    }

    public static String safeTitle(RadioStationParser.RadioStation station) {
        return (station != null && station.title != null) ? station.title : "";
    }
}
