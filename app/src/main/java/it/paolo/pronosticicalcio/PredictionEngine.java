package it.paolo.pronosticicalcio;

import java.util.Locale;
import java.util.Map;

/**
 * Motore di pronostico "proprio" dell'app: stima gli xG di una partita
 * dal rendimento storico delle due squadre (attacco/difesa, casa/trasferta,
 * forma recente) e ne deriva le probabilità 1/X/2, Gol e Over 2.5 con un
 * modello di Poisson bivariato.
 *
 * Rispetto alla prima versione, il modello applica due correzioni
 * statistiche standard nella letteratura sui pronostici calcistici:
 *
 * 1) SHRINKAGE BAYESIANO sulle medie gol di ogni squadra: quando una
 *    squadra ha poche partite in archivio, la sua media osservata (che può
 *    essere estrema per puro caso, es. 5-0 alla prima giornata) viene
 *    "attenuata" verso una media di riferimento prudente. Più partite reali
 *    si accumulano, meno pesa la media di riferimento e più conta il dato
 *    osservato. Tecnica standard per evitare overconfidence con pochi dati.
 *
 * 2) CORREZIONE DIXON-COLES sui risultati bassi (0-0, 1-0, 0-1, 1-1): un
 *    Poisson indipendente per gol-casa e gol-trasferta sottostima
 *    leggermente questi 4 risultati nella realtà (i gol nel calcio non sono
 *    perfettamente indipendenti a punteggi bassi). Si applica un fattore
 *    di correzione tau solo su queste 4 celle prima di ricavare 1/X/2.
 *    Riferimento: Dixon, M.J. e Coles, S.G. (1997), "Modelling Association
 *    Football Scores and Inefficiencies in the Football Betting Market".
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

    // --- Medie di riferimento (prior) per lo shrinkage bayesiano ---
    // Valori tipici osservati nei principali campionati europei: una squadra
    // media segna/subisce circa 1.30 gol a partita in generale, un po' di
    // più in casa (vantaggio campo) e un po' meno in trasferta.
    private static final double PRIOR_OVERALL_GOALS = 1.30;
    private static final double PRIOR_HOME_GF = 1.45;
    private static final double PRIOR_HOME_GA = 1.15;
    private static final double PRIOR_AWAY_GF = 1.15;
    private static final double PRIOR_AWAY_GA = 1.45;

    // Quante "partite virtuali" pesa il prior nello shrinkage. Con 6, dopo
    // circa 12-18 partite reali il prior conta ormai molto poco; con 1-2
    // partite reali invece domina ancora la media prudente.
    private static final double SHRINKAGE_WEIGHT_GAMES = 6.0;

    // Sotto questa soglia di partite (per la squadra col campione minore)
    // segnaliamo nell'analisi che il dato è ancora acerbo.
    private static final int LOW_SAMPLE_GAMES_THRESHOLD = 4;

    // Parametro rho della correzione Dixon-Coles: valore negativo piccolo,
    // in linea con la letteratura (Dixon-Coles originale stimava rho intorno
    // a -0.13 sui dati inglesi anni '90; studi più recenti su campionati
    // moderni trovano valori più contenuti, qui -0.08 come compromesso
    // prudente senza una calibrazione dedicata sui nostri dati).
    private static final double DIXON_COLES_RHO = -0.08;

    private PredictionEngine() {
        // Solo metodi statici: nessuna istanza necessaria.
    }

    /**
     * Calcola il pronostico per la partita {@code m} e scrive il risultato
     * (p1/px/p2, goal, over25, confidence, pick, analysis) direttamente sui
     * suoi campi.
     *
     * @param m           la partita da pronosticare (modificata in place)
     * @param history     statistiche per squadra ricavate dall'archivio storico,
     *                    con chiave il nome squadra normalizzato (TeamNameUtil):
     *                    l'archivio arriva da football-data.org e usa quindi ID
     *                    diversi da quelli di API-Football usati in MatchPrediction
     * @param archiveDays quanti giorni di archivio erano disponibili al momento
     *                    del calcolo (solo per il testo descrittivo dell'analisi)
     */
    public static void calculate(MatchPrediction m, Map<String, TeamStats> history, int archiveDays) {
        TeamStats home = history.get(TeamNameUtil.normalize(m.home));
        TeamStats away = history.get(TeamNameUtil.normalize(m.away));
        if (home == null) home = new TeamStats();
        if (away == null) away = new TeamStats();

        // Medie "attenuate" (shrinkage bayesiano): usano il totale gol
        // segnati/subiti e il numero di partite già presenti in TeamStats,
        // sfumate verso la media di riferimento quando il campione è piccolo.
        double homeOverallGF = shrink(home.gf, home.played, PRIOR_OVERALL_GOALS);
        double homeOverallGA = shrink(home.ga, home.played, PRIOR_OVERALL_GOALS);
        double homeHomeGF = shrink(home.homeGF, home.homePlayed, PRIOR_HOME_GF);
        double homeHomeGA = shrink(home.homeGA, home.homePlayed, PRIOR_HOME_GA);

        double awayOverallGF = shrink(away.gf, away.played, PRIOR_OVERALL_GOALS);
        double awayOverallGA = shrink(away.ga, away.played, PRIOR_OVERALL_GOALS);
        double awayAwayGF = shrink(away.awayGF, away.awayPlayed, PRIOR_AWAY_GF);
        double awayAwayGA = shrink(away.awayGA, away.awayPlayed, PRIOR_AWAY_GA);

        double homeAttack = 0.55 * homeOverallGF + 0.45 * homeHomeGF;
        double homeDefense = 0.55 * homeOverallGA + 0.45 * homeHomeGA;
        double awayAttack = 0.55 * awayOverallGF + 0.45 * awayAwayGF;
        double awayDefense = 0.55 * awayOverallGA + 0.45 * awayAwayGA;

        double formDiff = home.recentPPG() - away.recentPPG();

        double xgHome = clampDouble(((homeAttack + awayDefense) / 2.0) * 1.08 + 0.12 + formDiff * 0.08, 0.25, 3.40);
        double xgAway = clampDouble(((awayAttack + homeDefense) / 2.0) * 0.96 - formDiff * 0.05, 0.20, 3.10);

        double pHome = 0.0, pDraw = 0.0, pAway = 0.0;
        for (int hg = 0; hg <= 7; hg++) {
            double ph = poisson(hg, xgHome);
            for (int ag = 0; ag <= 7; ag++) {
                double p = ph * poisson(ag, xgAway);
                p *= dixonColesTau(hg, ag, xgHome, xgAway);
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

        int minPlayed = Math.min(home.played, away.played);
        String shrinkageNote = minPlayed < LOW_SAMPLE_GAMES_THRESHOLD
                ? " • dati storici ancora scarsi per almeno una squadra: stima attenuata verso la media di lega"
                : "";

        m.analysis = "MODELLO PROPRIO • xG stimati "
                + String.format(Locale.ITALY, "%.2f", xgHome) + " - "
                + String.format(Locale.ITALY, "%.2f", xgAway)
                + " • " + sampleText
                + " • archivio locale " + archiveDays + "/" + MODEL_HISTORY_DAYS + " giorni"
                + " • correzione pareggi/risultati bassi (Dixon-Coles)"
                + shrinkageNote
                + " • rendimento casa/trasferta e risultati recenti calcolati dall'app.";
    }

    /**
     * Media "attenuata" verso il prior: con {@code n} osservazioni reali e
     * somma {@code sum}, restituisce una media pesata tra il dato osservato
     * e {@code priorAvg}, dove il prior pesa quanto {@link #SHRINKAGE_WEIGHT_GAMES}
     * partite. Con n=0 restituisce esattamente priorAvg; con n grande si
     * avvicina alla media osservata pura (sum/n).
     */
    private static double shrink(int sum, int n, double priorAvg) {
        return (sum + SHRINKAGE_WEIGHT_GAMES * priorAvg) / (n + SHRINKAGE_WEIGHT_GAMES);
    }

    /**
     * Fattore di correzione Dixon-Coles per le 4 celle a basso punteggio
     * (0-0, 1-0, 0-1, 1-1). Per ogni altra combinazione restituisce 1.0
     * (nessuna correzione). Il risultato finale viene comunque rinormalizzato
     * (vedi divisione per {@code total} in {@link #calculate}), quindi la
     * correzione sposta probabilità tra gli esiti senza bisogno di un
     * ricalcolo separato della somma.
     */
    private static double dixonColesTau(int homeGoals, int awayGoals, double lambda, double mu) {
        if (homeGoals == 0 && awayGoals == 0) return 1.0 - (lambda * mu * DIXON_COLES_RHO);
        if (homeGoals == 0 && awayGoals == 1) return 1.0 + (lambda * DIXON_COLES_RHO);
        if (homeGoals == 1 && awayGoals == 0) return 1.0 + (mu * DIXON_COLES_RHO);
        if (homeGoals == 1 && awayGoals == 1) return 1.0 - DIXON_COLES_RHO;
        return 1.0;
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
