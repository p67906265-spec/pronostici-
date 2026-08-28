package it.paolo.pronosticicalcio;

/**
 * Modello dati di una partita con il relativo pronostico calcolato.
 * Popolato da MainActivity a partire dai dati API-Football, poi
 * arricchito da PredictionEngine con le percentuali del modello.
 */
public class MatchPrediction {
    int fixtureId;
    int leagueId;
    int homeId;
    int awayId;
    int p1;
    int px;
    int p2;
    int goal;
    int over25;
    int confidence;
    String league;
    String time;
    String home;
    String away;
    String pick;
    String analysis;
    String score;
    String predicted1x2;
    int finalHomeGoals = -1;
    int finalAwayGoals = -1;
    boolean finished;
}
