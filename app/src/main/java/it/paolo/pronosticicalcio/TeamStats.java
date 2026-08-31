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

    // Ultimi risultati (fino a 8): recentPoints[i] e' il punteggio ottenuto
    // (0/1/3) nella partita i-esima, recentOpponents[i] e' la chiave
    // (nome normalizzato) dell'avversario di quella partita, o stringa
    // vuota se sconosciuto. Le due liste restano sempre della stessa
    // lunghezza, in ordine parallelo. Usate da PredictionEngine per pesare
    // la forma recente in base alla forza dell'avversario incontrato.
    final List<Integer> recentPoints = new ArrayList<>();
    final List<String> recentOpponents = new ArrayList<>();

    void add(boolean home, int scored, int conceded, int pts) {
        add(home, scored, conceded, pts, "");
    }

    void add(boolean home, int scored, int conceded, int pts, String opponentKey) {
        played++; gf += scored; ga += conceded; points += pts;
        if (home) { homePlayed++; homeGF += scored; homeGA += conceded; }
        else { awayPlayed++; awayGF += scored; awayGA += conceded; }
        recentPoints.add(pts);
        recentOpponents.add(opponentKey == null ? "" : opponentKey);
        while (recentPoints.size() > 8) {
            recentPoints.remove(0);
            recentOpponents.remove(0);
        }
    }

    double avgGF() { return played == 0 ? 1.25 : (double) gf / played; }
    double avgGA() { return played == 0 ? 1.25 : (double) ga / played; }
    double avgHomeGF() { return homePlayed == 0 ? avgGF() : (double) homeGF / homePlayed; }
    double avgHomeGA() { return homePlayed == 0 ? avgGA() : (double) homeGA / homePlayed; }
    double avgAwayGF() { return awayPlayed == 0 ? avgGF() : (double) awayGF / awayPlayed; }
    double avgAwayGA() { return awayPlayed == 0 ? avgGA() : (double) awayGA / awayPlayed; }

    /**
     * Media punti/partita "grezza" delle ultime partite, senza considerare
     * la forza degli avversari incontrati. Mantenuta per semplicità/compatibilità;
     * PredictionEngine usa invece la versione pesata (vedi
     * PredictionEngine.weightedRecentPPG), che dà più credito a un buon
     * risultato contro un avversario forte.
     */
    double recentPPG() {
        if (recentPoints.isEmpty()) return 1.35;
        int total = 0;
        for (int p : recentPoints) total += p;
        return (double) total / recentPoints.size();
    }

    int recentCount() { return recentPoints.size(); }
}

