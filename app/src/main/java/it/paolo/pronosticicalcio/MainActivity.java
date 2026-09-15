package it.paolo.pronosticicalcio;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PronosticiCalcio";
    private static final String FOOTBALL_DATA_URL = "https://api.football-data.org/v4";
    private static final long CACHE_MS = 6L * 60L * 60L * 1000L;
    private static final int STRONG_THRESHOLD = 70;
    private static final int REQUEST_BACKUP_DATABASE = 4101;
    private static final int REQUEST_RESTORE_DATABASE = 4102;
    private static final String HISTORY_DB_NAME = "pronostici_storico.db";
    // Quanti giorni di storico si tenta di caricare: unica sorgente di verità
    // in PredictionEngine, dato che è un parametro del modello di pronostico.
    private static final int MODEL_HISTORY_DAYS = PredictionEngine.MODEL_HISTORY_DAYS;

    private static final int[] LEAGUE_IDS = {
            2019, 2021, 2014, 2002, 2015, 2003, 2017, 2001
    };

    private static final String[] LEAGUE_NAMES = {
            "Serie A", "Premier League", "La Liga", "Bundesliga", "Ligue 1",
            "Eredivisie", "Primeira Liga", "Champions League"
    };

    private static final Set<Integer> LEAGUES = new HashSet<>(Arrays.asList(
            2019, 2021, 2014, 2002, 2015, 2003, 2017, 2001
    ));

    private LinearLayout matchesContainer;
    private TextView tvAccuracy;
    private MaterialButton btnToday;
    private MaterialButton btnTomorrow;
    private MaterialButton btnDayAfterTomorrow;
    private MaterialButton btnFourthDay;
    private MaterialButton btnStrong;
    private SharedPreferences cache;
    private SharedPreferences prefs;
    private MatchHistoryDatabase historyDatabase;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger dayLoadGeneration = new AtomicInteger(0);

    private Integer selectedLeagueId = null;
    private String selectedLeagueName = "Tutti i campionati";
    private String selectedDate;
    private boolean strongOnly = false;
    private boolean favoritesOnly = false;
    private String filterMode = "ALL";
    private boolean sortByConfidence = false;
    private boolean topFiveOnly = false;
    private List<MatchPrediction> currentMatches = new ArrayList<>();
    private String currentMatchesDate = null;
    private final Set<String> expandedLeagueKeys = new HashSet<>();

    // MatchPrediction e TeamStats sono ora classi separate (stesso package):
    // vedi MatchPrediction.java e TeamStats.java.

    static class RecentTeamMatch {
        String date;
        String opponent;
        int goalsFor;
        int goalsAgainst;
        String outcome;
        boolean playedAtHome;
    }

    static class StandingsRow {
        int position;
        String team;
        int points;
        int played;
        int won;
        int draw;
        int lost;
        int goalDifference;
        int goalsFor;
        int goalsAgainst;
    }

    static class EvaluationStats {
        int total;
        int correct1x2;
        int correctGoal;
        int correctOver15;
        int correctOver;
        double brierSum1x2;

        void add(boolean oneXTwo, boolean goal, boolean over15, boolean over, double brier1x2) {
            total++;
            if (oneXTwo) correct1x2++;
            if (goal) correctGoal++;
            if (over15) correctOver15++;
            if (over) correctOver++;
            brierSum1x2 += brier1x2;
        }

        /** Media del Brier score sull'1X2: 0 = pronostici perfetti, 2 = il peggio possibile
         *  (probabilità piena sull'esito sbagliato). Utile per capire se il modello è ben
         *  calibrato, non solo se "indovina": due modelli possono avere la stessa % di
         *  esiti azzeccati ma un Brier score diverso se uno è più o meno sicuro di sé
         *  quando ha ragione o quando ha torto. */
        double avgBrier1x2() {
            return total == 0 ? 0.0 : brierSum1x2 / total;
        }
    }

    /** Conteggio corretti/totale per una fascia di confidenza (es. "60-69%"), usato per
     *  verificare la calibrazione: se il modello è ben calibrato, tra tutti i pronostici
     *  dati con il 60-69% di confidenza ci si aspetta che siano corretti circa il 60-69%
     *  delle volte, non di più né di meno. */
    static class ConfidenceBucketStats {
        int total;
        int correct;

        void add(boolean correct) {
            total++;
            if (correct) this.correct++;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        matchesContainer = findViewById(R.id.matchesContainer);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        btnToday = findViewById(R.id.btnToday);
        btnTomorrow = findViewById(R.id.btnTomorrow);
        btnDayAfterTomorrow = findViewById(R.id.btnDayAfterTomorrow);
        btnFourthDay = findViewById(R.id.btnFourthDay);
        btnStrong = findViewById(R.id.btnStrong);
        cache = getSharedPreferences("api_cache", MODE_PRIVATE);
        prefs = getSharedPreferences("pronostici_prefs", MODE_PRIVATE);
        historyDatabase = new MatchHistoryDatabase(this);
        selectedDate = prefs.getString("selected_date", dateOffset(0));
        if (dayOffsetForDate(selectedDate) < 0 || dayOffsetForDate(selectedDate) > 3) {
            selectedDate = dateOffset(0);
        }
        int savedLeagueId = prefs.getInt("selected_league_id", -1);
        selectedLeagueId = savedLeagueId < 0 ? null : savedLeagueId;
        // Migrazione dalla vecchia numerazione API-Football agli ID football-data.org.
        if (selectedLeagueId != null && !LEAGUES.contains(selectedLeagueId)) {
            selectedLeagueId = null;
        }
        selectedLeagueName = prefs.getString("selected_league_name", "Tutti i campionati");
        strongOnly = prefs.getBoolean("filter_strong", false);
        filterMode = prefs.getString("filter_mode", "ALL");
        sortByConfidence = prefs.getBoolean("sort_confidence", false);
        topFiveOnly = prefs.getBoolean("top_five", false);
        btnStrong.setText(strongOnly ? "Confidenza ≥70% ✓" : "Confidenza ≥70%");
        purgeExpiredCache();
        updateDayButtons();

        btnToday.setOnClickListener(v -> {
            selectedDate = dateOffset(0);
            favoritesOnly = false;
            updateDayButtons();
            loadDay(selectedDate, true);
        });

        btnTomorrow.setOnClickListener(v -> {
            selectedDate = dateOffset(1);
            favoritesOnly = false;
            updateDayButtons();
            loadDay(selectedDate, true);
        });

        btnDayAfterTomorrow.setOnClickListener(v -> selectDay(2));
        btnFourthDay.setOnClickListener(v -> selectDay(3));

        findViewById(R.id.btnLeagues).setOnClickListener(v -> showLeagueSelector());
        findViewById(R.id.btnStandings).setOnClickListener(v -> showStandingsLeagueSelector());
        findViewById(R.id.btnFavorites).setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            renderFiltered();
            Toast.makeText(this,
                    favoritesOnly ? "Mostro solo i preferiti" : "Mostro tutte le partite",
                    Toast.LENGTH_SHORT).show();
        });

        btnStrong.setOnClickListener(v -> {
            strongOnly = !strongOnly;
            btnStrong.setText(strongOnly ? "Confidenza ≥70% ✓" : "Confidenza ≥70%");
            renderFiltered();
        });

        findViewById(R.id.btnFilters).setOnClickListener(v -> showFiltersDialog());
        findViewById(R.id.btnHistory).setOnClickListener(v -> loadHistory());
        findViewById(R.id.btnStats).setOnClickListener(v -> showPredictionStats());
        findViewById(R.id.btnMenu).setOnClickListener(v -> showMainMenu());

        if (BuildConfig.FOOTBALL_DATA_KEY == null || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            showMessage("FOOTBALL_DATA_KEY non configurata nella build GitHub.");
            tvAccuracy.setText("API mancante");
        } else {
            loadDay(selectedDate, true);
            seedOneHistoricalSeason();
        }
    }

    private void showMainMenu() {
        MaterialCardView panel = new MaterialCardView(this);
        panel.setRadius(dp(26));
        panel.setCardBackgroundColor(getColor(R.color.surface));
        panel.setStrokeColor(getColor(R.color.primary));
        panel.setStrokeWidth(dp(1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(16));
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(true);
        menuScroll.addView(content);
        panel.addView(menuScroll);

        TextView title = text("Pronostici Calcio", 24, R.color.text_primary, true);
        content.addView(title);
        TextView subtitle = text("Database storico • " + historyDatabase.finishedCount()
                + " partite salvate", 13, R.color.text_secondary, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(2);
        subtitleParams.bottomMargin = dp(12);
        content.addView(subtitle, subtitleParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .create();

        content.addView(menuButton("▦  Stato database", false, v -> {
            dialog.dismiss();
            showDatabaseStatus();
        }));

        content.addView(menuButton("🏆  Campionati", false, v -> {
            dialog.dismiss();
            showLeagueSelector();
        }));
        content.addView(menuButton(
                favoritesOnly ? "★  Preferiti attivi" : "☆  Preferiti",
                favoritesOnly,
                v -> {
                    dialog.dismiss();
                    findViewById(R.id.btnFavorites).performClick();
                }));
        content.addView(menuButton("⚙  Filtri pronostici", false, v -> {
            dialog.dismiss();
            showFiltersDialog();
        }));
        content.addView(menuButton(
                strongOnly ? "✓  Confidenza ≥70% attiva" : "◎  Confidenza ≥70%",
                strongOnly,
                v -> {
                    dialog.dismiss();
                    btnStrong.performClick();
                }));
        content.addView(menuButton("▤  Classifiche", false, v -> {
            dialog.dismiss();
            showStandingsLeagueSelector();
        }));
        content.addView(menuButton("◷  Storico e verifiche", false, v -> {
            dialog.dismiss();
            loadHistory();
        }));
        content.addView(menuButton("▥  Statistiche complete", false, v -> {
            dialog.dismiss();
            showPredictionStats();
        }));

        MaterialButton close = new MaterialButton(this);
        close.setText("Chiudi");
        close.setAllCaps(false);
        close.setTextSize(15);
        close.setTextColor(getColor(R.color.bg));
        close.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        close.setCornerRadius(dp(20));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.topMargin = dp(10);
        content.addView(close, closeParams);

        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout(
                        android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private MaterialButton menuButton(
            String label, boolean active, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextColor(getColor(R.color.text_primary));
        button.setBackgroundTintList(ColorStateList.valueOf(
                getColor(active ? R.color.primary_dark : R.color.surface_2)));
        button.setCornerRadius(dp(18));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private void showDatabaseStatus() {
        MatchHistoryDatabase.ArchiveStats stats = historyDatabase.archiveStats();
        int importTotal = MODEL_HISTORY_FD_CODES.length * 5;
        int imported = completedHistoricalImports(importTotal);
        int attempts = prefs.getInt("history_seed_attempts", 0);
        int failures = prefs.getInt("history_seed_failures", 0);
        File dbFile = getDatabasePath("pronostici_storico.db");
        long bytes = dbFile.length();
        File walFile = new File(dbFile.getPath() + "-wal");
        if (walFile.exists()) bytes += walFile.length();

        MaterialCardView panel = new MaterialCardView(this);
        panel.setRadius(dp(26));
        panel.setCardBackgroundColor(getColor(R.color.surface));
        panel.setStrokeColor(getColor(R.color.primary));
        panel.setStrokeWidth(dp(1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(16));
        ScrollView databaseScroll = new ScrollView(this);
        databaseScroll.setFillViewport(true);
        databaseScroll.addView(content);
        panel.addView(databaseScroll);
        content.addView(text("Stato database", 24, R.color.text_primary, true));
        TextView description = text("Archivio permanente usato dal modello", 13,
                R.color.text_secondary, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.bottomMargin = dp(16);
        content.addView(description, descriptionParams);

        content.addView(databaseInfoRow("Partite concluse", String.valueOf(stats.finishedMatches)));
        content.addView(databaseInfoRow("Campionati presenti", String.valueOf(stats.leagues)));
        content.addView(databaseInfoRow("Campionati/stagioni", String.valueOf(stats.leagueSeasons)));
        content.addView(databaseInfoRow("Importazioni riuscite", imported + " di " + importTotal));
        content.addView(databaseInfoRow("Tentativi / falliti", attempts + " / " + failures));
        content.addView(databaseInfoRow("Ultima importazione",
                prefs.getString("history_seed_last_result", "Non ancora eseguita")));
        content.addView(databaseInfoRow("Ora ultimo tentativo",
                formatTimestamp(prefs.getLong("history_seed_last_attempt_at", 0L))));
        String lastImportError = prefs.getString("history_seed_last_error", "");
        if (!lastImportError.isEmpty()) {
            content.addView(databaseInfoBlock("Ultimo errore", lastImportError));
        }
        content.addView(databaseInfoRow("Periodo disponibile",
                formatDatabasePeriod(stats.oldestDate, stats.newestDate)));
        content.addView(databaseInfoRow("Ultimo salvataggio",
                formatTimestamp(stats.lastSavedAt)));
        content.addView(databaseInfoRow("Spazio occupato", formatFileSize(bytes)));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        content.addView(menuButton("⬆  Crea backup database", false, v -> {
            dialog.dismiss();
            chooseBackupDestination();
        }));
        content.addView(menuButton("⬇  Ripristina database", false, v -> {
            dialog.dismiss();
            confirmRestoreDatabase();
        }));
        MaterialButton close = new MaterialButton(this);
        close.setText("Chiudi");
        close.setAllCaps(false);
        close.setTextColor(getColor(R.color.bg));
        close.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.primary)));
        close.setCornerRadius(dp(20));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.topMargin = dp(14);
        content.addView(close, closeParams);
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        });
        dialog.show();
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ITALY)
                .format(new java.util.Date());
        intent.putExtra(Intent.EXTRA_TITLE, "PronosticiCalcio-backup-" + stamp + ".db");
        startActivityForResult(intent, REQUEST_BACKUP_DATABASE);
    }

    private void confirmRestoreDatabase() {
        new AlertDialog.Builder(this)
                .setTitle("Ripristinare il database?")
                .setMessage("Il database attuale verrà sostituito dal backup selezionato. "
                        + "Il file verrà controllato prima di modificare i dati.")
                .setPositiveButton("Scegli backup", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQUEST_RESTORE_DATABASE);
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void exportDatabase(Uri destination) {
        executor.execute(() -> {
            String error = null;
            try {
                File database = getDatabasePath(HISTORY_DB_NAME);
                if (!database.exists()) throw new Exception("Database non disponibile");
                synchronized (historyDatabase) {
                    // close() consolida il WAL nel file principale. Gli altri accessi
                    // al database usano lo stesso monitor e attendono la fine della copia.
                    historyDatabase.close();
                    try (InputStream in = new FileInputStream(database);
                         OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                        if (out == null) throw new Exception("Destinazione non disponibile");
                        copyStream(in, out);
                    }
                }
            } catch (Exception e) {
                error = cleanError(e);
            }
            String finalError = error;
            mainHandler.post(() -> Toast.makeText(this,
                    finalError == null ? "Backup database creato"
                            : "Backup non riuscito: " + finalError,
                    Toast.LENGTH_LONG).show());
        });
    }

    private void restoreDatabase(Uri source) {
        executor.execute(() -> {
            File temporary = new File(getCacheDir(), "pronostici_restore_check.db");
            File database = getDatabasePath(HISTORY_DB_NAME);
            File rollback = new File(getCacheDir(), "pronostici_before_restore.db");
            String error = null;
            int restoredMatches = -1;
            try {
                try (InputStream in = getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(temporary, false)) {
                    if (in == null) throw new Exception("Backup non leggibile");
                    copyStream(in, out);
                }
                validateBackupDatabase(temporary);
                synchronized (historyDatabase) {
                    historyDatabase.close();
                    if (database.exists()) copyFile(database, rollback);
                    try {
                        copyFile(temporary, database);
                        new File(database.getPath() + "-wal").delete();
                        new File(database.getPath() + "-shm").delete();
                        restoredMatches = historyDatabase.finishedCount();
                    } catch (Exception replaceError) {
                        historyDatabase.close();
                        if (rollback.exists()) copyFile(rollback, database);
                        throw replaceError;
                    }
                }
            } catch (Exception e) {
                error = cleanError(e);
            } finally {
                temporary.delete();
                rollback.delete();
            }
            String finalError = error;
            int finalCount = restoredMatches;
            mainHandler.post(() -> {
                Toast.makeText(this, finalError == null
                                ? "Database ripristinato: " + finalCount + " partite concluse"
                                : "Ripristino non riuscito: " + finalError,
                        Toast.LENGTH_LONG).show();
                if (finalError == null) loadDay(selectedDate, true);
            });
        });
    }

    private void validateBackupDatabase(File file) throws Exception {
        SQLiteDatabase check = null;
        Cursor cursor = null;
        try {
            check = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = check.rawQuery("SELECT COUNT(*) FROM matches", null);
            if (!cursor.moveToFirst()) throw new Exception("Backup vuoto");
        } catch (Exception e) {
            throw new Exception("Il file selezionato non è un backup valido");
        } finally {
            if (cursor != null) cursor.close();
            if (check != null) check.close();
        }
    }

    private void copyFile(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to, false)) {
            copyStream(in, out);
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_BACKUP_DATABASE) exportDatabase(data.getData());
        if (requestCode == REQUEST_RESTORE_DATABASE) restoreDatabase(data.getData());
    }

    private View databaseInfoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);
        row.addView(text(label, 14, R.color.text_secondary, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueView = text(value, 14, R.color.text_primary, true);
        valueView.setGravity(Gravity.END);
        row.addView(valueView);
        return row;
    }

    private View databaseInfoBlock(String label, String value) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(14), dp(11), dp(14), dp(11));
        block.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        block.setLayoutParams(params);
        block.addView(text(label, 13, R.color.warn, true));
        TextView detail = text(value, 12, R.color.text_secondary, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(4);
        block.addView(detail, detailParams);
        return block;
    }

    private int completedHistoricalImports(int total) {
        int completed = 0;
        for (int i = 0; i < total; i++) {
            if (prefs.getBoolean("history_seed_done_" + i, false)) completed++;
        }
        return completed;
    }

    private String formatDatabasePeriod(String oldest, String newest) {
        if (oldest == null || newest == null) return "Nessun dato";
        return shortDate(oldest) + " – " + shortDate(newest);
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) return "Mai";
        SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);
        format.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
        return format.format(timestamp);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ITALY, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ITALY, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void showLeagueSelector() {
        String[] items = new String[LEAGUE_NAMES.length + 1];
        items[0] = "Tutti i campionati";
        System.arraycopy(LEAGUE_NAMES, 0, items, 1, LEAGUE_NAMES.length);

        int checked = 0;
        if (selectedLeagueId != null) {
            for (int i = 0; i < LEAGUE_IDS.length; i++) {
                if (LEAGUE_IDS[i] == selectedLeagueId) {
                    checked = i + 1;
                    break;
                }
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Scegli campionato")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which == 0) {
                        selectedLeagueId = null;
                        selectedLeagueName = "Tutti i campionati";
                    } else {
                        selectedLeagueId = LEAGUE_IDS[which - 1];
                        selectedLeagueName = LEAGUE_NAMES[which - 1];
                    }
                    favoritesOnly = false;
                    dialog.dismiss();
                    loadDay(selectedDate, true);
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void loadDay(String date, boolean predictions) {
        expandedLeagueKeys.clear();
        setDayButtonsEnabled(false);
        final int requestGeneration = dayLoadGeneration.incrementAndGet();
        final Integer requestedLeagueId = selectedLeagueId;
        final String requestedLeagueName = selectedLeagueName;
        showLoading("Carico partite reali del " + italianDate(date) + "…");
        updateTopLabel();

        executor.execute(() -> {
            try {
                String body = loadFourDayFixtures();
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("matches");
                if (arr == null) throw new Exception("Calendario non valido");
                List<MatchPrediction> list = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    JSONObject competition = item.optJSONObject("competition");
                    int leagueId = competition == null ? 0 : competition.optInt("id", 0);
                    if (!LEAGUES.contains(leagueId)) continue;
                    if (requestedLeagueId != null && leagueId != requestedLeagueId) continue;
                    if (!date.equals(localDateFromUtc(item.optString("utcDate", "")))) continue;
                    list.add(footballDataToMatch(item));
                }

                Collections.sort(list, (a, b) -> {
                    int leagueCompare = Integer.compare(leagueOrder(a.leagueId), leagueOrder(b.leagueId));
                    if (leagueCompare != 0) return leagueCompare;
                    return a.time.compareTo(b.time);
                });
                for (MatchPrediction m : list) {
                    historyDatabase.upsert("fd:" + m.fixtureId, m, date);
                }
                if (requestGeneration != dayLoadGeneration.get()) return;

                mainHandler.post(() -> {
                    if (requestGeneration != dayLoadGeneration.get()) return;
                    setDayButtonsEnabled(true);
                    updateDayButtons();
                    currentMatches = list;
                    currentMatchesDate = date;
                    if (currentMatches.isEmpty()) {
                        showMessage(requestedLeagueId == null
                                ? "Nessuna partita dei principali campionati europei in questa data."
                                : "Nessuna partita di " + requestedLeagueName + " in questa data.");
                    } else {
                        renderFiltered();
                    }
                });

                if (predictions) {
                    Map<String, TeamStats> history = loadModelHistory(date);
                    if (requestGeneration != dayLoadGeneration.get()) return;
                    Map<String, SeasonPrior> previousSeasonPriors = loadPreviousSeasonPriors(date, list);
                    if (requestGeneration != dayLoadGeneration.get()) return;
                    // Seed Elo dal curriculum della stagione precedente (invece del
                    // piatto 1500 per tutti): le squadre assenti da questa mappa
                    // (es. neopromosse) restano comunque a 1500 di default dentro
                    // calculateEloRatings.
                    Map<String, Double> eloSeeds = new HashMap<>();
                    for (Map.Entry<String, SeasonPrior> entry : previousSeasonPriors.entrySet()) {
                        eloSeeds.put(entry.getKey(), PredictionEngine.eloSeedFromSeasonPrior(entry.getValue()));
                    }
                    Map<String, Double> eloRatings = historyDatabase.calculateEloRatings(date, eloSeeds);
                    int archiveDays = cache.getInt("history_archive_days", 0);
                    for (MatchPrediction m : list) {
                        if (m.finished) continue;
                        HeadToHeadStats headToHead = historyDatabase.headToHead(m.home, m.away, date);
                        String homeKey = TeamNameUtil.normalize(m.home);
                        String awayKey = TeamNameUtil.normalize(m.away);
                        PredictionEngine.calculate(m, history, previousSeasonPriors, archiveDays,
                                headToHead, eloRatings.get(homeKey), eloRatings.get(awayKey));
                        historyDatabase.upsert("fd:" + m.fixtureId, m, date);
                        // Il primo pronostico visto prima del calcio d'inizio viene
                        // congelato: anche Domani alimenta così lo
                        // storico reale, senza poter riscrivere la previsione dopo.
                        savePredictionSnapshot(m, date);
                    }
                    mainHandler.post(() -> {
                        if (requestGeneration == dayLoadGeneration.get()) renderFiltered();
                    });
                }

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (requestGeneration == dayLoadGeneration.get()) {
                        setDayButtonsEnabled(true);
                        updateDayButtons();
                        if (date.equals(currentMatchesDate) && !currentMatches.isEmpty()) {
                            renderFiltered();
                            Toast.makeText(this,
                                    "Aggiornamento non riuscito: mostro i dati già salvati",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            showMessage("Errore dati: " + cleanError(e));
                        }
                    }
                });
            }
        });
    }

    private String loadFourDayFixtures() throws Exception {
        String from = dateOffset(0);
        String to = dateOffset(3);
        String key = "fd_fixtures_4days_" + from + "_" + to;
        String saved = cache.getString(key, null);
        long timestamp = cache.getLong(key + "_ts", 0L);
        if (saved != null && System.currentTimeMillis() - timestamp < CACHE_MS) return saved;

        try {
            throttleFootballDataRequest();
            String body = directGetFootballData(FOOTBALL_DATA_URL + "/matches?dateFrom=" + from
                    + "&dateTo=" + to);
            JSONObject root = new JSONObject(body);
            if (root.optJSONArray("matches") == null) throw new Exception("Calendario non valido");
            cache.edit().putString(key, body).putLong(key + "_ts", System.currentTimeMillis()).apply();
            return body;
        } catch (Exception error) {
            if (saved != null) return saved;
            throw error;
        }
    }

    private MatchPrediction footballDataToMatch(JSONObject item) throws Exception {
        MatchPrediction m = new MatchPrediction();
        JSONObject competition = item.optJSONObject("competition");
        JSONObject home = item.optJSONObject("homeTeam");
        JSONObject away = item.optJSONObject("awayTeam");
        if (competition == null || home == null || away == null) {
            throw new Exception("Partita incompleta");
        }
        m.fixtureId = item.getInt("id");
        m.leagueId = competition.optInt("id", 0);
        m.league = leagueNameForId(m.leagueId, competition.optString("name", "Campionato"));
        m.homeId = home.optInt("id", 0);
        m.awayId = away.optInt("id", 0);
        m.home = home.optString("name", "Casa");
        m.away = away.optString("name", "Trasferta");
        m.time = localTimeFromUtc(item.optString("utcDate", ""));
        m.finished = "FINISHED".equalsIgnoreCase(item.optString("status", ""));
        if (m.finished) {
            JSONObject score = item.optJSONObject("score");
            JSONObject fullTime = score == null ? null : score.optJSONObject("fullTime");
            if (fullTime != null) {
                m.finalHomeGoals = fullTime.optInt("home", -1);
                m.finalAwayGoals = fullTime.optInt("away", -1);
            }
            if (m.finalHomeGoals >= 0 && m.finalAwayGoals >= 0) {
                m.score = m.finalHomeGoals + " - " + m.finalAwayGoals;
                evaluateSavedPrediction(m.fixtureId, m.finalHomeGoals, m.finalAwayGoals);
                String result = savedPredictionResult(m.fixtureId, m.finalHomeGoals, m.finalAwayGoals);
                m.pick = result.isEmpty() ? "Risultato finale " + m.score : result;
                m.analysis = result.isEmpty() ? "Partita terminata. Risultato finale reale."
                        : savedPredictionDetails(m.fixtureId, m.finalHomeGoals, m.finalAwayGoals);
            }
        } else {
            m.pick = "Calcolo modello statistico…";
            m.analysis = "Calcolo modello statistico…";
        }
        return m;
    }

    private String leagueNameForId(int id, String fallback) {
        for (int i = 0; i < LEAGUE_IDS.length; i++) {
            if (LEAGUE_IDS[i] == id) return LEAGUE_NAMES[i];
        }
        return fallback;
    }

    private java.util.Date parseUtcDate(String value) throws Exception {
        SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ITALY);
        input.setTimeZone(TimeZone.getTimeZone("UTC"));
        return input.parse(value);
    }

    private String localDateFromUtc(String value) {
        try {
            SimpleDateFormat output = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            output.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
            return output.format(parseUtcDate(value));
        } catch (Exception e) {
            return value != null && value.length() >= 10 ? value.substring(0, 10) : "";
        }
    }

    private String localTimeFromUtc(String value) {
        try {
            SimpleDateFormat output = new SimpleDateFormat("HH:mm", Locale.ITALY);
            output.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
            return output.format(parseUtcDate(value));
        } catch (Exception e) {
            return "--:--";
        }
    }


    // Codici football-data.org dei campionati per cui costruiamo lo storico.
    // Europa League (3) e Conference League (848), presenti in LEAGUES per
    // le partite del giorno, non hanno copertura sul piano gratuito di
    // football-data.org: restano semplicemente fuori dal modello statistico.
    private static final String[] MODEL_HISTORY_FD_CODES = {
            "SA", "PL", "PD", "BL1", "FL1", "DED", "PPL", "CL"
    };

    // Il piano gratuito di football-data.org limita l'ampiezza di ogni
    // richiesta con dateFrom/dateTo: interroghiamo a blocchi di 10 giorni
    // invece che un giorno alla volta, così l'intero archivio di 60 giorni
    // si popola in sole 6 chiamate (contro 10 richieste/minuto consentite).
    private static final int MODEL_HISTORY_CHUNK_DAYS = 10;

    /**
     * Costruisce lo storico squadre per il modello di pronostico, leggendo
     * i risultati da football-data.org (non da API-Football: il piano
     * gratuito di API-Football blocca la stagione corrente, che è proprio
     * il periodo che qui interessa di più).
     *
     * La mappa restituita è chiave per NOME squadra normalizzato (non per
     * ID numerico): football-data.org e API-Football usano ID diversi per
     * la stessa squadra, mentre i nomi normalizzati combaciano già grazie
     * a TeamNameUtil, la stessa logica usata per "Ultime partite".
     */
    // Throttle condiviso per TUTTE le chiamate a football-data.org (da
    // loadModelHistory, loadPreviousSeasonPriors o qualsiasi altro punto):
    // il piano gratuito consente 10 richieste/minuto, quindi imponiamo
    // almeno 6.5 secondi tra una chiamata e la successiva, indipendentemente
    // da quale metodo/thread la genera. "synchronized" perché fino a 4
    // thread dell'executor potrebbero provare a chiamare l'API in parallelo
    // (es. l'utente passa velocemente da "Oggi" a "Domani").
    private static final Object FOOTBALL_DATA_RATE_LIMIT_LOCK = new Object();
    private static volatile long lastFootballDataRequestAt = 0L;
    private static final long FOOTBALL_DATA_MIN_INTERVAL_MS = 6500L;

    private void throttleFootballDataRequest() throws InterruptedException {
        synchronized (FOOTBALL_DATA_RATE_LIMIT_LOCK) {
            long wait = FOOTBALL_DATA_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFootballDataRequestAt);
            if (wait > 0) Thread.sleep(wait);
            lastFootballDataRequestAt = System.currentTimeMillis();
        }
    }

    private Map<String, TeamStats> loadModelHistory(String targetDate) {
        Map<String, TeamStats> map = new HashMap<>();

        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            Log.w(TAG, "loadModelHistory: FOOTBALL_DATA_KEY non configurata, storico vuoto");
            return map;
        }

        try {
            Calendar target = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"));
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
            target.setTime(f.parse(targetDate));

            Set<String> acceptedCodes = new HashSet<>(Arrays.asList(MODEL_HISTORY_FD_CODES));
            int chunksCovered = 0;
            int totalChunks = (int) Math.ceil(MODEL_HISTORY_DAYS / (double) MODEL_HISTORY_CHUNK_DAYS);

            // Dal blocco più vecchio al più recente. TeamStats conserva gli
            // ultimi 8 inserimenti: questo ordine garantisce che siano davvero
            // le partite più recenti, non le più vecchie dell'archivio.
            for (int chunk = totalChunks - 1; chunk >= 0; chunk--) {
                int backFrom = chunk * MODEL_HISTORY_CHUNK_DAYS + 1;
                int backTo = Math.min(backFrom + MODEL_HISTORY_CHUNK_DAYS - 1, MODEL_HISTORY_DAYS);

                Calendar dayFrom = (Calendar) target.clone();
                dayFrom.add(Calendar.DAY_OF_YEAR, -backTo);
                Calendar dayTo = (Calendar) target.clone();
                dayTo.add(Calendar.DAY_OF_YEAR, -backFrom);

                String dateFrom = f.format(dayFrom.getTime());
                String dateTo = f.format(dayTo.getTime());

                String chunkKey = "model_history_fd_" + dateFrom + "_" + dateTo;
                String body = cache.getString(chunkKey, null);

                if (body == null) {
                    try {
                        // Rispetta il limite di 10 richieste/minuto del piano
                        // gratuito football-data.org, condiviso con tutte le
                        // altre chiamate all'API (vedi throttleFootballDataRequest).
                        throttleFootballDataRequest();

                        body = directGetFootballData(
                                FOOTBALL_DATA_URL + "/matches?dateFrom=" + dateFrom
                                        + "&dateTo=" + dateTo + "&status=FINISHED"
                        );

                        cache.edit()
                                .putString(chunkKey, body)
                                .putLong(chunkKey + "_ts", System.currentTimeMillis())
                                .apply();
                    } catch (Exception e) {
                        Log.w(TAG, "loadModelHistory: fetch blocco " + dateFrom + ".." + dateTo + " fallito", e);
                        body = null;
                    }
                }

                if (body == null) continue;

                try {
                    JSONObject root = new JSONObject(body);
                    JSONArray matches = root.optJSONArray("matches");
                    if (matches == null) continue;

                    // Un blocco copre una finestra di più giorni e l'API non
                    // garantisce l'ordine cronologico delle partite al suo
                    // interno. Le ordiniamo per data (utcDate, formato ISO
                    // 8601: l'ordine lessicografico coincide con quello
                    // cronologico) prima di elaborarle, così l'istantanea
                    // "punti/partita dell'avversario fino a questo momento"
                    // usata più sotto per la forma pesata è davvero
                    // calcolata con i soli dati precedenti, senza look-ahead.
                    List<JSONObject> orderedMatches = new ArrayList<>();
                    for (int i = 0; i < matches.length(); i++) {
                        orderedMatches.add(matches.getJSONObject(i));
                    }
                    Collections.sort(orderedMatches, (a, b) ->
                            a.optString("utcDate", "").compareTo(b.optString("utcDate", "")));

                    for (JSONObject item : orderedMatches) {
                        if (!"FINISHED".equalsIgnoreCase(item.optString("status", ""))) continue;

                        String code = item.optJSONObject("competition") == null
                                ? "" : item.optJSONObject("competition").optString("code", "");
                        if (!acceptedCodes.contains(code)) continue;

                        JSONObject home = item.optJSONObject("homeTeam");
                        JSONObject away = item.optJSONObject("awayTeam");
                        JSONObject score = item.optJSONObject("score");
                        if (home == null || away == null || score == null) continue;

                        JSONObject fullTime = score.optJSONObject("fullTime");
                        if (fullTime == null) continue;

                        int gh = fullTime.optInt("home", -1);
                        int ga = fullTime.optInt("away", -1);
                        if (gh < 0 || ga < 0) continue;

                        storeFootballDataMatch(item);

                        String homeName = TeamNameUtil.normalize(home.optString("name", ""));
                        String awayName = TeamNameUtil.normalize(away.optString("name", ""));
                        if (homeName.isEmpty() || awayName.isEmpty()) continue;

                        int hp = gh > ga ? 3 : (gh == ga ? 1 : 0);
                        int ap = ga > gh ? 3 : (gh == ga ? 1 : 0);

                        TeamStats hs = map.get(homeName);
                        if (hs == null) {
                            hs = new TeamStats();
                            map.put(homeName, hs);
                        }

                        TeamStats as = map.get(awayName);
                        if (as == null) {
                            as = new TeamStats();
                            map.put(awayName, as);
                        }

                        // Istantanea del rendimento avversario PRIMA di
                        // registrare questa partita, per evitare il
                        // look-ahead bias nella forma pesata (vedi
                        // PredictionEngine.weightedRecentPPG): dato che le
                        // partite del blocco sono ora in ordine
                        // cronologico, currentPPG() riflette solo le
                        // partite dell'avversario già giocate fino a qui.
                        double awayPpgBeforeMatch = as.currentPPG();
                        double homePpgBeforeMatch = hs.currentPPG();

                        hs.add(true, gh, ga, hp, awayPpgBeforeMatch);
                        as.add(false, ga, gh, ap, homePpgBeforeMatch);
                    }

                    chunksCovered++;
                } catch (Exception e) {
                    Log.w(TAG, "loadModelHistory: parsing blocco " + dateFrom + ".." + dateTo + " fallito", e);
                }
            }

            int archiveDays = Math.min(chunksCovered * MODEL_HISTORY_CHUNK_DAYS, MODEL_HISTORY_DAYS);
            cache.edit()
                    .putInt("history_archive_days", archiveDays)
                    .putLong("history_archive_last_update", System.currentTimeMillis())
                    .apply();

        } catch (Exception e) {
            Log.w(TAG, "loadModelHistory: costruzione storico fallita per " + targetDate, e);
        }

        return map;
    }

    /**
     * Rendimento medio (gol fatti/subiti, punti a partita) di ogni squadra
     * nella STAGIONE PRECEDENTE, usato come prior informato per lo
     * shrinkage bayesiano in PredictionEngine. A differenza dell'archivio
     * di {@link #loadModelHistory}, qui basta 1 chiamata per campionato
     * (8 in tutto) invece che a blocchi di giorni: una classifica finale
     * di una stagione già conclusa non cambia più, quindi viene messa in
     * cache senza scadenza (nessun controllo TTL, solo "esiste già?").
     */
    private Map<String, SeasonPrior> loadPreviousSeasonPriors(
            String targetDate, List<MatchPrediction> matches) {
        Map<String, SeasonPrior> priors = new HashMap<>();

        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            return priors;
        }

        int previousSeason = seasonForDate(targetDate) - 1;

        Set<String> requiredCodes = new HashSet<>();
        for (MatchPrediction match : matches) {
            String code = competitionCodeForLeague(match.leagueId);
            if (code != null) requiredCodes.add(code);
        }

        // Ordine stabile: i campionati nazionali precedono la Champions.
        // Se una squadra compare in entrambe, il prior domestico è più
        // rappresentativo e non deve essere sovrascritto da quello europeo.
        for (String code : MODEL_HISTORY_FD_CODES) {
            if (!requiredCodes.contains(code)) continue;
            try {
                String cacheKey = "fd_prev_season_standings_" + code + "_" + previousSeason;
                String body = cache.getString(cacheKey, null);

                if (body == null) {
                    // Stesso throttle condiviso usato in loadModelHistory:
                    // tutte le chiamate a football-data.org, da qualsiasi
                    // metodo, rispettano insieme il limite di 10/minuto.
                    throttleFootballDataRequest();

                    body = directGetFootballData(
                            FOOTBALL_DATA_URL + "/competitions/" + code
                                    + "/standings?season=" + previousSeason
                    );

                    cache.edit().putString(cacheKey, body).apply();
                }

                List<StandingsRow> rows = parseStandingsRows(body);
                for (StandingsRow row : rows) {
                    if (row.played <= 0) continue;
                    String key = TeamNameUtil.normalize(row.team);
                    if (key.isEmpty()) continue;

                    double avgGF = (double) row.goalsFor / row.played;
                    double avgGA = (double) row.goalsAgainst / row.played;
                    double ppg = (double) row.points / row.played;
                    if (!priors.containsKey(key)) {
                        priors.put(key, new SeasonPrior(avgGF, avgGA, ppg));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "loadPreviousSeasonPriors: fallito per " + code
                        + " stagione " + previousSeason, e);
            }
        }

        return priors;
    }

    // Il calcolo del pronostico (calculateOwnPrediction/poisson/clampDouble)
    // è ora in PredictionEngine.calculate(...), classe pura e testabile.

    private void renderFiltered() {
        updateTopLabel();
        if (currentMatches == null || currentMatches.isEmpty()) {
            showMessage("Nessuna partita da mostrare.");
            return;
        }

        List<MatchPrediction> filtered = new ArrayList<>();

        for (MatchPrediction m : currentMatches) {
            if (strongOnly && (m.confidence <= 0 || m.confidence < STRONG_THRESHOLD)) continue;
            if (favoritesOnly && !isFavorite(m.fixtureId)) continue;

            if ("1".equals(filterMode) && !"1".equals(m.predicted1x2)) continue;
            if ("X".equals(filterMode) && !"X".equals(m.predicted1x2)) continue;
            if ("2".equals(filterMode) && !"2".equals(m.predicted1x2)) continue;
            if ("GOAL".equals(filterMode) && m.goal < 60) continue;
            if ("OVER15".equals(filterMode) && m.over15 < 60) continue;
            if (("OVER25".equals(filterMode) || "OVER".equals(filterMode)) && m.over25 < 60) continue;

            filtered.add(m);
        }

        if (sortByConfidence || topFiveOnly) {
            Collections.sort(filtered, (a, b) -> Integer.compare(b.confidence, a.confidence));
        }

        if (topFiveOnly && filtered.size() > 5) {
            filtered = new ArrayList<>(filtered.subList(0, 5));
        }

        if (filtered.isEmpty()) {
            renderMatches(filtered, currentMatches);
            if (favoritesOnly) {
                showMessage("Nessuna partita preferita in questa schermata.");
            } else if (strongOnly) {
                showMessage("Nessun pronostico con confidenza del modello almeno 70%.");
            } else {
                showMessage("Nessun pronostico corrisponde ai filtri scelti.");
            }
            return;
        }

        renderMatches(filtered, currentMatches);
    }

    private void showFiltersDialog() {
        String[] items = {
                "Tutti i pronostici",
                "Pronostico 1",
                "Pronostico X",
                "Pronostico 2",
                "Gol ≥ 60%",
                "Più di 1,5 ≥ 60%",
                "Più di 2,5 ≥ 60%",
                "Top 5 del giorno",
                "Ordina per confidenza",
                "Azzera filtri"
        };

        new AlertDialog.Builder(this)
                .setTitle("Filtri pronostici")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            filterMode = "ALL";
                            topFiveOnly = false;
                            sortByConfidence = false;
                            break;
                        case 1:
                            filterMode = "1";
                            topFiveOnly = false;
                            break;
                        case 2:
                            filterMode = "X";
                            topFiveOnly = false;
                            break;
                        case 3:
                            filterMode = "2";
                            topFiveOnly = false;
                            break;
                        case 4:
                            filterMode = "GOAL";
                            topFiveOnly = false;
                            break;
                        case 5:
                            filterMode = "OVER15";
                            topFiveOnly = false;
                            break;
                        case 6:
                            filterMode = "OVER25";
                            topFiveOnly = false;
                            break;
                        case 7:
                            filterMode = "ALL";
                            topFiveOnly = true;
                            sortByConfidence = true;
                            break;
                        case 8:
                            sortByConfidence = !sortByConfidence;
                            Toast.makeText(this,
                                    sortByConfidence
                                            ? "Ordinamento per confidenza attivo"
                                            : "Ordinamento per confidenza disattivato",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 9:
                            filterMode = "ALL";
                            strongOnly = false;
                            favoritesOnly = false;
                            topFiveOnly = false;
                            sortByConfidence = false;
                            btnStrong.setText("Confidenza ≥70%");
                            break;
                    }
                    renderFiltered();
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void renderMatches(List<MatchPrediction> matches) {
        renderMatches(matches, matches);
    }

    private void renderMatches(List<MatchPrediction> matches, List<MatchPrediction> allMatches) {
        matchesContainer.removeAllViews();

        Map<String, List<MatchPrediction>> visibleByLeague = new LinkedHashMap<>();
        for (MatchPrediction match : matches) {
            String key = match.leagueId + "|" + match.league;
            List<MatchPrediction> leagueMatches = visibleByLeague.get(key);
            if (leagueMatches == null) {
                leagueMatches = new ArrayList<>();
                visibleByLeague.put(key, leagueMatches);
            }
            leagueMatches.add(match);
        }

        Map<String, List<MatchPrediction>> totalByLeague = new LinkedHashMap<>();
        for (MatchPrediction match : allMatches) {
            String key = match.leagueId + "|" + match.league;
            if (!totalByLeague.containsKey(key)) totalByLeague.put(key, new ArrayList<>());
            totalByLeague.get(key).add(match);
        }

        for (Map.Entry<String, List<MatchPrediction>> entry : totalByLeague.entrySet()) {
            List<MatchPrediction> totalLeagueMatches = entry.getValue();
            MatchPrediction first = totalLeagueMatches.get(0);
            String leagueKey = first.leagueId + "|" + first.league;
            List<MatchPrediction> leagueMatches = visibleByLeague.get(leagueKey);
            if (leagueMatches == null) leagueMatches = Collections.emptyList();
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setVisibility(expandedLeagueKeys.contains(leagueKey)
                    ? View.VISIBLE : View.GONE);
            for (MatchPrediction match : leagueMatches) {
                content.addView(createMatchCard(match));
            }
            matchesContainer.addView(createLeagueHeader(
                    leagueKey, first.league, leagueMatches.size(), totalLeagueMatches.size(), content));
            matchesContainer.addView(content);
        }
    }

    private View createLeagueHeader(String leagueKey, String leagueName, int visibleCount,
                                    int totalCount,
                                    LinearLayout content) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardBackgroundColor(getColor(R.color.surface_2));
        card.setStrokeColor(getColor(R.color.primary));
        card.setStrokeWidth(dp(1));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(12);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(12), dp(11));

        TextView title = text("🏆  " + leagueName, 17, R.color.primary, true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        String countText;
        if (visibleCount != totalCount) {
            countText = visibleCount + " di " + totalCount + " partite";
        } else {
            countText = totalCount == 1 ? "1 partita" : totalCount + " partite";
        }
        boolean initiallyExpanded = expandedLeagueKeys.contains(leagueKey);
        TextView count = text(countText + (initiallyExpanded ? "  ▴" : "  ▾"),
                12, R.color.text_secondary, true);
        count.setBackgroundResource(R.drawable.bg_chip);
        count.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.addView(count);
        card.addView(row);
        card.setContentDescription(leagueName + ", " + countText
                + (initiallyExpanded ? ", aperto" : ", chiuso"));
        card.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) expandedLeagueKeys.add(leagueKey);
            else expandedLeagueKeys.remove(leagueKey);
            count.setText(countText + (open ? "  ▴" : "  ▾"));
            card.setContentDescription(leagueName + ", " + countText
                    + (open ? ", aperto" : ", chiuso"));
        });
        return card;
    }

    private int leagueOrder(int leagueId) {
        for (int i = 0; i < LEAGUE_IDS.length; i++) {
            if (LEAGUE_IDS[i] == leagueId) return i;
        }
        return LEAGUE_IDS.length;
    }

    private View createMatchCard(MatchPrediction m) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.bottomMargin = dp(14);
        card.setLayoutParams(clp);
        card.setRadius(dp(22));
        card.setCardBackgroundColor(getColor(R.color.surface));
        card.setStrokeColor(getColor(R.color.surface_2));
        card.setStrokeWidth(dp(1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(15), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView matchTime = text(m.time, 13, R.color.primary, true);
        top.addView(matchTime, new LinearLayout.LayoutParams(0, -2, 1));

        MaterialButton favorite = new MaterialButton(this);
        favorite.setText(isFavorite(m.fixtureId) ? "★" : "☆");
        favorite.setAllCaps(false);
        favorite.setTextSize(18);
        favorite.setMinWidth(0);
        favorite.setMinimumWidth(0);
        favorite.setPadding(dp(7), 0, dp(7), 0);
        favorite.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_2)));
        favorite.setOnClickListener(v -> {
            toggleFavorite(m.fixtureId);
            renderFiltered();
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(44), dp(40));
        flp.setMarginStart(dp(8));
        top.addView(favorite, flp);

        root.addView(top);

        LinearLayout teamsRow = new LinearLayout(this);
        teamsRow.setOrientation(LinearLayout.HORIZONTAL);
        teamsRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView homeTeam = text(m.home, 20, R.color.primary, true);
        homeTeam.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        homeTeam.setPadding(0, dp(6), dp(6), dp(6));
        homeTeam.setOnClickListener(v -> showLastFiveMatches(m.homeId, m.home));

        TextView separator = text(" - ", 20, R.color.text_secondary, true);
        separator.setGravity(Gravity.CENTER);

        TextView awayTeam = text(m.away, 20, R.color.primary, true);
        awayTeam.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        awayTeam.setPadding(dp(6), dp(6), 0, dp(6));
        awayTeam.setOnClickListener(v -> showLastFiveMatches(m.awayId, m.away));

        teamsRow.addView(homeTeam, new LinearLayout.LayoutParams(0, -2, 1));
        teamsRow.addView(separator, new LinearLayout.LayoutParams(-2, -2));
        teamsRow.addView(awayTeam, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(8);
        tlp.bottomMargin = dp(12);
        root.addView(teamsRow, tlp);

        if (m.finished && m.score != null && !m.score.isEmpty()) {
            TextView finalScore = text("Finale: " + m.score, 22, R.color.primary, true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.bottomMargin = dp(10);
            root.addView(finalScore, slp);
        }

        if (!m.finished) {
            LinearLayout probs = new LinearLayout(this);
            probs.addView(statBox("1", valueOrDash(m.p1)), statLp());
            probs.addView(statBox("X", valueOrDash(m.px)), statLp());
            probs.addView(statBox("2", valueOrDash(m.p2)), statLp());
            root.addView(probs);

            LinearLayout extras = new LinearLayout(this);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
            elp.topMargin = dp(8);
            extras.addView(statBox("Gol", valueOrDash(m.goal)), statLp());
            extras.addView(statBox("Più di 1,5", valueOrDash(m.over15)), statLp());
            extras.addView(statBox("Più di 2,5", valueOrDash(m.over25)), statLp());
            root.addView(extras, elp);
        }

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.surface_2));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
        dlp.topMargin = dp(14);
        dlp.bottomMargin = dp(12);
        root.addView(divider, dlp);

        root.addView(text(
                m.finished ? "RISULTATO" : "PRONOSTICO MODELLO PROPRIO",
                11, R.color.text_secondary, true
        ));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView pick = text(m.pick, 18, R.color.primary, true);
        row.addView(pick, new LinearLayout.LayoutParams(0, -2, 1));

        if (!m.finished && m.confidence > 0) {
            row.addView(text("Confidenza modello " + m.confidence + "%", 12,
                    m.confidence >= 65 ? R.color.primary : R.color.warn, true));
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        root.addView(row, rlp);

        MaterialButton detail = new MaterialButton(this);
        detail.setText("Dettagli analisi");
        detail.setAllCaps(false);
        detail.setTextColor(getColor(R.color.text_primary));
        detail.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_2)));
        detail.setCornerRadius(dp(18));
        detail.setOnClickListener(v -> showMatchDetails(m));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(46));
        blp.topMargin = dp(12);
        root.addView(detail, blp);

        MaterialButton standings = new MaterialButton(this);
        standings.setText("Posizione in classifica");
        standings.setAllCaps(false);
        standings.setTextColor(getColor(R.color.text_primary));
        standings.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_2)));
        standings.setCornerRadius(dp(18));
        standings.setOnClickListener(v -> loadMatchStandings(m));

        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(-1, dp(46));
        slp2.topMargin = dp(8);
        root.addView(standings, slp2);

        card.addView(root);
        return card;
    }

    private void showLastFiveMatches(int teamId, String teamName) {
        showLoading("Cerco le ultime partite di " + teamName + "…");

        executor.execute(() -> {
            List<RecentTeamMatch> matches = collectLastFiveFromLocalArchive(teamId);
            String onlineError = null;

            if (matches.size() < 5) {
                try {
                    int leagueId = leagueForTeam(teamId);
                    List<RecentTeamMatch> online =
                            fetchRecentTeamMatchesFootballData(leagueId, teamName);

                    if (!online.isEmpty()) {
                        matches = online;
                    }
                } catch (Exception e) {
                    onlineError = cleanError(e);
                }
            }

            final List<RecentTeamMatch> result = matches;
            final String finalOnlineError = onlineError;

            mainHandler.post(() -> {
                renderFiltered();

                if (result.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle(teamName)
                            .setMessage(finalOnlineError == null
                                    ? "Nessuna partita conclusa disponibile."
                                    : "Nessuna partita conclusa disponibile.\n\n" + finalOnlineError)
                            .setPositiveButton("Chiudi", null)
                            .show();
                    return;
                }

                showRecentMatchesDialog(teamName, result);
            });
        });
    }

    private void showRecentMatchesDialog(String teamName, List<RecentTeamMatch> matches) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView title = text("Ultime partite", 12, R.color.text_secondary, true);
        outer.addView(title);

        TextView team = text(teamName, 22, R.color.primary, true);
        LinearLayout.LayoutParams teamLp = new LinearLayout.LayoutParams(-1, -2);
        teamLp.topMargin = dp(2);
        teamLp.bottomMargin = dp(12);
        outer.addView(team, teamLp);

        for (RecentTeamMatch rm : matches) {
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(16));
            card.setCardBackgroundColor(getColor(R.color.surface_2));
            card.setStrokeColor(getColor(R.color.surface_2));
            card.setStrokeWidth(dp(1));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);

            TextView opponent = text(rm.opponent, 16, R.color.text_primary, true);
            left.addView(opponent);

            String venue = rm.playedAtHome ? "Casa" : "Trasferta";
            TextView meta = text(venue, 12, R.color.text_secondary, false);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dp(2);
            left.addView(meta, metaLp);

            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

            // Il punteggio va sempre letto nell'ordine "squadra in casa -
            // squadra in trasferta", come da convenzione calcistica
            // standard: se la squadra del popup ha giocato in trasferta,
            // il suo gol (goalsFor) va per SECONDO, non per primo.
            String scoreText = rm.playedAtHome
                    ? (rm.goalsFor + " - " + rm.goalsAgainst)
                    : (rm.goalsAgainst + " - " + rm.goalsFor);
            TextView score = text(scoreText, 20, R.color.primary, true);
            score.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(score);

            card.addView(row);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(8);
            outer.addView(card, cardLp);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(outer)
                .setPositiveButton("Chiudi", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.primary)));

        dialog.show();
    }

    private int leagueForTeam(int teamId) {
        if (currentMatches != null) {
            for (MatchPrediction m : currentMatches) {
                if (m.homeId == teamId || m.awayId == teamId) {
                    return m.leagueId;
                }
            }
        }

        return selectedLeagueId == null ? 0 : selectedLeagueId;
    }

    private List<RecentTeamMatch> fetchRecentTeamMatchesFootballData(
            int leagueId,
            String teamName
    ) throws Exception {

        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            throw new Exception("FOOTBALL_DATA_KEY non configurata.");
        }

        String code = competitionCodeForLeague(leagueId);
        if (code == null) {
            throw new Exception("Campionato non supportato da football-data.org.");
        }

        int fdTeamId = resolveFootballDataTeamId(code, teamName);
        if (fdTeamId <= 0) {
            throw new Exception("Squadra non trovata su football-data.org.");
        }

        String cacheKey = "fd_recent_" + fdTeamId;
        String body = cache.getString(cacheKey, null);
        long ts = cache.getLong(cacheKey + "_ts", 0);

        if (body == null || System.currentTimeMillis() - ts > 30L * 60L * 1000L) {
            body = directGetFootballData(
                    FOOTBALL_DATA_URL + "/teams/" + fdTeamId
                            + "/matches?status=FINISHED&limit=5"
            );

            cache.edit()
                    .putString(cacheKey, body)
                    .putLong(cacheKey + "_ts", System.currentTimeMillis())
                    .apply();
        }

        return parseFootballDataRecentMatches(body, fdTeamId);
    }

    private int resolveFootballDataTeamId(String competitionCode, String teamName)
            throws Exception {

        String cacheKey = "fd_teams_" + competitionCode;
        String body = cache.getString(cacheKey, null);
        long ts = cache.getLong(cacheKey + "_ts", 0);

        if (body == null || System.currentTimeMillis() - ts > CACHE_MS) {
            body = directGetFootballData(
                    FOOTBALL_DATA_URL + "/competitions/" + competitionCode + "/teams"
            );

            cache.edit()
                    .putString(cacheKey, body)
                    .putLong(cacheKey + "_ts", System.currentTimeMillis())
                    .apply();
        }

        JSONObject root = new JSONObject(body);
        JSONArray teams = root.optJSONArray("teams");
        if (teams == null) return 0;

        String wanted = normalizeTeamName(teamName);

        // Prima corrispondenza esatta/normalizzata.
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.getJSONObject(i);
            String name = team.optString("name", "");
            String shortName = team.optString("shortName", "");
            String tla = team.optString("tla", "");

            if (sameTeam(name, teamName)
                    || sameTeam(shortName, teamName)
                    || (!tla.isEmpty() && normalizeTeamName(tla).equals(wanted))) {
                return team.optInt("id", 0);
            }
        }

        // Secondo tentativo più permissivo.
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.getJSONObject(i);
            String name = normalizeTeamName(team.optString("name", ""));
            String shortName = normalizeTeamName(team.optString("shortName", ""));

            if ((!name.isEmpty() && (name.contains(wanted) || wanted.contains(name)))
                    || (!shortName.isEmpty()
                    && (shortName.contains(wanted) || wanted.contains(shortName)))) {
                return team.optInt("id", 0);
            }
        }

        return 0;
    }

    private List<RecentTeamMatch> parseFootballDataRecentMatches(
            String body,
            int fdTeamId
    ) throws Exception {

        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("matches");
        List<RecentTeamMatch> result = new ArrayList<>();

        if (arr == null) return result;

        // Le API possono restituire ordine cronologico; raccogliamo e poi
        // ordiniamo per data decrescente.
        class TempRecent {
            RecentTeamMatch match;
            String iso;
        }

        List<TempRecent> temp = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);

            if (!"FINISHED".equalsIgnoreCase(item.optString("status", ""))) {
                continue;
            }

            JSONObject home = item.optJSONObject("homeTeam");
            JSONObject away = item.optJSONObject("awayTeam");
            JSONObject score = item.optJSONObject("score");

            if (home == null || away == null || score == null) continue;

            JSONObject fullTime = score.optJSONObject("fullTime");
            if (fullTime == null) continue;

            int gh = fullTime.optInt("home", -1);
            int ga = fullTime.optInt("away", -1);
            if (gh < 0 || ga < 0) continue;

            int homeId = home.optInt("id", 0);
            int awayId = away.optInt("id", 0);
            if (homeId != fdTeamId && awayId != fdTeamId) continue;

            RecentTeamMatch rm = new RecentTeamMatch();

            String utcDate = item.optString("utcDate", "");
            String day = utcDate.length() >= 10 ? utcDate.substring(0, 10) : utcDate;
            rm.date = italianDate(day);

            if (homeId == fdTeamId) {
                rm.opponent = away.optString("shortName",
                        away.optString("name", "Avversario"));
                rm.goalsFor = gh;
                rm.goalsAgainst = ga;
                rm.playedAtHome = true;
            } else {
                rm.opponent = home.optString("shortName",
                        home.optString("name", "Avversario"));
                rm.goalsFor = ga;
                rm.goalsAgainst = gh;
                rm.playedAtHome = false;
            }

            if (rm.goalsFor > rm.goalsAgainst) rm.outcome = "V";
            else if (rm.goalsFor == rm.goalsAgainst) rm.outcome = "P";
            else rm.outcome = "S";

            TempRecent tr = new TempRecent();
            tr.match = rm;
            tr.iso = utcDate;
            temp.add(tr);
        }

        Collections.sort(temp, (a, b) -> b.iso.compareTo(a.iso));

        for (TempRecent tr : temp) {
            result.add(tr.match);
            if (result.size() >= 5) break;
        }

        return result;
    }

    private List<RecentTeamMatch> collectLastFiveFromLocalArchive(int teamId) {
        List<RecentTeamMatch> result = new ArrayList<>();

        Calendar base = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"));
        SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
        keyFormat.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));

        try {
            if (selectedDate != null && !selectedDate.trim().isEmpty()) {
                base.setTime(keyFormat.parse(selectedDate));
            }
        } catch (Exception e) {
            Log.w(TAG, "collectLastFiveFromLocalArchive: data non valida, uso oggi", e);
        }

        for (int back = 1; back <= MODEL_HISTORY_DAYS && result.size() < 5; back++) {
            Calendar day = (Calendar) base.clone();
            day.add(Calendar.DAY_OF_YEAR, -back);
            String date = keyFormat.format(day.getTime());

            String body = cache.getString("model_history_" + date, null);
            if (body == null) body = cache.getString("fixtures_" + date, null);
            if (body == null) body = cache.getString("history_day_" + date, null);
            if (body == null) continue;

            try {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("response");
                if (arr == null) continue;

                for (int i = 0; i < arr.length() && result.size() < 5; i++) {
                    JSONObject item = arr.getJSONObject(i);
                    JSONObject fixture = item.getJSONObject("fixture");

                    String status = fixture.getJSONObject("status").optString("short", "");
                    if (!isFinished(status)) continue;

                    JSONObject teams = item.getJSONObject("teams");
                    JSONObject home = teams.getJSONObject("home");
                    JSONObject away = teams.getJSONObject("away");

                    int homeId = home.optInt("id", 0);
                    int awayId = away.optInt("id", 0);

                    if (homeId != teamId && awayId != teamId) continue;

                    JSONObject goals = item.getJSONObject("goals");
                    int gh = goals.optInt("home", -1);
                    int ga = goals.optInt("away", -1);
                    if (gh < 0 || ga < 0) continue;

                    RecentTeamMatch rm = new RecentTeamMatch();
                    rm.date = italianDate(date);

                    if (homeId == teamId) {
                        rm.opponent = away.optString("name", "Avversario");
                        rm.goalsFor = gh;
                        rm.goalsAgainst = ga;
                        rm.playedAtHome = true;
                    } else {
                        rm.opponent = home.optString("name", "Avversario");
                        rm.goalsFor = ga;
                        rm.goalsAgainst = gh;
                        rm.playedAtHome = false;
                    }

                    if (rm.goalsFor > rm.goalsAgainst) rm.outcome = "V";
                    else if (rm.goalsFor == rm.goalsAgainst) rm.outcome = "P";
                    else rm.outcome = "S";

                    result.add(rm);
                }
            } catch (Exception e) {
                Log.w(TAG, "collectLastFiveFromLocalArchive: giorno " + date + " scartato", e);
            }
        }

        return result;
    }

    private void showMatchDetails(MatchPrediction m) {
        new AlertDialog.Builder(this)
                .setTitle(m.home + " - " + m.away)
                .setMessage(m.analysis)
                .setPositiveButton("Chiudi", null)
                .show();
    }

    private void loadMatchStandings(MatchPrediction m) {
        String code = competitionCodeForLeague(m.leagueId);
        if (code == null) {
            Toast.makeText(
                    this,
                    "Classifica non disponibile per questa competizione nel piano gratuito.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            Toast.makeText(this, "FOOTBALL_DATA_KEY non configurata.", Toast.LENGTH_LONG).show();
            return;
        }

        showLoading("Carico le posizioni di " + m.home + " e " + m.away + "…");

        executor.execute(() -> {
            try {
                String cacheKey = "fd_standings_" + code;
                String body = cache.getString(cacheKey, null);
                long ts = cache.getLong(cacheKey + "_ts", 0);

                if (body == null || System.currentTimeMillis() - ts > CACHE_MS) {
                    body = directGetFootballData(
                            FOOTBALL_DATA_URL + "/competitions/" + code + "/standings"
                    );
                    cache.edit()
                            .putString(cacheKey, body)
                            .putLong(cacheKey + "_ts", System.currentTimeMillis())
                            .apply();
                }

                List<StandingsRow> rows = parseStandingsRows(body);
                if (rows.isEmpty()) throw new Exception("Classifica vuota.");

                mainHandler.post(() -> {
                    renderFiltered();
                    showMatchStandingsDialog(m, rows);
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    renderFiltered();
                    Toast.makeText(
                            this,
                            "Errore classifica: " + cleanError(e),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private String competitionCodeForLeague(int leagueId) {
        switch (leagueId) {
            case 2019: return "SA";
            case 2021: return "PL";
            case 2014: return "PD";
            case 2002: return "BL1";
            case 2015: return "FL1";
            case 2003: return "DED";
            case 2017: return "PPL";
            case 2001: return "CL";
            default: return null;
        }
    }

    private List<StandingsRow> parseStandingsRows(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray standings = root.optJSONArray("standings");
        List<StandingsRow> rows = new ArrayList<>();

        if (standings == null || standings.length() == 0) return rows;

        JSONArray table = null;
        for (int i = 0; i < standings.length(); i++) {
            JSONObject group = standings.getJSONObject(i);
            if ("TOTAL".equalsIgnoreCase(group.optString("type"))) {
                table = group.optJSONArray("table");
                break;
            }
        }
        if (table == null) table = standings.getJSONObject(0).optJSONArray("table");
        if (table == null) return rows;

        for (int i = 0; i < table.length(); i++) {
            JSONObject row = table.getJSONObject(i);
            JSONObject team = row.getJSONObject("team");
            StandingsRow r = new StandingsRow();
            r.position = row.optInt("position");
            r.team = team.optString("name", "Squadra");
            r.points = row.optInt("points");
            r.played = row.optInt("playedGames");
            r.won = row.optInt("won");
            r.draw = row.optInt("draw");
            r.lost = row.optInt("lost");
            r.goalDifference = row.optInt("goalDifference");
            r.goalsFor = row.optInt("goalsFor");
            r.goalsAgainst = row.optInt("goalsAgainst");
            rows.add(r);
        }
        return rows;
    }

    private void showMatchStandingsDialog(MatchPrediction match, List<StandingsRow> rows) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(outer, new ScrollView.LayoutParams(-1, -2));

        TextView note = text(
                "Evidenziate: " + match.home + " e " + match.away,
                13, R.color.primary, true
        );
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.bottomMargin = dp(8);
        outer.addView(note, np);
        outer.addView(buildStandingsHeader());

        for (int i = 0; i < rows.size(); i++) {
            StandingsRow item = rows.get(i);
            boolean highlighted = sameTeam(item.team, match.home) || sameTeam(item.team, match.away);
            outer.addView(buildStandingsRow(item, i % 2 == 0, highlighted));
        }

        new AlertDialog.Builder(this)
                .setTitle(match.home + " - " + match.away)
                .setView(scrollView)
                .setPositiveButton("Chiudi", null)
                .show();
    }

    private boolean sameTeam(String a, String b) {
        return TeamNameUtil.sameTeam(a, b);
    }

    private String normalizeTeamName(String value) {
        return TeamNameUtil.normalize(value);
    }

    private void showStandingsLeagueSelector() {
        final String[] names = {
                "Serie A",
                "Premier League",
                "La Liga",
                "Bundesliga",
                "Ligue 1",
                "Eredivisie",
                "Primeira Liga",
                "Champions League"
        };

        final String[] codes = {
                "SA",
                "PL",
                "PD",
                "BL1",
                "FL1",
                "DED",
                "PPL",
                "CL"
        };

        new AlertDialog.Builder(this)
                .setTitle("Classifica - scegli campionato")
                .setItems(names, (dialog, which) -> {
                    dialog.dismiss();
                    loadStandingsFootballData(names[which], codes[which]);
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void loadStandingsFootballData(String leagueName, String competitionCode) {
        dayLoadGeneration.incrementAndGet();
        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "FOOTBALL_DATA_KEY non configurata nei GitHub Secrets.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        showLoading("Carico la classifica di " + leagueName + "…");
        tvAccuracy.setText("Classifica • " + leagueName);

        executor.execute(() -> {
            try {
                String cacheKey = "fd_standings_" + competitionCode;
                String body = cache.getString(cacheKey, null);
                long ts = cache.getLong(cacheKey + "_ts", 0);

                if (body == null || System.currentTimeMillis() - ts > CACHE_MS) {
                    body = directGetFootballData(
                            FOOTBALL_DATA_URL + "/competitions/" + competitionCode + "/standings"
                    );
                    cache.edit()
                            .putString(cacheKey, body)
                            .putLong(cacheKey + "_ts", System.currentTimeMillis())
                            .apply();
                }

                JSONObject root = new JSONObject(body);
                JSONArray standings = root.optJSONArray("standings");

                if (standings == null || standings.length() == 0) {
                    throw new Exception("Classifica non disponibile.");
                }

                JSONArray table = null;

                for (int i = 0; i < standings.length(); i++) {
                    JSONObject group = standings.getJSONObject(i);
                    if ("TOTAL".equalsIgnoreCase(group.optString("type"))) {
                        table = group.optJSONArray("table");
                        break;
                    }
                }

                if (table == null && standings.length() > 0) {
                    table = standings.getJSONObject(0).optJSONArray("table");
                }

                if (table == null || table.length() == 0) {
                    throw new Exception("Classifica vuota.");
                }

                List<StandingsRow> rows = new ArrayList<>();

                for (int i = 0; i < table.length(); i++) {
                    JSONObject row = table.getJSONObject(i);
                    JSONObject team = row.getJSONObject("team");

                    StandingsRow r = new StandingsRow();
                    r.position = row.optInt("position");
                    r.team = team.optString("name", "Squadra");
                    r.points = row.optInt("points");
                    r.played = row.optInt("playedGames");
                    r.won = row.optInt("won");
                    r.draw = row.optInt("draw");
                    r.lost = row.optInt("lost");
                    r.goalDifference = row.optInt("goalDifference");
                    rows.add(r);
                }

                mainHandler.post(() -> {
                    renderFiltered();
                    showStandingsDialog(leagueName, rows);
                    updateTopLabel();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    renderFiltered();
                    updateTopLabel();
                    Toast.makeText(
                            this,
                            "Errore classifica: " + cleanError(e),
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private void showStandingsDialog(String leagueName, List<StandingsRow> rows) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(8), dp(8), dp(8), dp(8));
        scrollView.addView(outer, new ScrollView.LayoutParams(-1, -2));

        outer.addView(buildStandingsHeader());

        for (int i = 0; i < rows.size(); i++) {
            outer.addView(buildStandingsRow(rows.get(i), i % 2 == 0, false));
        }

        new AlertDialog.Builder(this)
                .setTitle("Classifica - " + leagueName)
                .setView(scrollView)
                .setPositiveButton("Chiudi", null)
                .show();
    }

    private View buildStandingsHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));

        row.addView(standingsCell("#", 0.8f, true, Gravity.CENTER));
        row.addView(standingsCell("Squadra", 2.7f, true, Gravity.START));
        row.addView(standingsCell("Pt", 0.9f, true, Gravity.CENTER));
        row.addView(standingsCell("G", 0.8f, true, Gravity.CENTER));
        row.addView(standingsCell("V", 0.8f, true, Gravity.CENTER));
        row.addView(standingsCell("N", 0.8f, true, Gravity.CENTER));
        row.addView(standingsCell("P", 0.8f, true, Gravity.CENTER));
        row.addView(standingsCell("DR", 1.0f, true, Gravity.CENTER));

        return row;
    }

    private View buildStandingsRow(StandingsRow item, boolean alt, boolean highlighted) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(14));
        card.setStrokeWidth(dp(highlighted ? 3 : 1));
        card.setStrokeColor(getColor(highlighted ? R.color.primary : R.color.surface_2));
        card.setCardBackgroundColor(getColor(alt ? R.color.surface : R.color.surface_2));

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(8);
        card.setLayoutParams(cp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        row.addView(standingsCell(String.valueOf(item.position), 0.8f, false, Gravity.CENTER));
        row.addView(standingsCell(item.team, 2.7f, true, Gravity.START, highlighted));
        row.addView(standingsCell(String.valueOf(item.points), 0.9f, true, Gravity.CENTER));
        row.addView(standingsCell(String.valueOf(item.played), 0.8f, false, Gravity.CENTER));
        row.addView(standingsCell(String.valueOf(item.won), 0.8f, false, Gravity.CENTER));
        row.addView(standingsCell(String.valueOf(item.draw), 0.8f, false, Gravity.CENTER));
        row.addView(standingsCell(String.valueOf(item.lost), 0.8f, false, Gravity.CENTER));
        row.addView(standingsCell((item.goalDifference > 0 ? "+" : "") + item.goalDifference, 1.0f, false, Gravity.CENTER));

        card.addView(row);
        return card;
    }

    private TextView standingsCell(String value, float weight, boolean bold, int gravity) {
        return standingsCell(value, weight, bold, gravity, false);
    }

    private TextView standingsCell(String value, float weight, boolean bold, int gravity, boolean highlighted) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(getColor(highlighted ? R.color.primary : R.color.text_primary));
        tv.setTextSize(value != null && value.length() > 18 ? 12 : 13);
        tv.setGravity(gravity);
        if (bold || highlighted) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        tv.setLayoutParams(lp);
        return tv;
    }

    private String directGetFootballData(String url) throws Exception {
        return httpGet(url, "X-Auth-Token", BuildConfig.FOOTBALL_DATA_KEY);
    }

    /**
     * Importa progressivamente cinque stagioni: una coppia campionato/stagione
     * al giorno. In circa 40 aperture giornaliere l'archivio iniziale è completo,
     * senza concentrare tutte le richieste nello stesso momento.
     */
    private void seedOneHistoricalSeason() {
        if (BuildConfig.FOOTBALL_DATA_KEY == null
                || BuildConfig.FOOTBALL_DATA_KEY.trim().isEmpty()) return;

        String today = dateOffset(0);
        boolean trackingAlreadyActive = prefs.getInt("history_seed_tracking_version", 0) >= 1;
        if (trackingAlreadyActive
                && today.equals(prefs.getString("history_seed_attempt_date", ""))) return;
        prefs.edit()
                .putString("history_seed_attempt_date", today)
                .putInt("history_seed_tracking_version", 1)
                .apply();

        executor.execute(() -> {
            int total = MODEL_HISTORY_FD_CODES.length * 5;
            int oldCursor = prefs.getInt("history_seed_cursor",
                    prefs.getInt("history_seed_index", 0));
            int cursor = Math.max(0, oldCursor) % total;
            int index = -1;
            for (int offset = 0; offset < total; offset++) {
                int candidate = (cursor + offset) % total;
                if (!prefs.getBoolean("history_seed_done_" + candidate, false)) {
                    index = candidate;
                    break;
                }
            }
            if (index < 0) return;

            String code = MODEL_HISTORY_FD_CODES[index % MODEL_HISTORY_FD_CODES.length];
            int season = seasonForDate(today) - 1 - (index / MODEL_HISTORY_FD_CODES.length);
            String target = code + " " + season;
            int attempts = prefs.getInt("history_seed_attempts", 0) + 1;
            try {
                throttleFootballDataRequest();
                String body = directGetFootballData(FOOTBALL_DATA_URL + "/competitions/"
                        + code + "/matches?season=" + season + "&status=FINISHED");
                JSONObject root = new JSONObject(body);
                JSONArray matches = root.optJSONArray("matches");
                if (matches == null || matches.length() == 0) {
                    throw new Exception("Nessuna partita restituita dall’API");
                }
                int before = historyDatabase.finishedCount();
                for (int i = 0; i < matches.length(); i++) {
                    storeFootballDataMatch(matches.getJSONObject(i));
                }
                int added = Math.max(0, historyDatabase.finishedCount() - before);
                prefs.edit()
                        .putBoolean("history_seed_done_" + index, true)
                        .putString("history_seed_last_result", target + " • riuscita (+" + added + ")")
                        .putString("history_seed_last_error", "")
                        .putLong("history_seed_last_attempt_at", System.currentTimeMillis())
                        .apply();
                Log.i(TAG, "Archivio storico importato: " + code + " " + season
                        + " (" + matches.length() + " partite, " + added + " nuove)");
            } catch (Exception e) {
                int failures = prefs.getInt("history_seed_failures", 0) + 1;
                prefs.edit()
                        .putInt("history_seed_failures", failures)
                        .putString("history_seed_last_result", target + " • fallita")
                        .putString("history_seed_last_error", cleanError(e))
                        .putLong("history_seed_last_attempt_at", System.currentTimeMillis())
                        .apply();
                Log.w(TAG, "Importazione storica non disponibile: " + code + " " + season, e);
            } finally {
                // Si passa al blocco successivo; quelli falliti restano non marcati
                // e verranno riprovati nei cicli seguenti senza bloccare gli altri.
                prefs.edit()
                        .putInt("history_seed_attempts", attempts)
                        .putInt("history_seed_cursor", (index + 1) % total)
                        .apply();
            }
        });
    }

    private void storeFootballDataMatch(JSONObject item) {
        try {
            if (!"FINISHED".equalsIgnoreCase(item.optString("status", ""))) return;
            JSONObject home = item.optJSONObject("homeTeam");
            JSONObject away = item.optJSONObject("awayTeam");
            JSONObject score = item.optJSONObject("score");
            JSONObject fullTime = score == null ? null : score.optJSONObject("fullTime");
            if (home == null || away == null || fullTime == null) return;
            int gh = fullTime.optInt("home", -1);
            int ga = fullTime.optInt("away", -1);
            if (gh < 0 || ga < 0) return;

            JSONObject competition = item.optJSONObject("competition");
            int fixtureId = item.optInt("id", 0);
            String utcDate = item.optString("utcDate", "");
            if (fixtureId <= 0 || utcDate.length() < 10) return;
            historyDatabase.upsertHistorical(
                    "fd:" + fixtureId,
                    fixtureId,
                    utcDate.substring(0, 10),
                    competition == null ? 0 : competition.optInt("id", 0),
                    competition == null ? "Campionato" : competition.optString("name", "Campionato"),
                    home.optInt("id", 0),
                    away.optInt("id", 0),
                    home.optString("name", "Casa"),
                    away.optString("name", "Trasferta"),
                    gh,
                    ga
            );
        } catch (Exception e) {
            Log.w(TAG, "Partita storica non salvata", e);
        }
    }

    private void loadHistory() {
        expandedLeagueKeys.clear();
        dayLoadGeneration.incrementAndGet();
        showLoading("Carico risultati reali ultimi 7 giorni…");
        tvAccuracy.setText("Storico reale");

        executor.execute(() -> {
            List<MatchPrediction> list = new ArrayList<>();
            boolean failed = false;
            try {
                String from = dateOffset(-7);
                String to = dateOffset(-1);
                String key = "fd_history_" + from + "_" + to;
                String body = cache.getString(key, null);
                long timestamp = cache.getLong(key + "_ts", 0L);
                if (body == null || System.currentTimeMillis() - timestamp >= CACHE_MS) {
                    try {
                        throttleFootballDataRequest();
                        body = directGetFootballData(FOOTBALL_DATA_URL + "/matches?dateFrom=" + from
                                + "&dateTo=" + to + "&status=FINISHED");
                        cache.edit().putString(key, body)
                                .putLong(key + "_ts", System.currentTimeMillis()).apply();
                    } catch (Exception e) {
                        if (body == null) throw e;
                    }
                }
                JSONArray matches = new JSONObject(body).optJSONArray("matches");
                if (matches == null) throw new Exception("Storico non valido");
                for (int i = 0; i < matches.length(); i++) {
                    JSONObject item = matches.getJSONObject(i);
                    JSONObject competition = item.optJSONObject("competition");
                    int leagueId = competition == null ? 0 : competition.optInt("id", 0);
                    if (!LEAGUES.contains(leagueId)) continue;
                    if (selectedLeagueId != null && leagueId != selectedLeagueId) continue;
                    MatchPrediction m = footballDataToMatch(item);
                    if (!m.finished || m.finalHomeGoals < 0 || m.finalAwayGoals < 0) continue;
                    String date = localDateFromUtc(item.optString("utcDate", ""));
                    historyDatabase.upsert("fd:" + m.fixtureId, m, date);
                    m.time = italianDate(date);
                    list.add(m);
                }
            } catch (Exception e) {
                failed = true;
            }

            Collections.sort(list, (a, b) -> b.time.compareTo(a.time));

            if (list.size() > 80) {
                list = new ArrayList<>(list.subList(0, 80));
            }

            final List<MatchPrediction> result = list;
            final boolean unavailable = failed;

            mainHandler.post(() -> {
                currentMatches = result;
                currentMatchesDate = null;

                if (result.isEmpty()) {
                    if (unavailable) {
                        showMessage("Storico non disponibile. Mostreremo i dati salvati appena disponibili.");
                    } else {
                        showMessage("Nessun risultato disponibile negli ultimi 7 giorni.");
                    }
                } else {
                    // Lo storico deve mostrare tutte le verifiche, senza essere
                    // nascosto dai filtri eventualmente attivi nella giornata.
                    renderMatches(result);

                }
            });
        });
    }

    private void savePredictionSnapshot(MatchPrediction m, String matchDate) {
        if (m.predicted1x2 == null || m.predicted1x2.isEmpty()) return;

        String key = "saved_prediction_" + m.fixtureId;
        if (!prefs.contains(key)
                || (!prefs.getBoolean(key + "_evaluated", false)
                && !prefs.getBoolean(key + "_metrics_v3", false))) {
            prefs.edit()
                    .putString(key, m.predicted1x2)
                    .putInt(key + "_confidence", m.confidence)
                    .putInt(key + "_p1", m.p1)
                    .putInt(key + "_px", m.px)
                    .putInt(key + "_p2", m.p2)
                    .putInt(key + "_goal_probability", m.goal)
                    .putInt(key + "_over15_probability", m.over15)
                    .putInt(key + "_over_probability", m.over25)
                    .putString(key + "_goal_pick", m.goal >= 50 ? "GOAL" : "NO GOAL")
                    .putString(key + "_over15_pick", m.over15 >= 50 ? "OVER 1,5" : "UNDER 1,5")
                    .putString(key + "_over_pick", m.over25 >= 50 ? "OVER 2,5" : "UNDER 2,5")
                    .putInt(key + "_league_id", m.leagueId)
                    .putString(key + "_league", m.league)
                    .putString(key + "_home", m.home)
                    .putString(key + "_away", m.away)
                    .putString(key + "_date", matchDate)
                    .putString(key + "_time", m.time)
                    .putInt(key + "_model_version", 28)
                    .putBoolean(key + "_metrics_v2", true)
                    .putBoolean(key + "_metrics_v3", true)
                    .putBoolean(key + "_evaluated", false)
                    .apply();
        }
    }

    private String savedPredictionResult(int fixtureId, int homeGoals, int awayGoals) {
        String key = "saved_prediction_" + fixtureId;
        if (!prefs.contains(key)) return "";

        String actual = PredictionEvaluation.actual1x2(homeGoals, awayGoals);

        boolean oneXTwoCorrect = actual.equals(prefs.getString(key, ""));
        if (!prefs.getBoolean(key + "_metrics_v2", false)) {
            return (oneXTwoCorrect ? "✅" : "❌") + " 1X2";
        }
        boolean goalCorrect = PredictionEvaluation.actualGoal(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_goal_pick", ""));
        boolean overCorrect = PredictionEvaluation.actualOver25(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_over_pick", ""));

        String result = (oneXTwoCorrect ? "✅" : "❌") + " 1X2   "
                + (goalCorrect ? "✅" : "❌") + " Goal   "
                + (overCorrect ? "✅" : "❌") + " Over 2,5";
        if (!prefs.getBoolean(key + "_metrics_v3", false)) return result;
        boolean over15Correct = PredictionEvaluation.actualOver15(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_over15_pick", ""));
        return result + "   " + (over15Correct ? "✅" : "❌") + " Over 1,5";
    }

    private String savedPredictionDetails(int fixtureId, int homeGoals, int awayGoals) {
        String key = "saved_prediction_" + fixtureId;
        if (!prefs.contains(key)) return "Nessun pronostico era stato salvato prima della partita.";

        String actual1x2 = PredictionEvaluation.actual1x2(homeGoals, awayGoals);
        String actualGoal = PredictionEvaluation.actualGoal(homeGoals, awayGoals);
        String actualOver15 = PredictionEvaluation.actualOver15(homeGoals, awayGoals);
        String actualOver = PredictionEvaluation.actualOver25(homeGoals, awayGoals);

        return "Pronostico congelato prima della partita\n\n"
                + "1X2: " + prefs.getString(key, "—")
                + "  (1 " + prefs.getInt(key + "_p1", 0) + "% • X "
                + prefs.getInt(key + "_px", 0) + "% • 2 "
                + prefs.getInt(key + "_p2", 0) + "%)\n"
                + "Esito reale: " + actual1x2 + "\n\n"
                + "Goal: " + prefs.getString(key + "_goal_pick", "—")
                + " (" + prefs.getInt(key + "_goal_probability", 0) + "%)\n"
                + "Esito reale: " + actualGoal + "\n\n"
                + "Over 1,5: " + prefs.getString(key + "_over15_pick", "—")
                + " (" + prefs.getInt(key + "_over15_probability", 0) + "%)\n"
                + "Esito reale: " + actualOver15 + "\n\n"
                + "Over: " + prefs.getString(key + "_over_pick", "—")
                + " (" + prefs.getInt(key + "_over_probability", 0) + "%)\n"
                + "Esito reale: " + actualOver + "\n\n"
                + savedPredictionResult(fixtureId, homeGoals, awayGoals);
    }

    private void evaluateSavedPrediction(int fixtureId, int homeGoals, int awayGoals) {
        String key = "saved_prediction_" + fixtureId;
        if (!prefs.contains(key) || prefs.getBoolean(key + "_evaluated", false)) return;

        String actual = PredictionEvaluation.actual1x2(homeGoals, awayGoals);

        boolean correct1x2 = actual.equals(prefs.getString(key, ""));
        boolean hasExtendedMetrics = prefs.getBoolean(key + "_metrics_v2", false);
        boolean hasOver15Metric = prefs.getBoolean(key + "_metrics_v3", false);
        boolean correctGoal = PredictionEvaluation.actualGoal(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_goal_pick", ""));
        boolean correctOver = PredictionEvaluation.actualOver25(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_over_pick", ""));
        boolean correctOver15 = PredictionEvaluation.actualOver15(homeGoals, awayGoals)
                .equals(prefs.getString(key + "_over15_pick", ""));

        prefs.edit()
                .putBoolean(key + "_1x2_correct", correct1x2)
                .putBoolean(key + "_goal_correct", hasExtendedMetrics && correctGoal)
                .putBoolean(key + "_over15_correct", hasOver15Metric && correctOver15)
                .putBoolean(key + "_over_correct", hasExtendedMetrics && correctOver)
                .putInt(key + "_final_home", homeGoals)
                .putInt(key + "_final_away", awayGoals)
                .putBoolean(key + "_evaluated", true)
                .apply();
    }

    private void showPredictionStats() {
        EvaluationStats totalStats = new EvaluationStats();
        Map<String, EvaluationStats> byLeague = new LinkedHashMap<>();
        Map<Integer, ConfidenceBucketStats> byConfidenceBucket = new TreeMap<>();

        for (String key : prefs.getAll().keySet()) {
            if (!isPredictionBaseKey(key)
                    || !prefs.getBoolean(key + "_evaluated", false)
                    || !prefs.getBoolean(key + "_metrics_v3", false)) continue;
            boolean correct1x2 = prefs.getBoolean(key + "_1x2_correct", false);
            boolean correctGoal = prefs.getBoolean(key + "_goal_correct", false);
            boolean correctOver15 = prefs.getBoolean(key + "_over15_correct", false);
            boolean correctOver = prefs.getBoolean(key + "_over_correct", false);

            // Brier score 1X2 e fascia di confidenza: calcolati al volo dai dati già
            // salvati (probabilità congelate + risultato finale), senza bisogno di
            // un nuovo formato di salvataggio: funzionano anche sui pronostici già
            // valutati prima di questa modifica.
            int p1 = prefs.getInt(key + "_p1", 0);
            int px = prefs.getInt(key + "_px", 0);
            int p2 = prefs.getInt(key + "_p2", 0);
            int confidence = prefs.getInt(key + "_confidence", 0);
            int finalHome = prefs.getInt(key + "_final_home", -1);
            int finalAway = prefs.getInt(key + "_final_away", -1);
            double brier1x2 = finalHome >= 0 && finalAway >= 0
                    ? brierScore1x2(p1, px, p2, PredictionEvaluation.actual1x2(finalHome, finalAway))
                    : 0.0;

            totalStats.add(correct1x2, correctGoal, correctOver15, correctOver, brier1x2);

            String league = prefs.getString(key + "_league", "Campionato non disponibile");
            EvaluationStats leagueStats = byLeague.get(league);
            if (leagueStats == null) {
                leagueStats = new EvaluationStats();
                byLeague.put(league, leagueStats);
            }
            leagueStats.add(correct1x2, correctGoal, correctOver15, correctOver, brier1x2);

            int bucket = confidenceBucketStart(confidence);
            ConfidenceBucketStats bucketStats = byConfidenceBucket.get(bucket);
            if (bucketStats == null) {
                bucketStats = new ConfidenceBucketStats();
                byConfidenceBucket.put(bucket, bucketStats);
            }
            bucketStats.add(correct1x2);
        }

        String msg;
        if (totalStats.total == 0) {
            msg = "Non ci sono ancora pronostici conclusi da verificare.\n\n"
                    + "I pronostici vengono salvati quando visualizzi una partita non ancora iniziata e verificati aprendo lo Storico.";
        } else {
            StringBuilder text = new StringBuilder();
            text.append("TOTALE • ").append(totalStats.total).append(" partite\n")
                    .append(formatStats(totalStats));
            for (Map.Entry<String, EvaluationStats> entry : byLeague.entrySet()) {
                text.append("\n\n").append(entry.getKey()).append(" • ")
                        .append(entry.getValue().total).append(" partite\n")
                        .append(formatStats(entry.getValue()));
            }

            text.append("\n\nCALIBRAZIONE 1X2 PER FASCIA DI CONFIDENZA\n")
                    .append("Se il modello è ben calibrato, in ogni fascia la % di esiti "
                            + "corretti dovrebbe avvicinarsi al valore della fascia stessa.\n");
            for (Map.Entry<Integer, ConfidenceBucketStats> entry : byConfidenceBucket.entrySet()) {
                ConfidenceBucketStats b = entry.getValue();
                text.append(confidenceBucketLabel(entry.getKey())).append(": ")
                        .append(b.correct).append("/").append(b.total)
                        .append(" corretti (").append(percentage(b.correct, b.total)).append("%)\n");
            }

            msg = text.toString();
        }

        new AlertDialog.Builder(this)
                .setTitle("Statistiche pronostici")
                .setMessage(msg)
                .setPositiveButton("Chiudi", null)
                .setNegativeButton("Azzera statistiche", (dialog, which) -> confirmResetPredictionStats())
                .show();
    }

    /**
     * Chiave numerica della fascia di confidenza a step di 10 punti (50-59%,
     * 60-69%, ...), usata come chiave di ordinamento nella tabella di
     * calibrazione. -1 rappresenta la fascia "sotto il 50%": il pronostico
     * secco scatta solo sopra SINGLE_PICK_CONFIDENCE_THRESHOLD (58%), ma
     * m.confidence riflette comunque il massimo tra p1/px/p2 anche sotto
     * quella soglia, e in una partita a 3 esiti può scendere fin verso il 34%.
     */
    private int confidenceBucketStart(int confidence) {
        return confidence < 50 ? -1 : (confidence / 10) * 10;
    }

    /** Etichetta leggibile per la fascia identificata da {@link #confidenceBucketStart}. */
    private String confidenceBucketLabel(int bucketStart) {
        if (bucketStart < 0) return "sotto il 50%";
        int bucketEnd = Math.min(bucketStart + 9, 100);
        return bucketStart + "-" + bucketEnd + "%";
    }

    /**
     * Brier score per un pronostico 1X2: somma degli scarti al quadrato tra le
     * probabilità stimate (0-1) e il vettore "one-hot" dell'esito reale (1 sulla
     * classe realmente accaduta, 0 sulle altre due). Va da 0 (probabilità 100%
     * tutta sull'esito giusto) a 2 (probabilità 100% tutta su un esito sbagliato).
     * Più basso è, meglio è calibrato il modello — a differenza della semplice %
     * di pronostici azzeccati, penalizza anche l'eccessiva sicurezza quando poi
     * il modello ha torto.
     */
    private double brierScore1x2(int p1, int px, int p2, String actual) {
        double f1 = p1 / 100.0, fx = px / 100.0, f2 = p2 / 100.0;
        double o1 = "1".equals(actual) ? 1.0 : 0.0;
        double ox = "X".equals(actual) ? 1.0 : 0.0;
        double o2 = "2".equals(actual) ? 1.0 : 0.0;
        return (f1 - o1) * (f1 - o1) + (fx - ox) * (fx - ox) + (f2 - o2) * (f2 - o2);
    }

    private boolean isPredictionBaseKey(String key) {
        return key != null && key.matches("saved_prediction_[0-9]+");
    }

    private String formatStats(EvaluationStats stats) {
        return "1X2: " + stats.correct1x2 + "/" + stats.total
                + " (" + percentage(stats.correct1x2, stats.total) + "%)\n"
                + "Goal/No Goal: " + stats.correctGoal + "/" + stats.total
                + " (" + percentage(stats.correctGoal, stats.total) + "%)\n"
                + "Over/Under 1,5: " + stats.correctOver15 + "/" + stats.total
                + " (" + percentage(stats.correctOver15, stats.total) + "%)\n"
                + "Over/Under 2,5: " + stats.correctOver + "/" + stats.total
                + " (" + percentage(stats.correctOver, stats.total) + "%)\n"
                + "Brier score 1X2: " + String.format(Locale.ITALY, "%.3f", stats.avgBrier1x2())
                + " (0=perfetto, 2=il peggio possibile)";
    }

    private int percentage(int correct, int total) {
        return total <= 0 ? 0 : Math.round(correct * 100f / total);
    }

    private void confirmResetPredictionStats() {
        new AlertDialog.Builder(this)
                .setTitle("Azzerare le statistiche?")
                .setMessage("Cancella il conteggio corretti/sbagliati e tutti i pronostici "
                        + "già congelati per la verifica. Utile dopo un aggiornamento del "
                        + "modello, per non mischiare pronostici vecchi e nuovi nella stessa "
                        + "percentuale. Questa azione non si può annullare.")
                .setPositiveButton("Azzera", (dialog, which) -> resetPredictionStats())
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void resetPredictionStats() {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("saved_prediction_")) editor.remove(key);
        }
        editor.apply();
        showMessage("Statistiche azzerate. Le nuove percentuali rifletteranno solo i pronostici da qui in avanti.");
    }

    private boolean isFavorite(int fixtureId) {
        return prefs.getBoolean("fav_" + fixtureId, false);
    }

    private void toggleFavorite(int fixtureId) {
        boolean newValue = !isFavorite(fixtureId);
        prefs.edit().putBoolean("fav_" + fixtureId, newValue).apply();
    }

    private void updateTopLabel() {
        String league = selectedLeagueId == null ? "Tutti" : selectedLeagueName;
        boolean filtersActive = strongOnly || favoritesOnly || topFiveOnly
                || !"ALL".equals(filterMode);
        tvAccuracy.setText(league + " • " + shortDate(selectedDate)
                + (filtersActive ? " • FILTRI" : ""));
        tvAccuracy.setTextColor(getColor(filtersActive ? R.color.warn : R.color.text_secondary));
    }

    private void updateDayButtons() {
        int selectedOffset = dayOffsetForDate(selectedDate);
        btnToday.setText("Oggi");
        btnTomorrow.setText("Domani");
        btnDayAfterTomorrow.setText(dayButtonLabel(2));
        btnFourthDay.setText(dayButtonLabel(3));
        styleDayButton(btnToday, selectedOffset == 0);
        styleDayButton(btnTomorrow, selectedOffset == 1);
        styleDayButton(btnDayAfterTomorrow, selectedOffset == 2);
        styleDayButton(btnFourthDay, selectedOffset == 3);
    }

    private void selectDay(int offset) {
        selectedDate = dateOffset(offset);
        favoritesOnly = false;
        updateDayButtons();
        loadDay(selectedDate, true);
    }

    private int dayOffsetForDate(String date) {
        for (int i = 0; i < 4; i++) if (dateOffset(i).equals(date)) return i;
        return -1;
    }

    private String dayButtonLabel(int offset) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"));
        calendar.add(Calendar.DAY_OF_YEAR, offset);
        SimpleDateFormat format = new SimpleDateFormat("EEE dd", Locale.ITALY);
        format.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
        String label = format.format(calendar.getTime()).replace(".", "");
        return label.substring(0, 1).toUpperCase(Locale.ITALY) + label.substring(1);
    }

    private void styleDayButton(MaterialButton button, boolean selected) {
        if (button == null) return;
        button.setBackgroundTintList(ColorStateList.valueOf(
                getColor(selected ? R.color.primary : R.color.surface)));
        button.setTextColor(getColor(selected ? R.color.bg : R.color.text_primary));
        button.setStrokeColor(ColorStateList.valueOf(
                getColor(selected ? R.color.primary : R.color.surface_2)));
        button.setStrokeWidth(dp(1));
    }

    private void setDayButtonsEnabled(boolean enabled) {
        btnToday.setEnabled(enabled);
        btnTomorrow.setEnabled(enabled);
        btnDayAfterTomorrow.setEnabled(enabled);
        btnFourthDay.setEnabled(enabled);
        btnToday.setAlpha(enabled ? 1f : 0.65f);
        btnTomorrow.setAlpha(enabled ? 1f : 0.65f);
        btnDayAfterTomorrow.setAlpha(enabled ? 1f : 0.65f);
        btnFourthDay.setAlpha(enabled ? 1f : 0.65f);
    }

    private double avg(JSONObject last5, String side) throws Exception {
        Object value = last5.getJSONObject("goals")
                .getJSONObject(side).opt("average");

        if (value == null || JSONObject.NULL.equals(value)) return 0;

        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(62), 1);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private View statBox(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(5), dp(8), dp(5));
        box.setBackgroundResource(R.drawable.bg_chip);

        box.addView(text(label, 11, R.color.text_secondary, true));
        box.addView(text(value, 17, R.color.text_primary, true));
        return box;
    }

    private String httpGet(String url, String headerName, String headerValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty(headerName, headerValue);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder body = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + body);
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    /** Elimina i vecchi JSON di partite/storico per evitare crescita illimitata. */
    private void purgeExpiredCache() {
        final long retentionMs = 90L * 24L * 60L * 60L * 1000L;
        final long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = cache.edit();
        boolean changed = false;

        for (Map.Entry<String, ?> entry : cache.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith("_ts") || !(entry.getValue() instanceof Long)) continue;
            long timestamp = (Long) entry.getValue();
            if (timestamp > 0 && now - timestamp > retentionMs) {
                String dataKey = key.substring(0, key.length() - 3);
                editor.remove(dataKey);
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    private String dateOffset(int days) {
        Calendar c = Calendar.getInstance(
                TimeZone.getTimeZone("Europe/Rome")
        );
        c.add(Calendar.DAY_OF_YEAR, days);

        SimpleDateFormat f =
                new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Rome"));
        return f.format(c.getTime());
    }

    private String italianDate(String date) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            SimpleDateFormat out = new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY);
            return out.format(in.parse(date));
        } catch (Exception e) {
            return date;
        }
    }

    private String shortDate(String date) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            SimpleDateFormat out = new SimpleDateFormat("dd/MM", Locale.ITALY);
            return out.format(in.parse(date));
        } catch (Exception e) {
            return date;
        }
    }

    private int seasonForDate(String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(5, 7));
            return month >= 7 ? year : year - 1;
        } catch (Exception e) {
            Calendar c = Calendar.getInstance();
            int year = c.get(Calendar.YEAR);
            int month = c.get(Calendar.MONTH) + 1;
            return month >= 7 ? year : year - 1;
        }
    }

    private boolean isFinished(String s) {
        return "FT".equals(s) || "AET".equals(s) || "PEN".equals(s);
    }

    private int percentValue(String s) {
        try {
            return Integer.parseInt(s.replace("%", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String valueOrDash(int v) {
        return v <= 0 ? "—" : v + "%";
    }

    private void showLoading(String msg) {
        matchesContainer.removeAllViews();
        TextView tv = text(msg, 16, R.color.text_secondary, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(36), dp(12), dp(36));
        matchesContainer.addView(tv);
    }

    private void showMessage(String msg) {
        matchesContainer.removeAllViews();
        TextView tv = text(msg, 16, R.color.text_primary, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(36), dp(12), dp(36));
        matchesContainer.addView(tv);
    }

    private String cleanError(Exception e) {
        String s = e.getMessage();
        if (s == null) return "errore sconosciuto";
        if (s.toLowerCase(Locale.ROOT).contains("account is suspended")) {
            return "account API-Football sospeso. Controlla il pannello API-Football.";
        }
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(colorRes));

        if (bold) {
            tv.setTypeface(
                    tv.getTypeface(),
                    android.graphics.Typeface.BOLD
            );
        }
        return tv;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    protected void onStop() {
        prefs.edit()
                .putString("selected_date", selectedDate)
                .putInt("selected_league_id", selectedLeagueId == null ? -1 : selectedLeagueId)
                .putString("selected_league_name", selectedLeagueName)
                .putBoolean("filter_strong", strongOnly)
                .putString("filter_mode", filterMode)
                .putBoolean("sort_confidence", sortByConfidence)
                .putBoolean("top_five", topFiveOnly)
                .apply();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dayLoadGeneration.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (historyDatabase != null) historyDatabase.close();
        super.onDestroy();
    }
}
