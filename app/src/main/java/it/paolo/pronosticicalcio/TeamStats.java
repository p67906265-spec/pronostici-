package it.paolo.pronosticicalcio;

import java.util.ArrayList;
import java.util.List;

/**
 * Statistiche aggregate di una squadra (rendimento generale, casa/trasferta
 * e forma recente) calcolate dall'archivio storico locale.
 * Usata da PredictionEngine per stimare gli xG di una partita.
 */
public class TeamStats {
    int played, gf, ga, points;
    int homePlayed, homeGF, homeGA;
    int awayPlayed, awayGF, awayGA;
    final List<Integer> recentPoints = new ArrayList<>();

    void add(boolean home, int scored, int conceded, int pts) {
        played++; gf += scored; ga += conceded; points += pts;
        if (home) { homePlayed++; homeGF += scored; homeGA += conceded; }
        else { awayPlayed++; awayGF += scored; awayGA += conceded; }
        recentPoints.add(pts);
        while (recentPoints.size() > 8) recentPoints.remove(0);
    }

    double avgGF() { return played == 0 ? 1.25 : (double) gf / played; }
    double avgGA() { return played == 0 ? 1.25 : (double) ga / played; }
    double avgHomeGF() { return homePlayed == 0 ? avgGF() : (double) homeGF / homePlayed; }
    double avgHomeGA() { return homePlayed == 0 ? avgGA() : (double) homeGA / homePlayed; }
    double avgAwayGF() { return awayPlayed == 0 ? avgGF() : (double) awayGF / awayPlayed; }
    double avgAwayGA() { return awayPlayed == 0 ? avgGA() : (double) awayGA / awayPlayed; }

    double recentPPG() {
        if (recentPoints.isEmpty()) return 1.35;
        int total = 0;
        for (int p : recentPoints) total += p;
        return (double) total / recentPoints.size();
    }

    int recentCount() { return recentPoints.size(); }
}
