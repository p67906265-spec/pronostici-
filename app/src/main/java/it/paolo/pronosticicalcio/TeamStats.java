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
    // (0/1/3) nella partita i-esima, recentOpponentPpgAtTime[i] e' il
    // punti/partita dell'avversario CALCOLATO CON I SOLI DATI DISPONIBILI
    // FINO A QUEL MOMENTO (non con lo storico completo), oppure NaN se
    // sconosciuto. Le due liste restano sempre della stessa lunghezza, in
    // ordine parallelo. Usate da PredictionEngine per pesare la forma
    // recente in base alla forza che l'avversario aveva REALMENTE in quel
    // momento, evitando un look-ahead bias (vedi PredictionEngine.weightedRecentPPG).
    final List<Integer> recentPoints = new ArrayList<>();
    final List<Double> recentOpponentPpgAtTime = new ArrayList<>();

    void add(boolean home, int scored, int conceded, int pts) {
        add(home, scored, conceded, pts, Double.NaN);
    }

    /**
     * @param opponentPpgAtTime punti/partita dell'avversario calcolato SOLO
     *                          sulle partite dell'avversario già note prima
     *                          di questa (istantanea storica), oppure
     *                          Double.NaN se non disponibile. Chi chiama
     *                          questo metodo deve garantire che le partite
     *                          vengano inserite in ordine cronologico,
     *                          altrimenti l'istantanea non è corretta.
     */
    void add(boolean home, int scored, int conceded, int pts, double opponentPpgAtTime) {
        played++; gf += scored; ga += conceded; points += pts;
        if (home) { homePlayed++; homeGF += scored; homeGA += conceded; }
        else { awayPlayed++; awayGF += scored; awayGA += conceded; }
        recentPoints.add(pts);
        recentOpponentPpgAtTime.add(opponentPpgAtTime);
        while (recentPoints.size() > 8) {
            recentPoints.remove(0);
            recentOpponentPpgAtTime.remove(0);
        }
    }

    /** Punti/partita correnti, per uso come istantanea storica da un'altra squadra. */
    double currentPPG() {
        return played == 0 ? Double.NaN : (double) points / played;
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

