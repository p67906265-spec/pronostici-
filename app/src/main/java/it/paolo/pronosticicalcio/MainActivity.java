package it.paolo.pronosticicalcio;

import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://v3.football.api-sports.io";
    private static final long CACHE_MS = 6L * 60L * 60L * 1000L;
    private static final int STRONG_THRESHOLD = 70;

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

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Integer selectedLeagueId = null;
    private String selectedLeagueName = "Tutti i campionati";
    private String selectedDate;
    private boolean strongOnly = false;
    private boolean favoritesOnly = false;
    private List<MatchPrediction> currentMatches = new ArrayList<>();

    static class MatchPrediction {
        int fixtureId;
        int leagueId;
        int homeId;
        int awayId;
        int p1;
        int px;
        int p2;
        int goal;
        int over25;
        int confidence;
        String league;
        String time;
        String home;
        String away;
        String pick;
        String analysis;
        String score;
        String predicted1x2;
        boolean finished;
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
        selectedDate = dateOffset(0);

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

        findViewById(R.id.btnCalendar).setOnClickListener(v -> showCalendar());
        findViewById(R.id.btnLeagues).setOnClickListener(v -> showLeagueSelector());
        findViewById(R.id.btnStandings).setOnClickListener(v -> loadStandings());
        findViewById(R.id.btnFavorites).setOnClickListener(v -> {
            favoritesOnly = !favoritesOnly;
            renderFiltered();
            Toast.makeText(this,
                    favoritesOnly ? "Mostro solo i preferiti" : "Mostro tutte le partite",
                    Toast.LENGTH_SHORT).show();
        });

        btnStrong.setOnClickListener(v -> {
            strongOnly = !strongOnly;
            btnStrong.setText(strongOnly ? "Forti ≥70% ✓" : "Forti ≥70%");
            renderFiltered();
        });

        findViewById(R.id.btnHistory).setOnClickListener(v -> loadHistory());
        findViewById(R.id.btnStats).setOnClickListener(v -> showPredictionStats());

        if (BuildConfig.API_FOOTBALL_KEY == null || BuildConfig.API_FOOTBALL_KEY.trim().isEmpty()) {
            showMessage("API_FOOTBALL_KEY non configurata nella build GitHub.");
            tvAccuracy.setText("API mancante");
        } else {
            loadDay(selectedDate, true);
        }
    }

    private void showCalendar() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"));
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY);
            c.setTime(f.parse(selectedDate));
        } catch (Exception ignored) {}

        DatePickerDialog d = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    selectedDate = String.format(Locale.ITALY, "%04d-%02d-%02d",
                            year, month + 1, day);
                    favoritesOnly = false;
                    loadDay(selectedDate, true);
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
        );
        d.setTitle("Scegli la data");
        d.show();
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
                    if (selectedLeagueId != null && leagueId != selectedLeagueId) continue;
                    list.add(fixtureToMatch(item));
                }

                Collections.sort(list, (a, b) -> a.time.compareTo(b.time));
                currentMatches = list;

                mainHandler.post(() -> {
                    if (currentMatches.isEmpty()) {
                        showMessage(selectedLeagueId == null
                                ? "Nessuna partita dei principali campionati europei in questa data."
                                : "Nessuna partita di " + selectedLeagueName + " in questa data.");
                    } else {
                        renderFiltered();
                    }
                });

                if (predictions) {
                    for (MatchPrediction m : list) {
                        if (m.finished) continue;
                        try {
                            loadPrediction(m);
                            savePredictionSnapshot(m);
                            mainHandler.post(this::renderFiltered);
                            Thread.sleep(220);
                        } catch (Exception ignored) {}
                    }
                }

            } catch (Exception e) {
                mainHandler.post(() -> showMessage("Errore dati: " + cleanError(e)));
            }
        });
    }

    private void loadPrediction(MatchPrediction m) throws Exception {
        String body = cachedGet(
                "prediction_" + m.fixtureId,
                BASE_URL + "/predictions?fixture=" + m.fixtureId,
                12L * 60L * 60L * 1000L
        );

        JSONObject root = new JSONObject(body);
        checkApiErrors(root);
        JSONArray arr = root.getJSONArray("response");

        if (arr.length() == 0) {
            m.analysis = "Pronostico non ancora disponibile.";
            return;
        }

        JSONObject data = arr.getJSONObject(0);
        JSONObject pred = data.getJSONObject("predictions");
        JSONObject percent = pred.getJSONObject("percent");

        m.p1 = percentValue(percent.optString("home", "0%"));
        m.px = percentValue(percent.optString("draw", "0%"));
        m.p2 = percentValue(percent.optString("away", "0%"));
        m.confidence = Math.max(m.p1, Math.max(m.px, m.p2));

        if (m.p1 >= m.px && m.p1 >= m.p2) m.predicted1x2 = "1";
        else if (m.px >= m.p1 && m.px >= m.p2) m.predicted1x2 = "X";
        else m.predicted1x2 = "2";

        String advice = pred.optString("advice", "");
        JSONObject winner = pred.optJSONObject("winner");

        if (!advice.isEmpty()) {
            m.pick = translateAdvice(advice);
        } else if (winner != null) {
            m.pick = "Vincente: " + winner.optString("name", "n/d");
        } else {
            m.pick = "Pronostico disponibile";
        }

        try {
            JSONObject teams = data.getJSONObject("teams");
            JSONObject h5 = teams.getJSONObject("home").getJSONObject("last_5");
            JSONObject a5 = teams.getJSONObject("away").getJSONObject("last_5");

            double hf = avg(h5, "for");
            double ha = avg(h5, "against");
            double af = avg(a5, "for");
            double aa = avg(a5, "against");

            double eh = (hf + aa) / 2.0;
            double ea = (af + ha) / 2.0;

            m.over25 = clamp((int) Math.round(35 + (eh + ea) * 12), 20, 88);
            m.goal = clamp((int) Math.round(30 + Math.min(eh, ea) * 30), 18, 85);

            String underOver = translateUnderOver(pred.optString("under_over", ""));
            m.analysis = "Forma ultime 5 • gol attesi circa "
                    + String.format(Locale.ITALY, "%.1f", eh) + "-"
                    + String.format(Locale.ITALY, "%.1f", ea)
                    + (underOver.isEmpty() ? "" : " • " + underOver);

        } catch (Exception e) {
            m.goal = 50;
            m.over25 = 50;
            m.analysis = "Pronostico basato su forma recente, scontri diretti e storico.";
        }
    }

    private String translateAdvice(String s) {
        String r = s.trim();

        r = r.replace("Double chance :", "Doppia chance:")
             .replace("double chance :", "Doppia chance:")
             .replace("Winner :", "Vincente:")
             .replace("winner :", "Vincente:")
             .replace(" or draw", " o pareggio")
             .replace("draw or ", "pareggio o ")
             .replace("Draw or ", "Pareggio o ")
             .replace(" or ", " o ")
             .replace("Home", "Casa")
             .replace("Away", "Trasferta")
             .replace("Draw", "Pareggio")
             .replace("draw", "pareggio");

        if (r.startsWith("Doppia chance:")) return r;
        return r;
    }

    private String translateUnderOver(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        return "Indicazione gol: " + s
                .replace("Over", "Più di")
                .replace("Under", "Meno di");
    }

    private void renderFiltered() {
        if (currentMatches == null || currentMatches.isEmpty()) {
            showMessage("Nessuna partita da mostrare.");
            return;
        }

        List<MatchPrediction> filtered = new ArrayList<>();
        for (MatchPrediction m : currentMatches) {
            if (strongOnly && (m.confidence <= 0 || m.confidence < STRONG_THRESHOLD)) continue;
            if (favoritesOnly && !isFavorite(m.fixtureId)) continue;
            filtered.add(m);
        }

        if (filtered.isEmpty()) {
            if (favoritesOnly) showMessage("Nessuna partita preferita in questa schermata.");
            else if (strongOnly) showMessage("Nessun pronostico con affidabilità almeno 70%.");
            else showMessage("Nessuna partita da mostrare.");
            return;
        }

        renderMatches(filtered);
    }

    private void renderMatches(List<MatchPrediction> matches) {
        matchesContainer.removeAllViews();
        for (MatchPrediction m : matches) {
            matchesContainer.addView(createMatchCard(m));
        }
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

        TextView league = text(m.league, 12, R.color.text_secondary, true);
        top.addView(league, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(text(m.time, 13, R.color.primary, true));

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

        TextView teams = text(m.home + "  -  " + m.away, 20, R.color.text_primary, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(8);
        tlp.bottomMargin = dp(12);
        root.addView(teams, tlp);

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
                m.finished ? "RISULTATO" : "PRONOSTICO CONSIGLIATO",
                11, R.color.text_secondary, true
        ));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView pick = text(m.pick, 18, R.color.primary, true);
        row.addView(pick, new LinearLayout.LayoutParams(0, -2, 1));

        if (!m.finished && m.confidence > 0) {
            row.addView(text("Affidabilità " + m.confidence + "%", 12,
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

        card.addView(root);
        return card;
    }

    private void showMatchDetails(MatchPrediction m) {
        String[] options = {
                "Analisi pronostico",
                "Scontri diretti"
        };

        new AlertDialog.Builder(this)
                .setTitle(m.home + " - " + m.away)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("Analisi")
                                .setMessage(m.analysis)
                                .setPositiveButton("Chiudi", null)
                                .show();
                    } else {
                        loadHeadToHead(m);
                    }
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void loadHeadToHead(MatchPrediction m) {
        showLoading("Carico gli scontri diretti…");
        executor.execute(() -> {
            try {
                String body = cachedGet(
                        "h2h_" + m.homeId + "_" + m.awayId,
                        BASE_URL + "/fixtures/headtohead?h2h=" + m.homeId + "-" + m.awayId
                                + "&last=5&timezone=Europe%2FRome",
                        CACHE_MS
                );

                JSONObject root = new JSONObject(body);
                checkApiErrors(root);
                JSONArray arr = root.getJSONArray("response");
                StringBuilder sb = new StringBuilder();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    JSONObject teams = item.getJSONObject("teams");
                    JSONObject goals = item.getJSONObject("goals");
                    sb.append("• ")
                            .append(teams.getJSONObject("home").optString("name"))
                            .append(" ")
                            .append(goals.optString("home", "-"))
                            .append("-")
                            .append(goals.optString("away", "-"))
                            .append(" ")
                            .append(teams.getJSONObject("away").optString("name"))
                            .append("\n");
                }

                String msg = sb.length() == 0 ? "Nessun dato disponibile" : sb.toString().trim();

                mainHandler.post(() -> {
                    renderFiltered();
                    new AlertDialog.Builder(this)
                            .setTitle("Ultimi scontri diretti")
                            .setMessage(msg)
                            .setPositiveButton("Chiudi", null)
                            .show();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    renderFiltered();
                    Toast.makeText(this, "Errore scontri diretti: " + cleanError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadStandings() {
        if (selectedLeagueId == null) {
            Toast.makeText(this, "Prima seleziona un campionato.", Toast.LENGTH_LONG).show();
            showLeagueSelector();
            return;
        }

        int season = seasonForDate(selectedDate);
        showLoading("Carico la classifica di " + selectedLeagueName + "…");

        executor.execute(() -> {
            try {
                String body = cachedGet(
                        "standings_" + selectedLeagueId + "_" + season,
                        BASE_URL + "/standings?league=" + selectedLeagueId + "&season=" + season,
                        CACHE_MS
                );

                JSONObject root = new JSONObject(body);
                checkApiErrors(root);
                JSONArray resp = root.getJSONArray("response");

                if (resp.length() == 0) {
                    mainHandler.post(() -> {
                        renderFiltered();
                        Toast.makeText(this, "Classifica non disponibile.", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                JSONArray groups = resp.getJSONObject(0)
                        .getJSONObject("league")
                        .getJSONArray("standings");

                JSONArray table = groups.getJSONArray(0);
                StringBuilder sb = new StringBuilder();

                for (int i = 0; i < table.length(); i++) {
                    JSONObject row = table.getJSONObject(i);
                    sb.append(row.optInt("rank")).append(". ")
                            .append(row.getJSONObject("team").optString("name"))
                            .append("  ")
                            .append(row.optInt("points")).append(" pt\n");
                }

                mainHandler.post(() -> {
                    renderFiltered();
                    new AlertDialog.Builder(this)
                            .setTitle("Classifica - " + selectedLeagueName)
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("Chiudi", null)
                            .show();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    renderFiltered();
                    Toast.makeText(this, "Errore classifica: " + cleanError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadHistory() {
        String to = dateOffset(-1);
        String from = dateOffset(-7);

        showLoading("Carico risultati reali ultimi 7 giorni…");
        tvAccuracy.setText("Storico reale");

        executor.execute(() -> {
            try {
                String body = cachedGet(
                        "history_" + from + "_" + to,
                        BASE_URL + "/fixtures?from=" + from + "&to=" + to + "&timezone=Europe%2FRome",
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
                    if (selectedLeagueId != null && leagueId != selectedLeagueId) continue;

                    String status = item.getJSONObject("fixture")
                            .getJSONObject("status").optString("short", "");
                    if (!isFinished(status)) continue;

                    MatchPrediction m = fixtureToMatch(item);
                    JSONObject goals = item.getJSONObject("goals");
                    int gh = goals.optInt("home", -1);
                    int ga = goals.optInt("away", -1);

                    m.score = gh + " - " + ga;
                    m.pick = "Risultato finale " + m.score;
                    m.analysis = "Risultato storico reale";
                    list.add(m);

                    evaluateSavedPrediction(m.fixtureId, gh, ga);
                }

                Collections.reverse(list);
                if (list.size() > 60) list = new ArrayList<>(list.subList(0, 60));
                currentMatches = list;

                mainHandler.post(() -> {
                    if (currentMatches.isEmpty()) {
                        showMessage("Nessun risultato disponibile negli ultimi 7 giorni.");
                    } else {
                        renderFiltered();
                    }
                });

            } catch (Exception e) {
                mainHandler.post(() -> showMessage("Errore storico: " + cleanError(e)));
            }
        });
    }

    private void savePredictionSnapshot(MatchPrediction m) {
        if (m.predicted1x2 == null || m.predicted1x2.isEmpty()) return;

        String key = "saved_prediction_" + m.fixtureId;
        if (!prefs.contains(key)) {
            prefs.edit()
                    .putString(key, m.predicted1x2)
                    .putInt(key + "_confidence", m.confidence)
                    .putBoolean(key + "_evaluated", false)
                    .apply();
        }
    }

    private void evaluateSavedPrediction(int fixtureId, int homeGoals, int awayGoals) {
        String key = "saved_prediction_" + fixtureId;
        if (!prefs.contains(key) || prefs.getBoolean(key + "_evaluated", false)) return;

        String actual;
        if (homeGoals > awayGoals) actual = "1";
        else if (homeGoals == awayGoals) actual = "X";
        else actual = "2";

        String predicted = prefs.getString(key, "");
        int total = prefs.getInt("stats_total", 0) + 1;
        int correct = prefs.getInt("stats_correct", 0);
        if (actual.equals(predicted)) correct++;

        prefs.edit()
                .putInt("stats_total", total)
                .putInt("stats_correct", correct)
                .putBoolean(key + "_evaluated", true)
                .apply();
    }

    private void showPredictionStats() {
        int total = prefs.getInt("stats_total", 0);
        int correct = prefs.getInt("stats_correct", 0);
        int wrong = Math.max(0, total - correct);
        int pct = total == 0 ? 0 : Math.round(correct * 100f / total);

        String msg;
        if (total == 0) {
            msg = "Non ci sono ancora pronostici conclusi da verificare.\n\n"
                    + "Le statistiche si aggiornano quando apri lo Storico dopo la fine delle partite.";
        } else {
            msg = "Pronostici verificati: " + total
                    + "\nCorretti: " + correct
                    + "\nSbagliati: " + wrong
                    + "\nPrecisione 1X2: " + pct + "%";
        }

        new AlertDialog.Builder(this)
                .setTitle("Statistiche pronostici")
                .setMessage(msg)
                .setPositiveButton("Chiudi", null)
                .show();
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

        m.pick = m.finished ? "Partita terminata" : "Analisi in caricamento…";
        m.analysis = m.finished
                ? "Risultato storico reale"
                : "Recupero forma, scontri diretti e storico…";

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

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("x-apisports-key", BuildConfig.API_FOOTBALL_KEY);
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300
                ? c.getInputStream()
                : c.getErrorStream();

        BufferedReader br = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + sb);
        }

        String body = sb.toString();
        cache.edit()
                .putString(key, body)
                .putLong(key + "_ts", System.currentTimeMillis())
                .apply();

        return body;
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
        } catch (Exception ignored) {}
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

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
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
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
