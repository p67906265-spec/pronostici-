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
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
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
