package it.paolo.pronosticicalcio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TeamStatsTest {

    @Test
    public void conservaDavveroLeOttoPartitePiuRecenti() {
        TeamStats stats = new TeamStats();

        // Inserimento cronologico: dalla partita più vecchia alla più recente.
        for (int i = 0; i < 12; i++) {
            stats.add(true, i, 0, i % 4, (double) i);
        }

        assertEquals(8, stats.recentCount());
        assertEquals(4.0, stats.recentOpponentPpgAtTime.get(0), 0.0001);
        assertEquals(11.0, stats.recentOpponentPpgAtTime.get(7), 0.0001);
    }

    @Test
    public void listePuntiEAvversariRestanoAllineate() {
        TeamStats stats = new TeamStats();
        for (int i = 0; i < 20; i++) {
            stats.add(i % 2 == 0, 1, 0, i % 3, (double) i);
        }

        assertEquals(stats.recentPoints.size(), stats.recentOpponentPpgAtTime.size());
        assertEquals(8, stats.recentPoints.size());
    }
}
