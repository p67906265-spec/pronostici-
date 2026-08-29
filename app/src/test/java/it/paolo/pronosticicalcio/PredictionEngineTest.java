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

    @Test
    public void shrinkageAttenuaUnaVittoriaEclatanteConUnaSolaPartita() {
        // Una sola partita vinta 5-0 e' un campione troppo piccolo per
        // essere preso "a valore pieno": lo shrinkage bayesiano deve
        // attenuare la stima verso la media di lega, dando una confidenza
        // piu bassa rispetto a una squadra che vince sistematicamente 3-0
        // su 12 partite (stesso verso, molti piu dati).
        TeamStats oneGameHome = new TeamStats();
        oneGameHome.add(true, 5, 0, 3);
        TeamStats oneGameAway = new TeamStats();
        oneGameAway.add(false, 0, 5, 0);

        Map<Integer, TeamStats> smallSample = new HashMap<>();
        smallSample.put(1, oneGameHome);
        smallSample.put(2, oneGameAway);
        MatchPrediction mSmall = newMatch(1, 2, "Forte1game", "Debole1game");
        PredictionEngine.calculate(mSmall, smallSample, 1);

        Map<Integer, TeamStats> bigSample = new HashMap<>();
        bigSample.put(1, strongTeam());
        bigSample.put(2, weakTeam());
        MatchPrediction mBig = newMatch(1, 2, "Forte12games", "Debole12games");
        PredictionEngine.calculate(mBig, bigSample, 60);

        assertTrue("Con 1 sola partita il p1 deve essere piu prudente (piu basso) che con 12: "
                        + "1 partita p1=" + mSmall.p1 + ", 12 partite p1=" + mBig.p1,
                mSmall.p1 < mBig.p1);
        assertTrue("Con dati scarsi l'analisi deve segnalarlo",
                mSmall.analysis.contains("dati storici ancora scarsi"));
    }

    @Test
    public void correzioneDixonColesNonRompeLaNormalizzazione() {
        // La correzione sui risultati bassi (0-0/1-0/0-1/1-1) sposta
        // probabilita' tra le celle della matrice, ma il risultato finale
        // deve restare comunque una distribuzione valida (somma 100,
        // nessun valore negativo).
        Map<Integer, TeamStats> history = new HashMap<>();
        history.put(1, averageTeam());
        history.put(2, averageTeam());

        MatchPrediction m = newMatch(1, 2, "Casa", "Trasferta");
        PredictionEngine.calculate(m, history, 60);

        assertEquals(100, m.p1 + m.px + m.p2);
        assertTrue(m.p1 >= 0 && m.px >= 0 && m.p2 >= 0);
        assertTrue(m.analysis.contains("Dixon-Coles"));
    }
}
