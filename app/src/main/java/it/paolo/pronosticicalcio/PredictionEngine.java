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
 * 3) FORMA RECENTE PESATA PER LA FORZA DELL'AVVERSARIO: la forma recente
 *    (ultime partite) non è più una semplice media punti, ma dà più credito
 *    a un buon risultato ottenuto contro un avversario forte e penalizza
 *    meno una sconfitta contro un avversario forte (e viceversa per gli
 *    avversari deboli). Vedi {@link #weightedRecentPPG}.
 *
 * 4) PRIOR INFORMATO PER SQUADRA (curriculum stagione precedente): quando
 *    l'archivio della stagione corrente è ancora scarso (es. inizio
 *    stagione), lo shrinkage bayesiano non attenua più verso una media di
 *    lega generica uguale per tutti, ma verso il rendimento REALE che
 *    quella specifica squadra aveva l'anno scorso (da classifica finale
 *    vera, non da un'opinione su chi è "forte"). Se la squadra non è
 *    presente nella classifica dell'anno scorso (es. neopromossa), si
 *    ricade sulla media di lega come prima. Vedi {@link #teamPrior}.
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

    // --- Parametri per la forma recente pesata per forza avversario ---
    // Media punti/partita "neutra" di riferimento (una squadra media vince
    // un po' più della metà delle volte in un campionato a 3 punti a
    // vittoria: ~1.35 punti/partita è un valore tipico).
    private static final double FORM_BASELINE_PPG = 1.35;
    // Quanto la forza dell'avversario sposta il punteggio "atteso" in una
    // partita: con 0.5, un avversario molto più forte della media abbassa
    // l'aspettativa di punti, un avversario molto più debole la alza.
    private static final double FORM_OPPONENT_ADJUSTMENT = 0.5;

    private PredictionEngine() {
        // Solo metodi statici: nessuna istanza necessaria.
    }

    /**
     * Calcola il pronostico per la partita {@code m} usando solo l'archivio
     * della stagione corrente, senza un prior informato per squadra (media
     * di lega generica per tutti). Mantenuto per compatibilità con i test
     * esistenti; equivale a chiamare la versione a 4 argomenti con una
     * mappa di prior vuota.
     */
    public static void calculate(MatchPrediction m, Map<String, TeamStats> history, int archiveDays) {
        calculate(m, history, java.util.Collections.emptyMap(), archiveDays);
    }

    /**
     * Calcola il pronostico per la partita {@code m} e scrive il risultato
     * (p1/px/p2, goal, over25, confidence, pick, analysis) direttamente sui
     * suoi campi.
     *
     * @param m                    la partita da pronosticare (modificata in place)
     * @param history              statistiche per squadra ricavate dall'archivio storico,
     *                             con chiave il nome squadra normalizzato (TeamNameUtil):
     *                             l'archivio arriva da football-data.org e usa quindi ID
     *                             diversi da quelli di API-Football usati in MatchPrediction
     * @param previousSeasonPriors rendimento reale di ogni squadra nella stagione
     *                             precedente (stessa chiave: nome normalizzato).
     *                             Usato come prior per lo shrinkage bayesiano al posto
     *                             della media di lega generica, quando disponibile per
     *                             quella squadra (altrimenti si ricade sulla media di lega
     *                             come prima)
     * @param archiveDays          quanti giorni di archivio erano disponibili al momento
     *                             del calcolo (solo per il testo descrittivo dell'analisi)
     */
    public static void calculate(MatchPrediction m, Map<String, TeamStats> history,
                                  Map<String, SeasonPrior> previousSeasonPriors, int archiveDays) {
        TeamStats home = history.get(TeamNameUtil.normalize(m.home));
        TeamStats away = history.get(TeamNameUtil.normalize(m.away));
        if (home == null) home = new TeamStats();
        if (away == null) away = new TeamStats();

        SeasonPrior homeSeasonPrior = previousSeasonPriors.get(TeamNameUtil.normalize(m.home));
        SeasonPrior awaySeasonPrior = previousSeasonPriors.get(TeamNameUtil.normalize(m.away));

        // Prior "informati" per squadra: se abbiamo il curriculum della
        // stagione scorsa lo usiamo come riferimento, con lo stesso
        // rapporto casa/trasferta della media di lega di default; se non
        // c'è (es. neopromossa) si ricade sulla media di lega, come prima.
        double homePriorOverallGF = homeSeasonPrior != null ? homeSeasonPrior.avgGF : PRIOR_OVERALL_GOALS;
        double homePriorOverallGA = homeSeasonPrior != null ? homeSeasonPrior.avgGA : PRIOR_OVERALL_GOALS;
        double homePriorHomeGF = teamPrior(homeSeasonPrior, true, PRIOR_HOME_GF);
        double homePriorHomeGA = teamPrior(homeSeasonPrior, false, PRIOR_HOME_GA);

        double awayPriorOverallGF = awaySeasonPrior != null ? awaySeasonPrior.avgGF : PRIOR_OVERALL_GOALS;
        double awayPriorOverallGA = awaySeasonPrior != null ? awaySeasonPrior.avgGA : PRIOR_OVERALL_GOALS;
        double awayPriorAwayGF = teamPrior(awaySeasonPrior, true, PRIOR_AWAY_GF);
        double awayPriorAwayGA = teamPrior(awaySeasonPrior, false, PRIOR_AWAY_GA);

        // Medie "attenuate" (shrinkage bayesiano): usano il totale gol
        // segnati/subiti e il numero di partite già presenti in TeamStats,
        // sfumate verso il prior (di squadra se disponibile, altrimenti di
        // lega) quando il campione della stagione corrente è piccolo.
        double homeOverallGF = shrink(home.gf, home.played, homePriorOverallGF);
        double homeOverallGA = shrink(home.ga, home.played, homePriorOverallGA);
        double homeHomeGF = shrink(home.homeGF, home.homePlayed, homePriorHomeGF);
        double homeHomeGA = shrink(home.homeGA, home.homePlayed, homePriorHomeGA);

        double awayOverallGF = shrink(away.gf, away.played, awayPriorOverallGF);
        double awayOverallGA = shrink(away.ga, away.played, awayPriorOverallGA);
        double awayAwayGF = shrink(away.awayGF, away.awayPlayed, awayPriorAwayGF);
        double awayAwayGA = shrink(away.awayGA, away.awayPlayed, awayPriorAwayGA);

        double homeAttack = 0.55 * homeOverallGF + 0.45 * homeHomeGF;
        double homeDefense = 0.55 * homeOverallGA + 0.45 * homeHomeGA;
        double awayAttack = 0.55 * awayOverallGF + 0.45 * awayAwayGF;
        double awayDefense = 0.55 * awayOverallGA + 0.45 * awayAwayGA;

        double formDiff = weightedRecentPPG(home, history) - weightedRecentPPG(away, history);

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
        boolean anySeasonPrior = homeSeasonPrior != null || awaySeasonPrior != null;
        String shrinkageNote;
        if (minPlayed < LOW_SAMPLE_GAMES_THRESHOLD && anySeasonPrior) {
            shrinkageNote = " • dati storici ancora scarsi per almeno una squadra: stima attenuata"
                    + " verso il suo rendimento nella stagione precedente";
        } else if (minPlayed < LOW_SAMPLE_GAMES_THRESHOLD) {
            shrinkageNote = " • dati storici ancora scarsi per almeno una squadra: stima attenuata verso la media di lega";
        } else {
            shrinkageNote = "";
        }

        m.analysis = "MODELLO PROPRIO • xG stimati "
                + String.format(Locale.ITALY, "%.2f", xgHome) + " - "
                + String.format(Locale.ITALY, "%.2f", xgAway)
                + " • " + sampleText
                + " • archivio locale " + archiveDays + "/" + MODEL_HISTORY_DAYS + " giorni"
                + " • correzione pareggi/risultati bassi (Dixon-Coles)"
                + " • forma recente pesata per la forza degli avversari"
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
     * Prior casa/trasferta specifico per la squadra, derivato dal suo
     * rendimento medio nella stagione passata ({@code seasonPrior}),
     * mantenendo lo stesso rapporto casa/trasferta della media di lega di
     * default (es. le squadre segnano un po' di più in casa in generale,
     * quindi anche il prior della singola squadra viene scalato allo
     * stesso modo). Se {@code seasonPrior} è null (squadra non trovata
     * nella classifica dell'anno scorso, es. neopromossa), restituisce
     * semplicemente {@code flatSplit}, cioè il vecchio comportamento.
     */
    private static double teamPrior(SeasonPrior seasonPrior, boolean forGoalsFor, double flatSplit) {
        if (seasonPrior == null) return flatSplit;
        double teamOverall = forGoalsFor ? seasonPrior.avgGF : seasonPrior.avgGA;
        return teamOverall * (flatSplit / PRIOR_OVERALL_GOALS);
    }

    /**
     * Forma recente di una squadra, pesata per la forza degli avversari
     * incontrati nelle ultime partite. Per ogni partita recente calcola un
     * punteggio "atteso" in base al rendimento medio (punti/partita)
     * dell'avversario in quel momento: un avversario più forte della media
     * abbassa l'atteso (quindi un pareggio o una sconfitta contano meno in
     * negativo, e una vittoria conta di più in positivo); un avversario più
     * debole della media alza l'atteso (quindi vincere conta meno, perdere
     * pesa di più). La forma finale è la media di questi scarti (vittoria/
     * sconfitta rispetto all'atteso), riportata sulla stessa scala 0-3 di
     * {@link TeamStats#recentPPG()}.
     *
     * Se una partita recente non ha un avversario noto (es. dati sintetici
     * nei test, o avversario non presente in {@code allTeams}), usa
     * {@link #FORM_BASELINE_PPG} come atteso: in quel caso il risultato
     * coincide esattamente con {@link TeamStats#recentPPG()}.
     */
    private static double weightedRecentPPG(TeamStats team, Map<String, TeamStats> allTeams) {
        int n = team.recentCount();
        if (n == 0) return FORM_BASELINE_PPG;

        double totalPerformance = 0.0;
        for (int i = 0; i < n; i++) {
            int pts = team.recentPoints.get(i);
            String opponentKey = team.recentOpponents.get(i);

            double opponentPPG = FORM_BASELINE_PPG;
            if (opponentKey != null && !opponentKey.isEmpty()) {
                TeamStats opponent = allTeams.get(opponentKey);
                if (opponent != null && opponent.played > 0) {
                    opponentPPG = (double) opponent.points / opponent.played;
                }
            }

            double expected = clampDouble(
                    FORM_BASELINE_PPG - FORM_OPPONENT_ADJUSTMENT * (opponentPPG - FORM_BASELINE_PPG),
                    0.2, 2.6
            );
            totalPerformance += (pts - expected);
        }

        return clampDouble(FORM_BASELINE_PPG + totalPerformance / n, 0.0, 3.0);
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
