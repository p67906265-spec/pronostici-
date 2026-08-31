package it.paolo.pronosticicalcio;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalizzazione dei nomi squadra, per far combaciare i nomi restituiti da
 * API diverse (es. "AS Roma" su un'API, "Roma" su un'altra). Classe pura,
 * senza dipendenze Android: usata sia da MainActivity (matching squadra su
 * football-data.org) sia da PredictionEngine (chiave della mappa storico).
 */
final class TeamNameUtil {

    private TeamNameUtil() {
    }

    static boolean sameTeam(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        if (na.equals(nb)) return true;

        // Per il confronto "uno contiene l'altro" usiamo solo la parte del
        // nome PRIMA di connettivi come "de"/"del"/"della"/"of"/"van": in
        // nomi come "RCD Espanyol de Barcelona" o "Real Sociedad de Fútbol"
        // l'identità del club sta nella parte iniziale, non nella città o
        // nello sport dopo il connettivo. Senza questo taglio, "Barcelona"
        // combaciava erroneamente con l'Espanyol solo perché gioca
        // "de Barcelona" (di Barcellona, come città).
        String coreA = coreName(na);
        String coreB = coreName(nb);
        if (coreA.isEmpty() || coreB.isEmpty()) return false;
        return coreA.equals(coreB) || coreA.contains(coreB) || coreB.contains(coreA);
    }

    private static final String[] NAME_CONNECTORS = {
            " de ", " del ", " della ", " di ", " of ", " van ", " von "
    };

    /**
     * Parte del nome normalizzato prima del primo connettivo tipo "de"/
     * "of"/... (es. "rcd espanyol de barcelona" -> "rcd espanyol"). Se non
     * c'è nessun connettivo, restituisce il nome intero invariato.
     */
    private static String coreName(String normalized) {
        for (String connector : NAME_CONNECTORS) {
            int idx = normalized.indexOf(connector);
            if (idx > 0) return normalized.substring(0, idx).trim();
        }
        return normalized;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace("football club", " ")
                .replace("calcio", " ")
                .replaceAll("\\b(fc|ac|ssc|ss|as|us|cf|bc)\\b", " ")
                .replaceAll("\\b(1907|1909|1913|1927)\\b", " ")
                .replaceAll("[^a-z0-9]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
