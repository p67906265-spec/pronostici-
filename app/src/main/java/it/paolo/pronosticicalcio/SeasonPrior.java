package it.paolo.pronosticicalcio;

/**
 * Rendimento medio di una squadra nella stagione passata (gol fatti/subiti
 * a partita, punti a partita), ricavato dalla classifica finale reale su
 * football-data.org. Usato come "prior" informato per lo shrinkage
 * bayesiano in PredictionEngine, al posto di una media di lega generica
 * uguale per tutte le squadre: una squadra con un buon curriculum recente
 * parte da una base più alta, una neopromossa (senza classifica nella
 * massima serie l'anno prima) resta sulla media di lega di default.
 */
public class SeasonPrior {
    final double avgGF;
    final double avgGA;
    final double ppg;

    SeasonPrior(double avgGF, double avgGA, double ppg) {
        this.avgGF = avgGF;
        this.avgGA = avgGA;
        this.ppg = ppg;
    }
}
