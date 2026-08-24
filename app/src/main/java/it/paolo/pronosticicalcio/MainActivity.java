package it.paolo.pronosticicalcio;

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
    private static final Set<Integer> LEAGUES = new HashSet<>(Arrays.asList(
            135, 39, 140, 78, 61, 88, 94, 2, 3, 848
    ));

    private LinearLayout matchesContainer;
    private TextView tvAccuracy;
    private SharedPreferences cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static class MatchPrediction {
        int fixtureId, p1, px, p2, goal, over25, confidence;
        String league, time, home, away, pick, analysis, score;
        boolean finished;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        matchesContainer = findViewById(R.id.matchesContainer);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        cache = getSharedPreferences("api_cache", MODE_PRIVATE);

        findViewById(R.id.btnToday).setOnClickListener(v -> loadDay(dateOffset(0), true));
        findViewById(R.id.btnTomorrow).setOnClickListener(v -> loadDay(dateOffset(1), true));
        findViewById(R.id.btnHistory).setOnClickListener(v -> loadHistory());
        findViewById(R.id.btnLeagues).setOnClickListener(v -> Toast.makeText(this,
                "Serie A • Premier League • La Liga • Bundesliga • Ligue 1 • Eredivisie • Primeira Liga • Champions • Europa League • Conference",
                Toast.LENGTH_LONG).show());

        if (BuildConfig.API_FOOTBALL_KEY == null || BuildConfig.API_FOOTBALL_KEY.trim().isEmpty()) {
            showMessage("API_FOOTBALL_KEY non configurata nella build GitHub.");
            tvAccuracy.setText("API mancante");
        } else {
            loadDay(dateOffset(0), true);
        }
    }

    private void loadDay(String date, boolean predictions) {
        showLoading("Carico partite reali del " + date + "…");
        tvAccuracy.setText("Dati reali");
        executor.execute(() -> {
            try {
                String body = cachedGet("fixtures_" + date,
                        BASE_URL + "/fixtures?date=" + date + "&timezone=Europe%2FRome", CACHE_MS);
                JSONObject root = new JSONObject(body);
                checkApiErrors(root);
                JSONArray arr = root.getJSONArray("response");
                List<MatchPrediction> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (!LEAGUES.contains(item.getJSONObject("league").getInt("id"))) continue;
                    list.add(fixtureToMatch(item));
                }
                Collections.sort(list, (a, b) -> a.time.compareTo(b.time));
                if (list.isEmpty()) {
                    mainHandler.post(() -> showMessage("Nessuna partita dei principali campionati europei in questa data."));
                    return;
                }
                mainHandler.post(() -> renderMatches(list));
                if (predictions) {
                    for (MatchPrediction m : list) {
                        if (m.finished) continue;
                        try {
                            loadPrediction(m);
                            mainHandler.post(() -> renderMatches(list));
                            Thread.sleep(250);
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> showMessage("Errore dati: " + cleanError(e)));
            }
        });
    }

    private void loadPrediction(MatchPrediction m) throws Exception {
        String body = cachedGet("prediction_" + m.fixtureId,
                BASE_URL + "/predictions?fixture=" + m.fixtureId, 12L * 60L * 60L * 1000L);
        JSONObject root = new JSONObject(body);
        checkApiErrors(root);
        JSONArray arr = root.getJSONArray("response");
        if (arr.length() == 0) {
            m.analysis = "Pronostico API non ancora disponibile.";
            return;
        }
        JSONObject data = arr.getJSONObject(0);
        JSONObject pred = data.getJSONObject("predictions");
        JSONObject percent = pred.getJSONObject("percent");
        m.p1 = percentValue(percent.optString("home", "0%"));
        m.px = percentValue(percent.optString("draw", "0%"));
        m.p2 = percentValue(percent.optString("away", "0%"));
        m.confidence = Math.max(m.p1, Math.max(m.px, m.p2));
        String advice = pred.optString("advice", "");
        JSONObject winner = pred.optJSONObject("winner");
        m.pick = !advice.isEmpty() ? advice : (winner != null ? winner.optString("name", "Pronostico") : "Pronostico");

        try {
            JSONObject teams = data.getJSONObject("teams");
            JSONObject h5 = teams.getJSONObject("home").getJSONObject("last_5");
            JSONObject a5 = teams.getJSONObject("away").getJSONObject("last_5");
            double hf = avg(h5, "for"), ha = avg(h5, "against");
            double af = avg(a5, "for"), aa = avg(a5, "against");
            double eh = (hf + aa) / 2.0, ea = (af + ha) / 2.0;
            m.over25 = clamp((int)Math.round(35 + (eh + ea) * 12), 20, 88);
            m.goal = clamp((int)Math.round(30 + Math.min(eh, ea) * 30), 18, 85);
            m.analysis = "Forma ultime 5 • gol attesi circa " +
                    String.format(Locale.ITALY, "%.1f", eh) + "-" + String.format(Locale.ITALY, "%.1f", ea) +
                    " • API: " + pred.optString("under_over", "n/d");
        } catch (Exception e) {
            m.goal = 50;
            m.over25 = 50;
            m.analysis = "Pronostico API basato su forma, scontri diretti e storico.";
        }
    }

    private double avg(JSONObject last5, String side) throws Exception {
        Object value = last5.getJSONObject("goals").getJSONObject(side).opt("average");
        if (value == null || JSONObject.NULL.equals(value)) return 0;
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return 0; }
    }

    private void loadHistory() {
        String from = dateOffset(-7), to = dateOffset(-1);
        showLoading("Carico risultati reali ultimi 7 giorni…");
        tvAccuracy.setText("Storico reale");
        executor.execute(() -> {
            try {
                String body = cachedGet("history_" + from + "_" + to,
                        BASE_URL + "/fixtures?from=" + from + "&to=" + to + "&timezone=Europe%2FRome", CACHE_MS);
                JSONObject root = new JSONObject(body);
                checkApiErrors(root);
                JSONArray arr = root.getJSONArray("response");
                List<MatchPrediction> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    if (!LEAGUES.contains(item.getJSONObject("league").getInt("id"))) continue;
                    String status = item.getJSONObject("fixture").getJSONObject("status").optString("short", "");
                    if (!isFinished(status)) continue;
                    MatchPrediction m = fixtureToMatch(item);
                    JSONObject goals = item.getJSONObject("goals");
                    m.score = goals.optString("home", "-") + " - " + goals.optString("away", "-");
                    m.pick = "Risultato finale " + m.score;
                    m.analysis = "Risultato storico reale API-FOOTBALL";
                    list.add(m);
                }
                Collections.reverse(list);
                if (list.size() > 60) list = new ArrayList<>(list.subList(0, 60));
                List<MatchPrediction> result = list;
                mainHandler.post(() -> {
                    if (result.isEmpty()) showMessage("Nessun risultato disponibile negli ultimi 7 giorni.");
                    else renderMatches(result);
                });
            } catch (Exception e) {
                mainHandler.post(() -> showMessage("Errore storico: " + cleanError(e)));
            }
        });
    }

    private MatchPrediction fixtureToMatch(JSONObject item) throws Exception {
        MatchPrediction m = new MatchPrediction();
        JSONObject fixture = item.getJSONObject("fixture");
        JSONObject teams = item.getJSONObject("teams");
        m.fixtureId = fixture.getInt("id");
        m.league = item.getJSONObject("league").optString("name", "Campionato");
        m.time = formatTime(fixture.optString("date", ""));
        m.home = teams.getJSONObject("home").optString("name", "Casa");
        m.away = teams.getJSONObject("away").optString("name", "Trasferta");
        m.finished = isFinished(fixture.getJSONObject("status").optString("short", ""));
        m.pick = m.finished ? "Partita terminata" : "Analisi in caricamento…";
        m.analysis = m.finished ? "Risultato storico reale" : "Recupero forma, H2H e storico…";
        return m;
    }

    private void renderMatches(List<MatchPrediction> matches) {
        matchesContainer.removeAllViews();
        for (MatchPrediction m : matches) matchesContainer.addView(createMatchCard(m));
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
        top.addView(text(m.league, 12, R.color.text_secondary, true), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(text(m.time, 13, R.color.primary, true));
        root.addView(top);

        TextView teams = text(m.home + "  -  " + m.away, 20, R.color.text_primary, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(8); tlp.bottomMargin = dp(12);
        root.addView(teams, tlp);

        if (!m.finished) {
            LinearLayout probs = new LinearLayout(this);
            probs.addView(statBox("1", valueOrDash(m.p1)), statLp());
            probs.addView(statBox("X", valueOrDash(m.px)), statLp());
            probs.addView(statBox("2", valueOrDash(m.p2)), statLp());
            root.addView(probs);

            LinearLayout extras = new LinearLayout(this);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2); elp.topMargin = dp(8);
            extras.addView(statBox("Goal", valueOrDash(m.goal)), statLp());
            extras.addView(statBox("Over 2.5", valueOrDash(m.over25)), statLp());
            root.addView(extras, elp);
        }

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.surface_2));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
        dlp.topMargin = dp(14); dlp.bottomMargin = dp(12);
        root.addView(divider, dlp);
        root.addView(text(m.finished ? "RISULTATO" : "PRONOSTICO CONSIGLIATO", 11, R.color.text_secondary, true));

        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(m.pick, 18, R.color.primary, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!m.finished && m.confidence > 0) row.addView(text("Affidabilità " + m.confidence + "%", 12,
                m.confidence >= 65 ? R.color.primary : R.color.warn, true));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2); rlp.topMargin = dp(4);
        root.addView(row, rlp);

        MaterialButton detail = new MaterialButton(this);
        detail.setText("Dettagli analisi");
        detail.setAllCaps(false);
        detail.setTextColor(getColor(R.color.text_primary));
        detail.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.surface_2)));
        detail.setCornerRadius(dp(18));
        detail.setOnClickListener(v -> Toast.makeText(this, m.analysis, Toast.LENGTH_LONG).show());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(46)); blp.topMargin = dp(12);
        root.addView(detail, blp);
        card.addView(root);
        return card;
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1); lp.setMarginEnd(dp(6)); return lp;
    }

    private View statBox(String label, String value) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(5), dp(8), dp(5)); box.setBackgroundResource(R.drawable.bg_chip);
        box.addView(text(label, 11, R.color.text_secondary, true));
        box.addView(text(value, 17, R.color.text_primary, true)); return box;
    }

    private String cachedGet(String key, String url, long maxAge) throws Exception {
        long ts = cache.getLong(key + "_ts", 0); String saved = cache.getString(key, null);
        if (saved != null && System.currentTimeMillis() - ts < maxAge) return saved;
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setRequestProperty("x-apisports-key", BuildConfig.API_FOOTBALL_KEY);
        c.setRequestProperty("Accept", "application/json"); c.setConnectTimeout(15000); c.setReadTimeout(20000);
        int code = c.getResponseCode(); InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in)); StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line); br.close(); c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + sb);
        String body = sb.toString(); cache.edit().putString(key, body).putLong(key + "_ts", System.currentTimeMillis()).apply(); return body;
    }

    private void checkApiErrors(JSONObject root) throws Exception {
        Object errors = root.opt("errors");
        if (errors instanceof JSONArray && ((JSONArray)errors).length() > 0) throw new Exception(errors.toString());
        if (errors instanceof JSONObject && ((JSONObject)errors).length() > 0) throw new Exception(errors.toString());
    }

    private String dateOffset(int days) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")); c.add(Calendar.DAY_OF_YEAR, days);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ITALY); f.setTimeZone(TimeZone.getTimeZone("Europe/Rome")); return f.format(c.getTime());
    }

    private String formatTime(String iso) { return iso != null && iso.length() >= 16 ? iso.substring(11, 16) : "--:--"; }
    private boolean isFinished(String s) { return "FT".equals(s) || "AET".equals(s) || "PEN".equals(s); }
    private int percentValue(String s) { try { return Integer.parseInt(s.replace("%", "").trim()); } catch (Exception e) { return 0; } }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private String valueOrDash(int v) { return v <= 0 ? "—" : v + "%"; }

    private void showLoading(String s) { matchesContainer.removeAllViews(); TextView t = text(s, 16, R.color.text_secondary, true); t.setGravity(Gravity.CENTER); t.setPadding(dp(12), dp(36), dp(12), dp(36)); matchesContainer.addView(t); }
    private void showMessage(String s) { matchesContainer.removeAllViews(); TextView t = text(s, 16, R.color.text_primary, true); t.setGravity(Gravity.CENTER); t.setPadding(dp(12), dp(36), dp(12), dp(36)); matchesContainer.addView(t); }
    private String cleanError(Exception e) { String s = e.getMessage(); if (s == null) return "errore sconosciuto"; return s.length() > 180 ? s.substring(0, 180) : s; }
    private TextView text(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(getColor(color)); if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD); return t; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
