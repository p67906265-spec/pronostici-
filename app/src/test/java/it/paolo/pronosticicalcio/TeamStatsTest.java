package it.paolo.pronosticicalcio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TeamStatsTest {

    @Test
    public void conservaDavveroLeOttoPartitePiuRecenti() {
        TeamStats stats = new TeamStats();

        // Inserimento cronologico: dalla partita più vecchia alla più recente.
        for (int i = 0; i < 12; i++) {
            stats.add(true, i, 0, i % 4, "avversario-" + i);
        }

        assertEquals(8, stats.recentCount());
        assertEquals("avversario-4", stats.recentOpponents.get(0));
        assertEquals("avversario-11", stats.recentOpponents.get(7));
    }

    @Test
    public void listePuntiEAvversariRestanoAllineate() {
        TeamStats stats = new TeamStats();
        for (int i = 0; i < 20; i++) {
            stats.add(i % 2 == 0, 1, 0, i % 3, "team-" + i);
        }

        assertEquals(stats.recentPoints.size(), stats.recentOpponents.size());
        assertEquals(8, stats.recentPoints.size());
    }
}
