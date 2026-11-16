package com.example.radioplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchStationsActivity extends AppCompatActivity {

    private EditText searchQueryEdit;
    private Button searchActionButton;
    private Spinner countrySpinner;
    private ProgressBar searchProgress;
    private TextView searchStatusText;
    private ListView searchResultsList;
    private final List<RadioStationParser.RadioStation> results = new ArrayList<>();
    private final ArrayList<RadioStationParser.RadioStation> addedStations = new ArrayList<>();
    private final Set<String> addedSources = new HashSet<>();
    private final List<RadioBrowserClient.CountryInfo> countries = new ArrayList<>();
    private ArrayAdapter<RadioBrowserClient.CountryInfo> countryAdapter;
    private String lastSelectedCountryCode = "";
    private SearchAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_stations);

        searchQueryEdit = findViewById(R.id.searchQueryEdit);
        searchActionButton = findViewById(R.id.searchActionButton);
        countrySpinner = findViewById(R.id.countrySpinner);
        searchProgress = findViewById(R.id.searchProgress);
        searchStatusText = findViewById(R.id.searchStatusText);
        searchResultsList = findViewById(R.id.searchResultsList);
        ImageButton backButton = findViewById(R.id.searchBackButton);

        backButton.setOnClickListener(v -> finishWithResult());

        adapter = new SearchAdapter();
        searchResultsList.setAdapter(adapter);

        countries.add(new RadioBrowserClient.CountryInfo("All Countries", "", 0));
        countryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, countries);
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        countrySpinner.setAdapter(countryAdapter);
        countrySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RadioBrowserClient.CountryInfo selected = getSelectedCountry();
                String code = selected != null ? selected.isoCode : "";
                if (code.equals(lastSelectedCountryCode)) {
                    return;
                }
                lastSelectedCountryCode = code;
                String query = searchQueryEdit.getText().toString().trim();
                if (!code.isEmpty() || !query.isEmpty()) {
                    runSearch();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadCountries();

        searchActionButton.setOnClickListener(v -> runSearch());
        searchQueryEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        searchResultsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < results.size()) {
                addStation(results.get(position));
            }
        });
    }

    private RadioBrowserClient.CountryInfo getSelectedCountry() {
        Object item = countrySpinner.getSelectedItem();
        if (item instanceof RadioBrowserClient.CountryInfo) {
            return (RadioBrowserClient.CountryInfo) item;
        }
        return null;
    }

    private void loadCountries() {
        executor.execute(() -> {
            try {
                List<RadioBrowserClient.CountryInfo> fetched = RadioBrowserClient.fetchCountries();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    countries.clear();
                    countries.add(new RadioBrowserClient.CountryInfo("All Countries", "", 0));
                    countries.addAll(fetched);
                    countryAdapter.notifyDataSetChanged();
                });
            } catch (Exception ignored) {
                // Keep "All Countries" available even if country list fetch fails
            }
        });
    }

    private void runSearch() {
        String query = searchQueryEdit.getText().toString().trim();
        RadioBrowserClient.CountryInfo selectedCountry = getSelectedCountry();
        String countryCode = selectedCountry != null ? selectedCountry.isoCode : "";

        if (query.isEmpty() && countryCode.isEmpty()) {
            searchQueryEdit.setError("Enter a station name or select a country");
            return;
        }
        searchQueryEdit.setError(null);
        searchProgress.setVisibility(View.VISIBLE);
        searchActionButton.setEnabled(false);
        searchStatusText.setText(countryCode.isEmpty()
                ? "Searching radio-browser.info..."
                : "Loading stations for " + selectedCountry.name + "...");

        executor.execute(() -> {
            try {
                List<RadioStationParser.RadioStation> found = RadioBrowserClient.searchStations(query, countryCode);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    searchProgress.setVisibility(View.GONE);
                    searchActionButton.setEnabled(true);
                    results.clear();
                    results.addAll(found);
                    adapter.notifyDataSetChanged();
                    if (found.isEmpty()) {
                        searchStatusText.setText("No stations found.");
                    } else {
                        searchStatusText.setText("Found " + found.size() + " stations. Tap Add to import.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    searchProgress.setVisibility(View.GONE);
                    searchActionButton.setEnabled(true);
                    searchStatusText.setText("Search failed: " + e.getMessage());
                });
            }
        });
    }

    private void addStation(RadioStationParser.RadioStation station) {
        if (station == null || station.source == null) return;
        if (addedSources.contains(station.source)) {
            Toast.makeText(this, "Already added", Toast.LENGTH_SHORT).show();
            return;
        }
        addedSources.add(station.source);
        addedStations.add(station);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "Added: " + station.title, Toast.LENGTH_SHORT).show();
    }

    private void finishWithResult() {
        if (!addedStations.isEmpty()) {
            Intent data = new Intent();
            data.putExtra("added_stations", addedStations);
            setResult(RESULT_OK, data);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithResult();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private class SearchAdapter extends ArrayAdapter<RadioStationParser.RadioStation> {
        SearchAdapter() {
            super(SearchStationsActivity.this, R.layout.search_station_item, results);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.search_station_item, parent, false);
                holder = new ViewHolder();
                holder.title = convertView.findViewById(R.id.searchItemTitle);
                holder.meta = convertView.findViewById(R.id.searchItemMeta);
                holder.addButton = convertView.findViewById(R.id.searchItemAddButton);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            RadioStationParser.RadioStation station = getItem(position);
            if (station != null) {
                holder.title.setText(StationStore.safeTitle(station));
                holder.meta.setText(station.tags != null && !station.tags.isEmpty() ? station.tags : station.source);
                boolean isAdded = addedSources.contains(station.source);
                holder.addButton.setText(isAdded ? "Added" : "Add");
                holder.addButton.setEnabled(!isAdded);
                holder.addButton.setOnClickListener(v -> addStation(station));
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        TextView title;
        TextView meta;
        Button addButton;
    }
}
