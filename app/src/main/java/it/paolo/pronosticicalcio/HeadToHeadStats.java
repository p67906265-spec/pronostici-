package it.paolo.pronosticicalcio;

/** Statistiche degli scontri diretti viste rispetto alla partita corrente. */
public class HeadToHeadStats {
    int played;
    int homeWins;
    int draws;
    int awayWins;

    void add(int homeGoals, int awayGoals) {
        played++;
        if (homeGoals > awayGoals) homeWins++;
        else if (homeGoals < awayGoals) awayWins++;
        else draws++;
    }
}
