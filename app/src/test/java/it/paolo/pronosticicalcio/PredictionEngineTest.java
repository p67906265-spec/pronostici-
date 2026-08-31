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
 *
 * Lo storico è ora chiave per nome squadra normalizzato (TeamNameUtil),
 * non per ID numerico: rispecchia il fatto che PredictionEngine riceve
 * un Map<String, TeamStats> costruito da football-data.org.
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

    private MatchPrediction newMatch(String home, String away) {
        MatchPrediction m = new MatchPrediction();
        m.home = home;
        m.away = away;
        return m;
    }

    private void put(Map<String, TeamStats> history, String teamName, TeamStats stats) {
        history.put(TeamNameUtil.normalize(teamName), stats);
    }

    @Test
    public void probabilitaSommanoSempreCento() {
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "Forte", strongTeam());
        put(history, "Debole", weakTeam());

        MatchPrediction m = newMatch("Forte", "Debole");
        PredictionEngine.calculate(m, history, 60);

        assertEquals(100, m.p1 + m.px + m.p2);
    }

    @Test
    public void squadraFortiInCasaControDeboleVinceProbabilmente() {
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "Forte", strongTeam());
        put(history, "Debole", weakTeam());

        MatchPrediction m = newMatch("Forte", "Debole");
        PredictionEngine.calculate(m, history, 60);

        assertTrue("p1 dovrebbe essere il piu alto: p1=" + m.p1 + " px=" + m.px + " p2=" + m.p2,
                m.p1 > m.px && m.p1 > m.p2);
        assertEquals("1", m.predicted1x2);
        assertTrue("Con un pronostico cosi netto il pick dovrebbe essere secco, non doppia chance",
                m.pick.startsWith("Vittoria"));
    }

    @Test
    public void squadreEquilibrateDannoUnPronosticoIncerto() {
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "Casa", averageTeam());
        put(history, "Trasferta", averageTeam());

        MatchPrediction m = newMatch("Casa", "Trasferta");
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
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "Forte1", strongTeam());
        put(history, "Forte2", strongTeam());

        MatchPrediction m = newMatch("Forte1", "Forte2");
        PredictionEngine.calculate(m, history, 60);

        assertTrue(m.goal >= 5 && m.goal <= 95);
        assertTrue(m.over25 >= 5 && m.over25 <= 95);
    }

    @Test
    public void squadreSenzaStoricoNonFannoCrashareIlCalcolo() {
        // Nessuna voce nella mappa: PredictionEngine deve usare i valori
        // di default di TeamStats invece di lanciare un'eccezione.
        Map<String, TeamStats> history = new HashMap<>();

        MatchPrediction m = newMatch("Sconosciuta1", "Sconosciuta2");
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

        Map<String, TeamStats> smallSample = new HashMap<>();
        put(smallSample, "Forte1game", oneGameHome);
        put(smallSample, "Debole1game", oneGameAway);
        MatchPrediction mSmall = newMatch("Forte1game", "Debole1game");
        PredictionEngine.calculate(mSmall, smallSample, 1);

        Map<String, TeamStats> bigSample = new HashMap<>();
        put(bigSample, "Forte12games", strongTeam());
        put(bigSample, "Debole12games", weakTeam());
        MatchPrediction mBig = newMatch("Forte12games", "Debole12games");
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
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "Casa", averageTeam());
        put(history, "Trasferta", averageTeam());

        MatchPrediction m = newMatch("Casa", "Trasferta");
        PredictionEngine.calculate(m, history, 60);

        assertEquals(100, m.p1 + m.px + m.p2);
        assertTrue(m.p1 >= 0 && m.px >= 0 && m.p2 >= 0);
        assertTrue(m.analysis.contains("Dixon-Coles"));
    }

    @Test
    public void ilMatchingPerNomeIgnoraAccentiEDenominazioniDiverse() {
        // Stessa squadra, nomi scritti diversamente tra le due API
        // (es. "AS Roma" su una fonte, "Roma" sull'altra): devono
        // combaciare tramite TeamNameUtil invece di restare "senza dati".
        Map<String, TeamStats> history = new HashMap<>();
        put(history, "AS Roma", strongTeam());
        put(history, "Getafe CF", weakTeam());

        MatchPrediction m = newMatch("Roma", "Getafe");
        PredictionEngine.calculate(m, history, 60);

        assertTrue("Il nome abbreviato dovrebbe comunque trovare lo storico della squadra forte",
                m.p1 > m.px && m.p1 > m.p2);
    }

    @Test
    public void formaRecenteContaDiPiuSeOttenutaControUnAvversarioForte() {
        // Stesso identico record (3 vittorie 3-0 nelle ultime partite), ma
        // in uno scenario l'avversario incontrato era forte (PPG alto) e
        // nell'altro era debole (PPG basso). A parita' di tutto il resto,
        // il pronostico deve dare piu' fiducia a chi ha ottenuto quel
        // record contro un avversario piu' difficile.
        TeamStats forte = new TeamStats();
        for (int i = 0; i < 9; i++) forte.add(true, 2, 0, 3);
        forte.add(true, 0, 2, 0);

        TeamStats squadraA = new TeamStats();
        for (int i = 0; i < 3; i++) {
            squadraA.add(true, 3, 0, 3, TeamNameUtil.normalize("Forte"));
        }

        TeamStats debole = new TeamStats();
        debole.add(true, 0, 2, 3);
        for (int i = 0; i < 9; i++) debole.add(true, 0, 2, 0);

        TeamStats squadraB = new TeamStats();
        for (int i = 0; i < 3; i++) {
            squadraB.add(true, 3, 0, 3, TeamNameUtil.normalize("Debole"));
        }

        Map<String, TeamStats> historyA = new HashMap<>();
        put(historyA, "SquadraA", squadraA);
        put(historyA, "Forte", forte);
        MatchPrediction mA = newMatch("SquadraA", "AvversarioSenzaStorico");
        PredictionEngine.calculate(mA, historyA, 60);

        Map<String, TeamStats> historyB = new HashMap<>();
        put(historyB, "SquadraB", squadraB);
        put(historyB, "Debole", debole);
        MatchPrediction mB = newMatch("SquadraB", "AvversarioSenzaStorico");
        PredictionEngine.calculate(mB, historyB, 60);

        assertTrue("Stesso record (3 vittorie 3-0), ma vs avversario forte deve dare p1 piu alto: "
                        + "vs forte p1=" + mA.p1 + ", vs debole p1=" + mB.p1,
                mA.p1 > mB.p1);
    }
}
