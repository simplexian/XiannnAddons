package me.xiannn.addons;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized logging utility for XiannnAddons.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li><b>Consistent prefixes</b> — every message tagged with its
 *       source: {@code [Core]}, {@code [ChatReminder]}, etc.</li>
 *   <li><b>Global toggle</b> — master switch to suppress all
 *       non-error logs across the entire plugin.</li>
 *   <li><b>Per-source toggles</b> — silence individual modules
 *       without affecting others.</li>
 *   <li><b>Debug mode</b> — verbose output, gated behind its own
 *       flag on top of the source toggle.</li>
 *   <li><b>Errors always log</b> — {@link #error} is never
 *       suppressed, regardless of any toggle.</li>
 * </ul>
 *
 * <h3>Filtering hierarchy</h3>
 * <pre>
 *   error()  → ALWAYS logged (never suppressed)
 *   warn()   → logged if: globalEnabled AND sourceEnabled
 *   info()   → logged if: globalEnabled AND sourceEnabled
 *   debug()  → logged if: globalEnabled AND sourceEnabled AND debugEnabled
 * </pre>
 *
 * <h3>Config mapping</h3>
 * <pre>
 *   logging:
 *     enabled: true          → globalEnabled
 *     debug: false           → debugEnabled
 *     sources:
 *       Core: true           → sourceToggles["Core"]
 *       ChatReminder: true   → sourceToggles["ChatReminder"]
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * // In main class
 * AddonLogger log = new AddonLogger(this);              // source: "Core"
 *
 * // In a module
 * AddonLogger log = new AddonLogger(plugin, "ChatReminder");
 *
 * log.info("Loaded 10 tips.");         // filtered by global + source
 * log.warn("File missing.");           // filtered by global + source
 * log.error("Fatal!", exception);      // NEVER filtered
 * log.debug("Raw line: " + line);      // filtered by global + source + debug
 * </pre>
 */
public final class AddonLogger {

    private final Logger bukkitLogger;
    private final String prefix;
    private final String sourceName;

    /* ================================================================== */
    /*  Static state — shared across all logger instances                   */
    /* ================================================================== */

    /** Master switch — if false, only errors are logged. */
    private static volatile boolean globalEnabled = true;

    /** Debug switch — if false, debug() calls are skipped. */
    private static volatile boolean debugEnabled = false;

    /**
     * Per-source toggles. Key = source name (e.g. "Core",
     * "ChatReminder"). Value = enabled. Sources not present
     * in the map default to {@code true}.
     * <p>
     * ConcurrentHashMap for thread safety (scheduler threads).
     */
    private static final Map<String, Boolean> sourceToggles =
            new ConcurrentHashMap<>();

    /* ================================================================== */
    /*  Constructors                                                        */
    /* ================================================================== */

    /**
     * Creates a logger for the <b>main plugin</b>.
     * Source name: {@code "Core"}.
     *
     * @param plugin the main plugin instance
     */
    public AddonLogger(org.bukkit.plugin.java.JavaPlugin plugin) {
        this(plugin, "Core");
    }

    /**
     * Creates a logger for a <b>specific source</b> (module).
     *
     * @param plugin     the main plugin (provides underlying logger)
     * @param sourceName the source name used as prefix and toggle key
     */
    public AddonLogger(org.bukkit.plugin.java.JavaPlugin plugin,
                       String sourceName) {
        this.bukkitLogger = plugin.getLogger();
        this.sourceName   = (sourceName != null && !sourceName.isEmpty())
                ? sourceName : "Core";
        this.prefix = "[" + this.sourceName + "] ";
    }

    /* ================================================================== */
    /*  Log methods                                                         */
    /* ================================================================== */

    /**
     * Logs an <b>INFO</b> message.
     * Filtered by: global toggle + source toggle.
     */
    public void info(String message) {
        if (!shouldLog()) return;
        bukkitLogger.info(prefix + message);
    }

    /**
     * Logs a <b>WARNING</b> message.
     * Filtered by: global toggle + source toggle.
     */
    public void warn(String message) {
        if (!shouldLog()) return;
        bukkitLogger.warning(prefix + message);
    }

    /**
     * Logs a <b>WARNING</b> message with a throwable.
     * Filtered by: global toggle + source toggle.
     */
    public void warn(String message, Throwable throwable) {
        if (!shouldLog()) return;
        bukkitLogger.log(Level.WARNING, prefix + message, throwable);
    }

    /**
     * Logs a <b>SEVERE</b> (error) message.
     * <b>NEVER filtered</b> — always logged regardless of toggles.
     */
    public void error(String message) {
        bukkitLogger.severe(prefix + message);
    }

    /**
     * Logs a <b>SEVERE</b> (error) message with a throwable.
     * <b>NEVER filtered</b> — always logged regardless of toggles.
     */
    public void error(String message, Throwable throwable) {
        bukkitLogger.log(Level.SEVERE, prefix + message, throwable);
    }

    /**
     * Logs a <b>DEBUG</b> message.
     * Filtered by: global toggle + source toggle + debug toggle.
     */
    public void debug(String message) {
        if (!shouldLogDebug()) return;
        bukkitLogger.info(prefix + "[DEBUG] " + message);
    }

    /**
     * Logs a <b>DEBUG</b> message with a throwable.
     * Filtered by: global toggle + source toggle + debug toggle.
     */
    public void debug(String message, Throwable throwable) {
        if (!shouldLogDebug()) return;
        bukkitLogger.log(Level.INFO,
                prefix + "[DEBUG] " + message, throwable);
    }

    /* ================================================================== */
    /*  Convenience methods                                                 */
    /* ================================================================== */

    /**
     * Logs a visual divider line at INFO level.
     * Filtered by: global toggle + source toggle.
     */
    public void divider() {
        if (!shouldLog()) return;
        bukkitLogger.info(prefix
                + "────────────────────────────────────────");
    }

    /* ================================================================== */
    /*  Filtering logic                                                     */
    /* ================================================================== */

    /**
     * Checks whether INFO/WARN messages should be logged
     * for this source.
     *
     * @return true if global is ON and this source is ON
     */
    private boolean shouldLog() {
        if (!globalEnabled) return false;
        return sourceToggles.getOrDefault(sourceName, true);
    }

    /**
     * Checks whether DEBUG messages should be logged
     * for this source.
     *
     * @return true if global ON, source ON, and debug ON
     */
    private boolean shouldLogDebug() {
        if (!globalEnabled) return false;
        if (!debugEnabled) return false;
        return sourceToggles.getOrDefault(sourceName, true);
    }

    /* ================================================================== */
    /*  Static toggle controls                                              */
    /* ================================================================== */

    // ── Global ──

    /**
     * Sets the master logging switch.
     * When false, only {@link #error} calls produce output.
     */
    public static void setGlobalEnabled(boolean enabled) {
        globalEnabled = enabled;
    }

    /** @return true if global logging is enabled */
    public static boolean isGlobalEnabled() {
        return globalEnabled;
    }

    // ── Debug ──

    /**
     * Sets the debug logging switch.
     * Debug messages require global + source + debug all ON.
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /** @return true if debug logging is enabled */
    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    // ── Per-source ──

    /**
     * Sets the logging toggle for a specific source.
     *
     * @param source the source name (e.g. "Core", "ChatReminder")
     * @param enabled true to enable logging for this source
     */
    public static void setSourceEnabled(String source,
                                        boolean enabled) {
        sourceToggles.put(source, enabled);
    }

    /**
     * @param source the source name
     * @return true if this source is enabled (defaults to true
     *         if not explicitly set)
     */
    public static boolean isSourceEnabled(String source) {
        return sourceToggles.getOrDefault(source, true);
    }

    /**
     * Returns an unmodifiable view of all registered source toggles.
     *
     * @return map of source name → enabled
     */
    public static Map<String, Boolean> getSourceToggles() {
        return Collections.unmodifiableMap(sourceToggles);
    }

    /**
     * Returns the set of all registered source names.
     *
     * @return set of source names
     */
    public static Set<String> getSourceNames() {
        return Collections.unmodifiableSet(sourceToggles.keySet());
    }

    /**
     * Registers a source with a default toggle value.
     * Does NOT overwrite if the source already exists
     * (preserves user config).
     *
     * @param source the source name
     * @param defaultEnabled default toggle value
     */
    public static void registerSource(String source,
                                      boolean defaultEnabled) {
        sourceToggles.putIfAbsent(source, defaultEnabled);
    }

    /**
     * Clears all source toggles. Called on plugin disable.
     */
    public static void clearSources() {
        sourceToggles.clear();
    }

    /* ================================================================== */
    /*  Instance info                                                        */
    /* ================================================================== */

    /**
     * @return the source name this logger instance is bound to
     */
    public String getSourceName() {
        return sourceName;
    }
}
