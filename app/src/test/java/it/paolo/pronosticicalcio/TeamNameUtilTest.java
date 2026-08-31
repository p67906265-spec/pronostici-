package it.paolo.pronosticicalcio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Test per TeamNameUtil.sameTeam: verifica sia i casi legittimi (nomi
 * abbreviati vs completi restituiti da API diverse) sia i falsi positivi
 * che il matching "contiene" ingenuo può creare quando un nome squadra
 * include il nome di una città come qualificatore (es. "Espanyol de
 * Barcelona" scambiato per "Barcelona").
 */
public class TeamNameUtilTest {

    @Test
    public void squadreDiverseConCittaCondivisaNonDevonoCombaciare() {
        // Bug reale osservato in app: la classifica evidenziava sia
        // "FC Barcelona" sia "RCD Espanyol de Barcelona" per una partita
        // del solo Barcellona, perché "barcelona" è contenuto per intero
        // nel nome dell'Espanyol (che gioca "de Barcelona", cioè nella
        // città di Barcellona, ma è un club diverso).
        assertFalse(TeamNameUtil.sameTeam("Barcelona", "RCD Espanyol de Barcelona"));
        assertFalse(TeamNameUtil.sameTeam("RCD Espanyol de Barcelona", "Barcelona"));
    }

    @Test
    public void nomeBreveEDenominazioneUfficialeCombacianoAncora() {
        assertTrue(TeamNameUtil.sameTeam("Barcelona", "FC Barcelona"));
        assertTrue(TeamNameUtil.sameTeam("Roma", "AS Roma"));
        assertTrue(TeamNameUtil.sameTeam("Inter", "FC Internazionale Milano"));
        assertTrue(TeamNameUtil.sameTeam("Getafe", "Getafe CF"));
        assertTrue(TeamNameUtil.sameTeam("Osasuna", "CA Osasuna"));
        assertTrue(TeamNameUtil.sameTeam("Deportivo", "RC Deportivo La Coruña"));
    }

    @Test
    public void connettivoNelNomeNonImpedisceIlMatchDelNomeCompleto() {
        // Il connettivo "de" fa parte anche di nomi legittimi dove
        // l'identità del club (non solo la città) precede il connettivo:
        // deve comunque combaciare quando il nome breve corrisponde alla
        // parte PRIMA del connettivo.
        assertTrue(TeamNameUtil.sameTeam("Real Sociedad", "Real Sociedad de Fútbol"));
    }

    @Test
    public void stringheVuoteONullNonCombacianoMai() {
        assertFalse(TeamNameUtil.sameTeam("", "Barcelona"));
        assertFalse(TeamNameUtil.sameTeam("Barcelona", ""));
        assertFalse(TeamNameUtil.sameTeam(null, "Barcelona"));
        assertFalse(TeamNameUtil.sameTeam("Barcelona", null));
    }

    @Test
    public void normalizeIgnoraAccentiMaiuscoleESiglePrefisso() {
        assertTrue(TeamNameUtil.normalize("AS Roma").equals(TeamNameUtil.normalize("Roma")));
        assertTrue(TeamNameUtil.normalize("Getafe CF").equals(TeamNameUtil.normalize("Getafe")));
        assertTrue(TeamNameUtil.normalize("Coruña").equals(TeamNameUtil.normalize("Coruna")));
    }
}
