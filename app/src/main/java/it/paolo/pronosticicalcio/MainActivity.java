package it.paolo.pronosticicalcio;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
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
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PronosticiCalcio";
    private static final String BASE_URL = "https://v3.football.api-sports.io";
    private static final String FOOTBALL_DATA_URL = "https://api.football-data.org/v4";
    private static final long CACHE_MS = 6L * 60L * 60L * 1000L;
    private static final int STRONG_THRESHOLD = 70;
    // Quanti giorni di storico si tenta di caricare: unica sorgente di verità
    // in PredictionEngine, dato che è un parametro del modello di pronostico.
    private static final int MODEL_HISTORY_DAYS = PredictionEngine.MODEL_HISTORY_DAYS;

    private static final int[] LEAGUE_IDS = {
            135, 39, 140, 78, 61, 88, 94, 2, 3, 848
    };

    private static final String[] LEAGUE_NAMES = {
            "Serie A", "Premier League", "La Liga", "Bundesliga", "Ligue 1",
            "Eredivisie", "Primeira Liga", "Champions League",
            "Europa League", "Conference League"
    };

    private static final Set<Integer> LEAGUES = new HashSet<>(Arrays.asList(
            135, 39, 140, 78, 61, 88, 94, 2, 3, 848
    ));

    private LinearLayout matchesContainer;
    private TextView tvAccuracy;
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

        void add(boolean oneXTwo, boolean goal, boolean over15, boolean over) {
            total++;
            if (oneXTwo) correct1x2++;
            if (goal) correctGoal++;
            if (over15) correctOver15++;
            if (over) correctOver++;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        matchesContainer = findViewById(R.id.matchesContainer);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        btnStrong = findViewById(R.id.btnStrong);
        cache = getSharedPreferences("api_cache", MODE_PRIVATE);
        prefs = getSharedPreferences("pronostici_prefs", MODE_PRIVATE);
        historyDatabase = new MatchHistoryDatabase(this);
        selectedDate = prefs.getString("selected_date", dateOffset(0));
        if (!selectedDate.equals(dateOffset(0)) && !selectedDate.equals(dateOffset(1))) {
            selectedDate = dateOffset(0);
        }
        int savedLeagueId = prefs.getInt("selected_league_id", -1);
        selectedLeagueId = savedLeagueId < 0 ? null : savedLeagueId;
        selectedLeagueName = prefs.getString("selected_league_name", "Tutti i campionati");
        strongOnly = prefs.getBoolean("filter_strong", false);
        filterMode = prefs.getString("filter_mode", "ALL");
        sortByConfidence = prefs.getBoolean("sort_confidence", false);
        topFiveOnly = prefs.getBoolean("top_five", false);
        btnStrong.setText(strongOnly ? "Confidenza ≥70% ✓" : "Confidenza ≥70%");
        purgeExpiredCache();

        findViewById(R.id.btnToday).setOnClickListener(v -> {
            selectedDate = dateOffset(0);
            favoritesOnly = false;
            loadDay(selectedDate, true);
        });

        findViewById(R.id.btnTomorrow).setOnClickListener(v -> {
            selectedDate = dateOffset(1);
            favoritesOnly = false;
            loadDay(selectedDate, true);
        });

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

        if (BuildConfig.API_FOOTBALL_KEY == null || BuildConfig.API_FOOTBALL_KEY.trim().isEmpty()) {
            showMessage("API_FOOTBALL_KEY non configurata nella build GitHub.");
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
        panel.addView(content);

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
        final int requestGeneration = dayLoadGeneration.incrementAndGet();
        final Integer requestedLeagueId = selectedLeagueId;
        final String requestedLeagueName = selectedLeagueName;
        showLoading("Carico partite reali del " + italianDate(date) + "…");
        updateTopLabel();

        executor.execute(() -> {
            try {
                String body = cachedGet(
                        "fixtures_" + date,
                        BASE_URL + "/fixtures?date=" + date + "&timezone=Europe%2FRome",
                        CACHE_MS
                );

                JSONObject root = new JSONObject(body);
                checkApiErrors(root);
                JSONArray arr = root.getJSONArray("response");
                List<MatchPrediction> list = new ArrayList<>();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    int leagueId = item.getJSONObject("league").getInt("id");
                    if (!LEAGUES.contains(leagueId)) continue;
                    if (requestedLeagueId != null && leagueId != requestedLeagueId) continue;
                    list.add(fixtureToMatch(item));
                }

                Collections.sort(list, (a, b) -> {
                    int leagueCompare = Integer.compare(leagueOrder(a.leagueId), leagueOrder(b.leagueId));
                    if (leagueCompare != 0) return leagueCompare;
                    return a.time.compareTo(b.time);
                });
                for (MatchPrediction m : list) {
                    historyDatabase.upsert("af:" + m.fixtureId, m, date);
                }
                if (requestGeneration != dayLoadGeneration.get()) return;

                mainHandler.post(() -> {
                    if (requestGeneration != dayLoadGeneration.get()) return;
                    currentMatches = list;
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
                    Map<String, Double> eloRatings = historyDatabase.calculateEloRatings(date);
                    int archiveDays = cache.getInt("history_archive_days", 0);
                    for (MatchPrediction m : list) {
                        if (m.finished) continue;
                        HeadToHeadStats headToHead = historyDatabase.headToHead(m.home, m.away, date);
                        String homeKey = TeamNameUtil.normalize(m.home);
                        String awayKey = TeamNameUtil.normalize(m.away);
                        PredictionEngine.calculate(m, history, previousSeasonPriors, archiveDays,
                                headToHead, eloRatings.get(homeKey), eloRatings.get(awayKey));
                        historyDatabase.upsert("af:" + m.fixtureId, m, date);
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
                        showMessage("Errore dati: " + cleanError(e));
                    }
                });
            }
        });
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

                    for (int i = 0; i < matches.length(); i++) {
                        JSONObject item = matches.getJSONObject(i);

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

                        hs.add(true, gh, ga, hp, awayName);
                        as.add(false, ga, gh, ap, homeName);
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
            if (favoritesOnly) {
                showMessage("Nessuna partita preferita in questa schermata.");
            } else if (strongOnly) {
                showMessage("Nessun pronostico con confidenza del modello almeno 70%.");
            } else {
                showMessage("Nessun pronostico corrisponde ai filtri scelti.");
            }
            return;
        }

        renderMatches(filtered);
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
        matchesContainer.removeAllViews();

        Map<String, List<MatchPrediction>> byLeague = new LinkedHashMap<>();
        for (MatchPrediction match : matches) {
            String key = match.leagueId + "|" + match.league;
            List<MatchPrediction> leagueMatches = byLeague.get(key);
            if (leagueMatches == null) {
                leagueMatches = new ArrayList<>();
                byLeague.put(key, leagueMatches);
            }
            leagueMatches.add(match);
        }

        for (List<MatchPrediction> leagueMatches : byLeague.values()) {
            if (leagueMatches.isEmpty()) continue;
            MatchPrediction first = leagueMatches.get(0);
            String leagueKey = first.leagueId + "|" + first.league;
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setVisibility(expandedLeagueKeys.contains(leagueKey)
                    ? View.VISIBLE : View.GONE);
            for (MatchPrediction match : leagueMatches) {
                content.addView(createMatchCard(match));
            }
            matchesContainer.addView(createLeagueHeader(
                    leagueKey, first.league, leagueMatches.size(), content));
            matchesContainer.addView(content);
        }
    }

    private View createLeagueHeader(String leagueKey, String leagueName, int matchCount,
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

        String countText = matchCount == 1 ? "1 partita" : matchCount + " partite";
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
            case 135: return "SA";
            case 39: return "PL";
            case 140: return "PD";
            case 78: return "BL1";
            case 61: return "FL1";
            case 88: return "DED";
            case 94: return "PPL";
            case 2: return "CL";
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
        if (today.equals(prefs.getString("history_seed_attempt_date", ""))) return;
        prefs.edit().putString("history_seed_attempt_date", today).apply();

        executor.execute(() -> {
            int index = prefs.getInt("history_seed_index", 0);
            int total = MODEL_HISTORY_FD_CODES.length * 5;
            if (index >= total) return;

            String code = MODEL_HISTORY_FD_CODES[index % MODEL_HISTORY_FD_CODES.length];
            int season = seasonForDate(today) - 1 - (index / MODEL_HISTORY_FD_CODES.length);
            try {
                throttleFootballDataRequest();
                String body = directGetFootballData(FOOTBALL_DATA_URL + "/competitions/"
                        + code + "/matches?season=" + season + "&status=FINISHED");
                JSONObject root = new JSONObject(body);
                JSONArray matches = root.optJSONArray("matches");
                if (matches != null) {
                    for (int i = 0; i < matches.length(); i++) {
                        storeFootballDataMatch(matches.getJSONObject(i));
                    }
                }
                Log.i(TAG, "Archivio storico importato: " + code + " " + season
                        + " (" + (matches == null ? 0 : matches.length()) + " partite)");
            } catch (Exception e) {
                // Anche una stagione non disponibile viene saltata: altrimenti
                // il popolamento resterebbe bloccato per sempre sullo stesso punto.
                Log.w(TAG, "Importazione storica non disponibile: " + code + " " + season, e);
            } finally {
                prefs.edit().putInt("history_seed_index", index + 1).apply();
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
            int failedDays = 0;

            for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
                String date = dateOffset(-daysAgo);

                try {
                    String body = cachedGet(
                            "history_day_" + date,
                            BASE_URL + "/fixtures?date=" + date + "&timezone=Europe%2FRome",
                            CACHE_MS
                    );

                    JSONObject root = new JSONObject(body);
                    checkApiErrors(root);
                    JSONArray arr = root.getJSONArray("response");

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        int leagueId = item.getJSONObject("league").getInt("id");

                        if (!LEAGUES.contains(leagueId)) continue;
                        if (selectedLeagueId != null && leagueId != selectedLeagueId) continue;

                        String status = item.getJSONObject("fixture")
                                .getJSONObject("status").optString("short", "");

                        if (!isFinished(status)) continue;

                        MatchPrediction m = fixtureToMatch(item);
                        JSONObject goals = item.getJSONObject("goals");

                        int gh = goals.optInt("home", -1);
                        int ga = goals.optInt("away", -1);

                        m.finalHomeGoals = gh;
                        m.finalAwayGoals = ga;
                        m.finished = true;
                        historyDatabase.upsert("af:" + m.fixtureId, m, date);

                        m.time = italianDate(date);
                        m.score = gh + " - " + ga;

                        evaluateSavedPrediction(m.fixtureId, gh, ga);
                        String verifica = savedPredictionResult(m.fixtureId, gh, ga);
                        m.pick = verifica.isEmpty()
                                ? "Risultato finale " + m.score
                                : verifica;
                        m.analysis = verifica.isEmpty()
                                ? "Risultato storico reale del " + italianDate(date)
                                : savedPredictionDetails(m.fixtureId, gh, ga);

                        list.add(m);
                    }

                } catch (Exception e) {
                    failedDays++;
                }
            }

            Collections.reverse(list);

            if (list.size() > 80) {
                list = new ArrayList<>(list.subList(0, 80));
            }

            final List<MatchPrediction> result = list;
            final int failures = failedDays;

            mainHandler.post(() -> {
                currentMatches = result;

                if (result.isEmpty()) {
                    if (failures > 0) {
                        showMessage("Storico non disponibile. Alcuni giorni non sono stati restituiti dall'API.");
                    } else {
                        showMessage("Nessun risultato disponibile negli ultimi 7 giorni.");
                    }
                } else {
                    // Lo storico deve mostrare tutte le verifiche, senza essere
                    // nascosto dai filtri eventualmente attivi nella giornata.
                    renderMatches(result);

                    if (failures > 0) {
                        Toast.makeText(
                                this,
                                "Storico caricato. " + failures + " giorno/i non disponibili.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
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

        for (String key : prefs.getAll().keySet()) {
            if (!isPredictionBaseKey(key)
                    || !prefs.getBoolean(key + "_evaluated", false)
                    || !prefs.getBoolean(key + "_metrics_v3", false)) continue;
            boolean correct1x2 = prefs.getBoolean(key + "_1x2_correct", false);
            boolean correctGoal = prefs.getBoolean(key + "_goal_correct", false);
            boolean correctOver15 = prefs.getBoolean(key + "_over15_correct", false);
            boolean correctOver = prefs.getBoolean(key + "_over_correct", false);
            totalStats.add(correct1x2, correctGoal, correctOver15, correctOver);

            String league = prefs.getString(key + "_league", "Campionato non disponibile");
            EvaluationStats leagueStats = byLeague.get(league);
            if (leagueStats == null) {
                leagueStats = new EvaluationStats();
                byLeague.put(league, leagueStats);
            }
            leagueStats.add(correct1x2, correctGoal, correctOver15, correctOver);
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
            msg = text.toString();
        }

        new AlertDialog.Builder(this)
                .setTitle("Statistiche pronostici")
                .setMessage(msg)
                .setPositiveButton("Chiudi", null)
                .setNegativeButton("Azzera statistiche", (dialog, which) -> confirmResetPredictionStats())
                .show();
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
                + " (" + percentage(stats.correctOver, stats.total) + "%)";
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

    private MatchPrediction fixtureToMatch(JSONObject item) throws Exception {
        MatchPrediction m = new MatchPrediction();

        JSONObject fixture = item.getJSONObject("fixture");
        JSONObject league = item.getJSONObject("league");
        JSONObject teams = item.getJSONObject("teams");

        m.fixtureId = fixture.getInt("id");
        m.leagueId = league.getInt("id");
        m.league = league.optString("name", "Campionato");
        m.time = formatTime(fixture.optString("date", ""));

        JSONObject home = teams.getJSONObject("home");
        JSONObject away = teams.getJSONObject("away");
        m.homeId = home.optInt("id");
        m.awayId = away.optInt("id");
        m.home = home.optString("name", "Casa");
        m.away = away.optString("name", "Trasferta");

        m.finished = isFinished(
                fixture.getJSONObject("status").optString("short", "")
        );

        if (m.finished) {
            JSONObject goals = item.optJSONObject("goals");
            if (goals != null) {
                m.finalHomeGoals = goals.optInt("home", -1);
                m.finalAwayGoals = goals.optInt("away", -1);
            }

            if (m.finalHomeGoals >= 0 && m.finalAwayGoals >= 0) {
                m.score = m.finalHomeGoals + " - " + m.finalAwayGoals;
                evaluateSavedPrediction(
                        m.fixtureId,
                        m.finalHomeGoals,
                        m.finalAwayGoals
                );
                String verifica = savedPredictionResult(
                        m.fixtureId,
                        m.finalHomeGoals,
                        m.finalAwayGoals
                );
                m.pick = verifica.isEmpty() ? "Risultato finale " + m.score : verifica;
                m.analysis = verifica.isEmpty()
                        ? "Partita terminata. Risultato finale reale."
                        : savedPredictionDetails(m.fixtureId, m.finalHomeGoals, m.finalAwayGoals);
            } else {
                m.pick = "Partita terminata";
                m.analysis = "Partita terminata. Risultato non ancora disponibile.";
            }
        } else {
            m.pick = "Calcolo modello statistico…";
            m.analysis = "Calcolo modello statistico…";
        }

        return m;
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
        tvAccuracy.setText(league + " • " + shortDate(selectedDate));
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

    private String cachedGet(String key, String url, long maxAge) throws Exception {
        long ts = cache.getLong(key + "_ts", 0);
        String saved = cache.getString(key, null);

        if (saved != null && System.currentTimeMillis() - ts < maxAge) {
            return saved;
        }

        try {
            String body = httpGet(url, "x-apisports-key", BuildConfig.API_FOOTBALL_KEY);
            cache.edit()
                    .putString(key, body)
                    .putLong(key + "_ts", System.currentTimeMillis())
                    .apply();
            return body;
        } catch (Exception networkError) {
            if (saved != null) return saved;
            throw networkError;
        }
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

    private void checkApiErrors(JSONObject root) throws Exception {
        Object errors = root.opt("errors");

        if (errors instanceof JSONArray
                && ((JSONArray) errors).length() > 0) {
            throw new Exception(errors.toString());
        }

        if (errors instanceof JSONObject
                && ((JSONObject) errors).length() > 0) {
            throw new Exception(errors.toString());
        }
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

    private String formatTime(String iso) {
        try {
            if (iso.length() >= 16) return iso.substring(11, 16);
        } catch (Exception e) {
            Log.w(TAG, "formatTime: formato orario inatteso: " + iso, e);
        }
        return "--:--";
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
