package it.paolo.pronosticicalcio;

import java.util.Locale;
import java.util.Map;

/**
 * Motore di pronostico "proprio" dell'app: stima gli xG di una partita
 * dal rendimento storico delle due squadre (attacco/difesa, casa/trasferta,
 * forma recente) e ne deriva le probabilità 1/X/2, Gol e Over 2.5 con un
 * modello di Poisson.
 *
 * Classe senza dipendenze Android: prende in input solo i dati già caricati
 * (MatchPrediction, TeamStats, numero di giorni d'archivio disponibili) e
 * scrive il risultato dentro l'oggetto MatchPrediction passato. Questo la
 * rende testabile con JUnit puro, senza bisogno di un device/emulatore.
 */
public class PredictionEngine {

    /** Quanti giorni di storico l'app prova a mantenere in archivio locale. */
    public static final int MODEL_HISTORY_DAYS = 60;

    /** Soglia di confidenza sopra la quale si pronostica un esito secco (1/X/2). */
    private static final int SINGLE_PICK_CONFIDENCE_THRESHOLD = 58;

    private PredictionEngine() {
        // Solo metodi statici: nessuna istanza necessaria.
    }

    /**
     * Calcola il pronostico per la partita {@code m} e scrive il risultato
     * (p1/px/p2, goal, over25, confidence, pick, analysis) direttamente sui
     * suoi campi.
     *
     * @param m           la partita da pronosticare (modificata in place)
     * @param history     statistiche per squadra ricavate dall'archivio storico
     * @param archiveDays quanti giorni di archivio erano disponibili al momento
     *                    del calcolo (solo per il testo descrittivo dell'analisi)
     */
    public static void calculate(MatchPrediction m, Map<Integer, TeamStats> history, int archiveDays) {
        TeamStats home = history.get(m.homeId);
        TeamStats away = history.get(m.awayId);
        if (home == null) home = new TeamStats();
        if (away == null) away = new TeamStats();

        double homeAttack = 0.55 * home.avgGF() + 0.45 * home.avgHomeGF();
        double homeDefense = 0.55 * home.avgGA() + 0.45 * home.avgHomeGA();
        double awayAttack = 0.55 * away.avgGF() + 0.45 * away.avgAwayGF();
        double awayDefense = 0.55 * away.avgGA() + 0.45 * away.avgAwayGA();

        double formDiff = home.recentPPG() - away.recentPPG();

        double xgHome = clampDouble(((homeAttack + awayDefense) / 2.0) * 1.08 + 0.12 + formDiff * 0.08, 0.25, 3.40);
        double xgAway = clampDouble(((awayAttack + homeDefense) / 2.0) * 0.96 - formDiff * 0.05, 0.20, 3.10);

        double pHome = 0.0, pDraw = 0.0, pAway = 0.0;
        for (int hg = 0; hg <= 7; hg++) {
            double ph = poisson(hg, xgHome);
            for (int ag = 0; ag <= 7; ag++) {
                double p = ph * poisson(ag, xgAway);
                if (hg > ag) pHome += p;
                else if (hg == ag) pDraw += p;
                else pAway += p;
            }
        }

        double total = pHome + pDraw + pAway;
        if (total <= 0) total = 1.0;
        pHome /= total; pDraw /= total; pAway /= total;

        m.p1 = (int) Math.round(pHome * 100);
        m.px = (int) Math.round(pDraw * 100);
        m.p2 = 100 - m.p1 - m.px;

        m.goal = clamp((int) Math.round((1.0 - Math.exp(-xgHome)) * (1.0 - Math.exp(-xgAway)) * 100), 5, 95);

        double lambda = xgHome + xgAway;
        double underEq2 = poisson(0, lambda) + poisson(1, lambda) + poisson(2, lambda);
        m.over25 = clamp((int) Math.round((1.0 - underEq2) * 100), 5, 95);

        m.confidence = Math.max(m.p1, Math.max(m.px, m.p2));

        if (m.p1 >= m.px && m.p1 >= m.p2) m.predicted1x2 = "1";
        else if (m.px >= m.p1 && m.px >= m.p2) m.predicted1x2 = "X";
        else m.predicted1x2 = "2";

        if (m.confidence >= SINGLE_PICK_CONFIDENCE_THRESHOLD) {
            if ("1".equals(m.predicted1x2)) m.pick = "Vittoria " + m.home;
            else if ("2".equals(m.predicted1x2)) m.pick = "Vittoria " + m.away;
            else m.pick = "Pareggio";
        } else {
            int oneX = m.p1 + m.px;
            int xTwo = m.px + m.p2;
            int oneTwo = m.p1 + m.p2;
            if (oneX >= xTwo && oneX >= oneTwo) m.pick = "Doppia chance: " + m.home + " o pareggio";
            else if (xTwo >= oneTwo) m.pick = "Doppia chance: pareggio o " + m.away;
            else m.pick = "Doppia chance: " + m.home + " o " + m.away;
        }

        int sample = Math.min(home.recentCount(), away.recentCount());
        String sampleText = sample >= 5 ? "campione recente buono" : (sample >= 3 ? "campione recente medio" : "pochi dati recenti");

        m.analysis = "MODELLO PROPRIO • xG stimati "
                + String.format(Locale.ITALY, "%.2f", xgHome) + " - "
                + String.format(Locale.ITALY, "%.2f", xgAway)
                + " • " + sampleText
                + " • archivio locale " + archiveDays + "/" + MODEL_HISTORY_DAYS + " giorni"
                + " • rendimento casa/trasferta e risultati recenti calcolati dall'app.";
    }

    private static double poisson(int k, double lambda) {
        double fact = 1.0;
        for (int i = 2; i <= k; i++) fact *= i;
        return Math.exp(-lambda) * Math.pow(lambda, k) / fact;
    }

    private static double clampDouble(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
