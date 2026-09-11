package it.paolo.pronosticicalcio;

/** Regole pure e testabili per confrontare pronostici e risultati finali. */
final class PredictionEvaluation {

    private PredictionEvaluation() {
    }

    static String actual1x2(int homeGoals, int awayGoals) {
        if (homeGoals > awayGoals) return "1";
        if (homeGoals < awayGoals) return "2";
        return "X";
    }

    static String actualGoal(int homeGoals, int awayGoals) {
        return homeGoals > 0 && awayGoals > 0 ? "GOAL" : "NO GOAL";
    }

    static String actualOver25(int homeGoals, int awayGoals) {
        return homeGoals + awayGoals >= 3 ? "OVER 2,5" : "UNDER 2,5";
    }

    static String actualOver15(int homeGoals, int awayGoals) {
        return homeGoals + awayGoals >= 2 ? "OVER 1,5" : "UNDER 1,5";
    }
}
