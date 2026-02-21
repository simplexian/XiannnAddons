package me.xiannn.addons.modules.staff;

import me.xiannn.addons.AddonLogger;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Handles SQLite database connection and schema creation for the Staff Module.
 */
public class StaffDatabase {

    private final StaffModule module;
    private final AddonLogger log;
    private final File dbFile;
    private Connection connection;

    public StaffDatabase(StaffModule module) {
        this.module = module;
        this.log = module.getLog();
        this.dbFile = new File(module.getPlugin().getModuleFolder(module), "staff.db");
    }

    public void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            log.info("Connected to SQLite database.");
            initTables();
        } catch (Exception e) {
            log.error("Failed to connect to database!", e);
        }
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                log.info("Database connection closed.");
            } catch (SQLException e) {
                log.error("Error closing database", e);
            }
        }
    }

    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        return connection;
    }

    private void initTables() {
        String[] queries = {
            // Player cache
            "CREATE TABLE IF NOT EXISTS players (" +
            "uuid TEXT PRIMARY KEY, " +
            "name TEXT NOT NULL, " +
            "ip TEXT, " +
            "first_join INTEGER NOT NULL, " +
            "last_join INTEGER NOT NULL)",

            // Bans
            "CREATE TABLE IF NOT EXISTS bans (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT, " +
            "ip TEXT, " +
            "ip_range TEXT, " +
            "staff_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "type TEXT NOT NULL, " +
            "created INTEGER NOT NULL, " +
            "expires INTEGER, " +
            "active INTEGER DEFAULT 1, " +
            "unban_staff TEXT, " +
            "unban_reason TEXT, " +
            "appeal_id TEXT)",

            // Mutes
            "CREATE TABLE IF NOT EXISTS mutes (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT, " +
            "ip TEXT, " +
            "staff_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "type TEXT NOT NULL, " +
            "created INTEGER NOT NULL, " +
            "expires INTEGER, " +
            "active INTEGER DEFAULT 1, " +
            "unmute_staff TEXT, " +
            "unmute_reason TEXT)",

            // Kicks
            "CREATE TABLE IF NOT EXISTS kicks (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT NOT NULL, " +
            "staff_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "created INTEGER NOT NULL)",

            // Warnings
            "CREATE TABLE IF NOT EXISTS warnings (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT NOT NULL, " +
            "staff_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "type TEXT NOT NULL, " +
            "created INTEGER NOT NULL, " +
            "expires INTEGER, " +
            "active INTEGER DEFAULT 1)",

            // Notes
            "CREATE TABLE IF NOT EXISTS notes (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT NOT NULL, " +
            "staff_uuid TEXT NOT NULL, " +
            "message TEXT NOT NULL, " +
            "created INTEGER NOT NULL)",

            // Reports
            "CREATE TABLE IF NOT EXISTS reports (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "reporter_uuid TEXT NOT NULL, " +
            "reported_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "status TEXT DEFAULT 'OPEN', " +
            "assigned_to TEXT, " +
            "reporter_loc TEXT, " +
            "reported_loc TEXT, " +
            "created INTEGER NOT NULL, " +
            "closed_at INTEGER, " +
            "close_comment TEXT)",

            // GameMode Log
            "CREATE TABLE IF NOT EXISTS gamemode_log (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT NOT NULL, " +
            "from_mode TEXT NOT NULL, " +
            "to_mode TEXT NOT NULL, " +
            "world TEXT NOT NULL, " +
            "created INTEGER NOT NULL)",

            // Command Log
            "CREATE TABLE IF NOT EXISTS command_log (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "player_uuid TEXT NOT NULL, " +
            "command TEXT NOT NULL, " +
            "world TEXT NOT NULL, " +
            "created INTEGER NOT NULL)",

            // Name Bans
            "CREATE TABLE IF NOT EXISTS name_bans (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "name TEXT NOT NULL, " +
            "staff_uuid TEXT NOT NULL, " +
            "reason TEXT NOT NULL, " +
            "type TEXT NOT NULL, " +
            "created INTEGER NOT NULL, " +
            "expires INTEGER, " +
            "active INTEGER DEFAULT 1)"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String query : queries) {
                stmt.addBatch(query);
            }
            stmt.executeBatch();
            log.debug("Database tables initialized.");
        } catch (SQLException e) {
            log.error("Failed to initialize database tables!", e);
        }
    }

    // ── Helper Methods ───────────────────────────────────────────────

    /**
     * Updates player cache on join
     */
    public void updatePlayer(String uuid, String name, String ip) {
        String sql = "INSERT OR REPLACE INTO players (uuid, name, ip, first_join, last_join) " +
                     "VALUES (?, ?, ?, " +
                     "COALESCE((SELECT first_join FROM players WHERE uuid=?), ?), ?)";
        long now = System.currentTimeMillis();
        
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, ip);
            ps.setString(4, uuid); // for subquery
            ps.setLong(5, now);    // for COALESCE default
            ps.setLong(6, now);    // last_join
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to update player cache for " + name, e);
        }
    }
    
    /**
     * Get player name from UUID (offline lookup)
     */
    public String getPlayerName(String uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM players WHERE uuid=?")) {
            ps.setString(1, uuid);
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getString("name");
        } catch (SQLException e) {
            log.warn("Error looking up name for " + uuid, e);
        }
        return "Unknown";
    }
}
