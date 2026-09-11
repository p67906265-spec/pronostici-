package it.paolo.pronosticicalcio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

/** Archivio permanente delle partite: non viene eliminato con la cache HTTP. */
public class MatchHistoryDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "pronostici_storico.db";
    private static final int DB_VERSION = 1;

    MatchHistoryDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE matches ("
                + "source_key TEXT PRIMARY KEY, fixture_id INTEGER, match_date TEXT NOT NULL, "
                + "league_id INTEGER, league TEXT, home_id INTEGER, away_id INTEGER, "
                + "home_name TEXT NOT NULL, away_name TEXT NOT NULL, "
                + "home_key TEXT NOT NULL, away_key TEXT NOT NULL, "
                + "home_goals INTEGER, away_goals INTEGER, finished INTEGER NOT NULL DEFAULT 0, "
                + "p1 INTEGER, px INTEGER, p2 INTEGER, goal_probability INTEGER, "
                + "over15_probability INTEGER, over25_probability INTEGER, saved_at INTEGER)");
        db.execSQL("CREATE INDEX idx_matches_teams_date ON matches(home_key, away_key, match_date)");
        db.execSQL("CREATE INDEX idx_matches_date ON matches(match_date)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Prima versione dello schema.
    }

    void upsert(String sourceKey, MatchPrediction m, String date) {
        ContentValues v = baseValues(sourceKey, m.fixtureId, date, m.leagueId, m.league,
                m.homeId, m.awayId, m.home, m.away);
        v.put("finished", m.finished ? 1 : 0);
        if (m.finalHomeGoals >= 0 && m.finalAwayGoals >= 0) {
            v.put("home_goals", m.finalHomeGoals);
            v.put("away_goals", m.finalAwayGoals);
        }
        if (m.confidence > 0) {
            v.put("p1", m.p1); v.put("px", m.px); v.put("p2", m.p2);
            v.put("goal_probability", m.goal);
            v.put("over15_probability", m.over15);
            v.put("over25_probability", m.over25);
        }
        getWritableDatabase().insertWithOnConflict("matches", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void upsertHistorical(String sourceKey, int fixtureId, String date, int leagueId,
                          String league, int homeId, int awayId, String home, String away,
                          int homeGoals, int awayGoals) {
        ContentValues v = baseValues(sourceKey, fixtureId, date, leagueId, league,
                homeId, awayId, home, away);
        v.put("home_goals", homeGoals);
        v.put("away_goals", awayGoals);
        v.put("finished", 1);
        getWritableDatabase().insertWithOnConflict("matches", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private ContentValues baseValues(String sourceKey, int fixtureId, String date, int leagueId,
                                     String league, int homeId, int awayId, String home, String away) {
        ContentValues v = new ContentValues();
        v.put("source_key", sourceKey);
        v.put("fixture_id", fixtureId);
        v.put("match_date", date);
        v.put("league_id", leagueId);
        v.put("league", league);
        v.put("home_id", homeId);
        v.put("away_id", awayId);
        v.put("home_name", home);
        v.put("away_name", away);
        v.put("home_key", TeamNameUtil.normalize(home));
        v.put("away_key", TeamNameUtil.normalize(away));
        v.put("saved_at", System.currentTimeMillis());
        return v;
    }

    HeadToHeadStats headToHead(String currentHome, String currentAway, String beforeDate) {
        String homeKey = TeamNameUtil.normalize(currentHome);
        String awayKey = TeamNameUtil.normalize(currentAway);
        HeadToHeadStats stats = new HeadToHeadStats();
        String sql = "SELECT home_key, home_goals, away_goals FROM matches "
                + "WHERE finished=1 AND home_goals IS NOT NULL AND away_goals IS NOT NULL "
                + "AND match_date < ? AND match_date >= date(?, '-5 years') "
                + "AND ((home_key=? AND away_key=?) OR (home_key=? AND away_key=?)) "
                + "GROUP BY match_date, home_key, away_key, home_goals, away_goals "
                + "ORDER BY match_date DESC LIMIT 20";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{beforeDate, beforeDate, homeKey, awayKey, awayKey, homeKey})) {
            while (c.moveToNext()) {
                boolean sameVenueOrder = homeKey.equals(c.getString(0));
                int gh = c.getInt(1);
                int ga = c.getInt(2);
                stats.add(sameVenueOrder ? gh : ga, sameVenueOrder ? ga : gh);
            }
        }
        return stats;
    }

    int finishedCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM matches WHERE finished=1 "
                        + "GROUP BY match_date, home_key, away_key, home_goals, away_goals)", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Calcola un rating Elo autonomo scorrendo cronologicamente lo storico. */
    Map<String, Double> calculateEloRatings(String beforeDate) {
        Map<String, Double> ratings = new HashMap<>();
        String sql = "SELECT home_key, away_key, home_goals, away_goals FROM matches "
                + "WHERE finished=1 AND home_goals IS NOT NULL AND away_goals IS NOT NULL "
                + "AND match_date < ? AND match_date >= date(?, '-5 years') "
                + "GROUP BY match_date, home_key, away_key, home_goals, away_goals "
                + "ORDER BY match_date ASC";
        try (Cursor c = getReadableDatabase().rawQuery(sql,
                new String[]{beforeDate, beforeDate})) {
            while (c.moveToNext()) {
                String home = c.getString(0);
                String away = c.getString(1);
                int gh = c.getInt(2);
                int ga = c.getInt(3);
                double homeRating = ratings.containsKey(home) ? ratings.get(home) : 1500.0;
                double awayRating = ratings.containsKey(away) ? ratings.get(away) : 1500.0;
                double expectedHome = 1.0 / (1.0
                        + Math.pow(10.0, (awayRating - (homeRating + 65.0)) / 400.0));
                double actualHome = gh > ga ? 1.0 : (gh == ga ? 0.5 : 0.0);
                double change = 20.0 * (actualHome - expectedHome);
                ratings.put(home, homeRating + change);
                ratings.put(away, awayRating - change);
            }
        }
        return ratings;
    }
}
