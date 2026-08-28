package it.paolo.pronosticicalcio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Test unitari "puri" (JVM, niente Android/emulatore) per PredictionEngine.
 * Costruiscono a mano un piccolo storico (TeamStats) e verificano che il
 * calcolo produca risultati coerenti, senza controllare i numeri esatti
 * del modello (che potranno cambiare) ma le sue proprietà invarianti.
 */
public class PredictionEngineTest {

    /** Squadra che vince quasi sempre 3-0, sia in casa che in trasferta. */
    private TeamStats strongTeam() {
        TeamStats s = new TeamStats();
        for (int i = 0; i < 6; i++) s.add(true, 3, 0, 3);
        for (int i = 0; i < 6; i++) s.add(false, 3, 0, 3);
        return s;
    }

    /** Squadra che perde quasi sempre 0-3, sia in casa che in trasferta. */
    private TeamStats weakTeam() {
        TeamStats s = new TeamStats();
        for (int i = 0; i < 6; i++) s.add(true, 0, 3, 0);
        for (int i = 0; i < 6; i++) s.add(false, 0, 3, 0);
        return s;
    }

    /** Squadra "nella media": pareggia sempre 1-1. */
    private TeamStats averageTeam() {
        TeamStats s = new TeamStats();
        for (int i = 0; i < 6; i++) s.add(true, 1, 1, 1);
        for (int i = 0; i < 6; i++) s.add(false, 1, 1, 1);
        return s;
    }

    private MatchPrediction newMatch(int homeId, int awayId, String home, String away) {
        MatchPrediction m = new MatchPrediction();
        m.homeId = homeId;
        m.awayId = awayId;
        m.home = home;
        m.away = away;
        return m;
    }

    @Test
    public void probabilitaSommanoSempreCento() {
        Map<Integer, TeamStats> history = new HashMap<>();
        history.put(1, strongTeam());
        history.put(2, weakTeam());

        MatchPrediction m = newMatch(1, 2, "Forte", "Debole");
        PredictionEngine.calculate(m, history, 60);

        assertEquals(100, m.p1 + m.px + m.p2);
    }

    @Test
    public void squadraFortiInCasaControDeboleVinceProbabilmente() {
        Map<Integer, TeamStats> history = new HashMap<>();
        history.put(1, strongTeam());
        history.put(2, weakTeam());

        MatchPrediction m = newMatch(1, 2, "Forte", "Debole");
        PredictionEngine.calculate(m, history, 60);

        assertTrue("p1 dovrebbe essere il piu alto: p1=" + m.p1 + " px=" + m.px + " p2=" + m.p2,
                m.p1 > m.px && m.p1 > m.p2);
        assertEquals("1", m.predicted1x2);
        assertTrue("Con un pronostico cosi netto il pick dovrebbe essere secco, non doppia chance",
                m.pick.startsWith("Vittoria"));
    }

    @Test
    public void squadreEquilibrateDannoUnPronosticoIncerto() {
        Map<Integer, TeamStats> history = new HashMap<>();
        history.put(1, averageTeam());
        history.put(2, averageTeam());

        MatchPrediction m = newMatch(1, 2, "Casa", "Trasferta");
        PredictionEngine.calculate(m, history, 60);

        // Con due squadre identiche nessun esito deve avere una probabilita
        // schiacciante: la confidenza resta sotto la soglia del pronostico secco.
        assertTrue("confidence troppo alta per squadre equivalenti: " + m.confidence,
                m.confidence < 58);
        assertTrue("con confidenza bassa il pick dovrebbe essere una doppia chance",
                m.pick.startsWith("Doppia chance"));
    }

    @Test
    public void goalEOver25RestanoNelRangeConsentito() {
        Map<Integer, TeamStats> history = new HashMap<>();
        history.put(1, strongTeam());
        history.put(2, strongTeam());

        MatchPrediction m = newMatch(1, 2, "Forte1", "Forte2");
        PredictionEngine.calculate(m, history, 60);

        assertTrue(m.goal >= 5 && m.goal <= 95);
        assertTrue(m.over25 >= 5 && m.over25 <= 95);
    }

    @Test
    public void squadreSenzaStoricoNonFannoCrashareIlCalcolo() {
        // Nessuna voce nella mappa: PredictionEngine deve usare i valori
        // di default di TeamStats invece di lanciare un'eccezione.
        Map<Integer, TeamStats> history = new HashMap<>();

        MatchPrediction m = newMatch(1, 2, "Sconosciuta1", "Sconosciuta2");
        PredictionEngine.calculate(m, history, 0);

        assertEquals(100, m.p1 + m.px + m.p2);
        assertTrue(m.analysis.contains("archivio locale 0/" + PredictionEngine.MODEL_HISTORY_DAYS));
    }
}
