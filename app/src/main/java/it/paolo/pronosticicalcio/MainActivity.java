package it.paolo.pronosticicalcio;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private LinearLayout matchesContainer;

    static class MatchPrediction {
        final String league;
        final String time;
        final String home;
        final String away;
        final int p1;
        final int px;
        final int p2;
        final int goal;
        final int over25;
        final String pick;
        final int confidence;

        MatchPrediction(String league, String time, String home, String away,
                        int p1, int px, int p2, int goal, int over25,
                        String pick, int confidence) {
            this.league = league;
            this.time = time;
            this.home = home;
            this.away = away;
            this.p1 = p1;
            this.px = px;
            this.p2 = p2;
            this.goal = goal;
            this.over25 = over25;
            this.pick = pick;
            this.confidence = confidence;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        matchesContainer = findViewById(R.id.matchesContainer);

        findViewById(R.id.btnToday).setOnClickListener(v -> renderMatches(todayMatches(), "Partite demo di oggi"));
        findViewById(R.id.btnTomorrow).setOnClickListener(v -> renderMatches(tomorrowMatches(), "Partite demo di domani"));
        findViewById(R.id.btnLeagues).setOnClickListener(v -> Toast.makeText(this, "Campionati: funzione in arrivo", Toast.LENGTH_SHORT).show());
        findViewById(R.id.btnHistory).setOnClickListener(v -> Toast.makeText(this, "Storico pronostici: funzione in arrivo", Toast.LENGTH_SHORT).show());

        renderMatches(todayMatches(), null);
    }

    private void renderMatches(List<MatchPrediction> matches, String toast) {
        matchesContainer.removeAllViews();
        for (MatchPrediction match : matches) {
            matchesContainer.addView(createMatchCard(match));
        }
        if (toast != null) {
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
    }

    private View createMatchCard(MatchPrediction m) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.bottomMargin = dp(14);
        card.setLayoutParams(cardLp);
        card.setRadius(dp(22));
        card.setCardBackgroundColor(getColor(R.color.surface));
        card.setStrokeColor(getColor(R.color.surface_2));
        card.setStrokeWidth(dp(1));
        card.setCardElevation(dp(1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(15), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView league = text(m.league, 12, R.color.text_secondary, true);
        top.addView(league, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView time = text(m.time, 13, R.color.primary, true);
        top.addView(time);
        root.addView(top);

        TextView teams = text(m.home + "  -  " + m.away, 21, R.color.text_primary, true);
        LinearLayout.LayoutParams teamsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        teamsLp.topMargin = dp(8);
        teamsLp.bottomMargin = dp(12);
        root.addView(teams, teamsLp);

        LinearLayout probs = new LinearLayout(this);
        probs.setOrientation(LinearLayout.HORIZONTAL);
        probs.addView(statBox("1", m.p1 + "%"), statLp());
        probs.addView(statBox("X", m.px + "%"), statLp());
        probs.addView(statBox("2", m.p2 + "%"), statLp());
        root.addView(probs);

        LinearLayout extras = new LinearLayout(this);
        extras.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams extrasLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        extrasLp.topMargin = dp(8);
        extras.addView(statBox("Goal", m.goal + "%"), statLp());
        extras.addView(statBox("Over 2.5", m.over25 + "%"), statLp());
        root.addView(extras, extrasLp);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.surface_2));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        );
        divLp.topMargin = dp(14);
        divLp.bottomMargin = dp(12);
        root.addView(divider, divLp);

        TextView label = text("PRONOSTICO CONSIGLIATO", 11, R.color.text_secondary, true);
        root.addView(label);

        LinearLayout pickRow = new LinearLayout(this);
        pickRow.setOrientation(LinearLayout.HORIZONTAL);
        pickRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView pick = text(m.pick, 20, R.color.primary, true);
        pickRow.addView(pick, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView conf = text("Affidabilità " + m.confidence + "%", 12,
                m.confidence >= 70 ? R.color.primary : R.color.warn, true);
        pickRow.addView(conf);

        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        pickLp.topMargin = dp(4);
        root.addView(pickRow, pickLp);

        MaterialButton detail = new MaterialButton(this);
        detail.setText("Dettagli analisi");
        detail.setAllCaps(false);
        detail.setTextColor(getColor(R.color.text_primary));
        detail.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.surface_2)));
        detail.setCornerRadius(dp(18));
        detail.setOnClickListener(v -> Toast.makeText(
                this,
                "Analisi demo: forma recente, casa/trasferta, gol fatti e subiti.",
                Toast.LENGTH_LONG
        ).show());
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
        );
        detailLp.topMargin = dp(12);
        root.addView(detail, detailLp);

        card.addView(root);
        return card;
    }

    private LinearLayout.LayoutParams statLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private View statBox(String label, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(5), dp(8), dp(5));
        box.setBackgroundResource(R.drawable.bg_chip);

        TextView l = text(label, 11, R.color.text_secondary, true);
        TextView v = text(value, 17, R.color.text_primary, true);
        box.addView(l);
        box.addView(v);
        return box;
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(colorRes));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private List<MatchPrediction> todayMatches() {
        List<MatchPrediction> list = new ArrayList<>();
        list.add(new MatchPrediction("Serie A", "18:30", "Inter", "Torino", 62, 23, 15, 52, 58, "1 + Over 1.5", 74));
        list.add(new MatchPrediction("Serie A", "20:45", "Roma", "Bologna", 44, 30, 26, 61, 54, "1X + Goal", 67));
        list.add(new MatchPrediction("Premier League", "21:00", "Liverpool", "Newcastle", 57, 25, 18, 66, 63, "1X + Over 2.5", 72));
        return list;
    }

    private List<MatchPrediction> tomorrowMatches() {
        List<MatchPrediction> list = new ArrayList<>();
        list.add(new MatchPrediction("Liga", "19:00", "Villarreal", "Getafe", 49, 30, 21, 48, 45, "1X", 64));
        list.add(new MatchPrediction("Bundesliga", "20:30", "Dortmund", "Mainz", 64, 21, 15, 59, 62, "1 + Over 1.5", 76));
        return list;
    }
}
