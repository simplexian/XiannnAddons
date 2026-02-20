package me.xiannn.addons.modules;

import me.xiannn.addons.AddonLogger;
import me.xiannn.addons.AddonModule;
import me.xiannn.addons.XiannnAddons;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Broadcasts configurable tip messages on a timer.
 *
 * <h3>Performance optimizations</h3>
 * <ol>
 *   <li><b>Pre-parsed messages</b> — all formatting (Legacy, Hex,
 *       MiniMessage) is done once at load time. Broadcast sends
 *       pre-built {@link Component} objects with zero parsing.</li>
 *   <li><b>Cached eligible players</b> — a {@link Set} of UUIDs
 *       tracks who has tips enabled. Updated only on join/quit/toggle,
 *       not on every broadcast.</li>
 *   <li><b>Lazy save with dirty flag</b> — {@code players.yml} is
 *       only written to disk when a toggle actually changes, not on
 *       every join or broadcast.</li>
 * </ol>
 *
 * <h3>File layout</h3>
 * <pre>
 * plugins/XiannnAddons/ChatReminder/
 * ├── config.yml          — delay, order, sound settings
 * ├── players.yml         — per-player toggle persistence
 * └── messages/           — one .yml file per tip
 *     ├── tip_1.yml
 *     ├── tip_2.yml
 *     └── ...
 * </pre>
 */
public final class ChatReminderModule implements AddonModule, Listener {

    private final XiannnAddons plugin;
    private final AddonLogger  log;
    private final MiniMessage  miniMessage = MiniMessage.miniMessage();

    /* ================================================================== */
    /*  Configuration fields (from config.yml)                             */
    /* ================================================================== */

    private int     delaySeconds   = 300;
    private boolean randomOrder    = false;
    private boolean soundEnabled   = true;
    private Key     soundKey       = Key.key("block.note_block.chime");
    private float   soundVolume    = 0.5F;
    private float   soundPitch     = 1.2F;
    private boolean defaultEnabled = true;

    /* ================================================================== */
    /*  Pre-parsed messages                                                 */
    /*  Optimization #1: parse once at load, not on every broadcast.       */
    /* ================================================================== */

    /**
     * Each entry is one tip — stored as a list of pre-parsed
     * {@link Component} objects. Zero formatting work at broadcast time.
     */
    private List<List<Component>> parsedMessages = new ArrayList<>();

    /**
     * Raw strings kept only for debug logging and reload feedback.
     * Never used at broadcast time.
     */
    private List<List<String>> rawMessagesDebug = new ArrayList<>();

    /* ================================================================== */
    /*  Cached eligible players                                             */
    /*  Optimization #2: maintained on join/quit/toggle only.              */
    /* ================================================================== */

    /**
     * UUIDs of all online players who have tips <b>enabled</b>.
     * Updated by: {@link #onPlayerJoin}, {@link #onPlayerQuit},
     * {@link #handleToggle}, {@link #rebuildEligibleCache}.
     * <p>
     * At broadcast time, the loop iterates only this set — no
     * HashMap lookups or full-player-list scans.
     */
    private final Set<UUID> eligiblePlayers = new HashSet<>();

    /* ================================================================== */
    /*  Player toggle persistence                                           */
    /*  Optimization #3: dirty flag — only save when actually changed.     */
    /* ================================================================== */

    private final Map<UUID, Boolean> playerToggles = new HashMap<>();

    /** True when toggles have changed since last save. */
    private volatile boolean togglesDirty = false;

    /* ================================================================== */
    /*  Runtime state                                                       */
    /* ================================================================== */

    private int        currentIndex = 0;
    private BukkitTask reminderTask;
    private BukkitTask autoSaveTask;
    private final Random random = new Random();

    /** Pre-built immutable Sound object — reused every broadcast. */
    private Sound cachedSound;

    /* ================================================================== */
    /*  Folder & file references                                           */
    /* ================================================================== */

    private File moduleFolder;
    private File configFile;
    private File playersFile;
    private File messagesDir;

    /* ================================================================== */
    /*  Legacy code → MiniMessage tag map                                   */
    /* ================================================================== */

    private static final Map<Character, String> LEGACY_MAP =
            new LinkedHashMap<>();

    static {
        LEGACY_MAP.put('0', "<black>");
        LEGACY_MAP.put('1', "<dark_blue>");
        LEGACY_MAP.put('2', "<dark_green>");
        LEGACY_MAP.put('3', "<dark_aqua>");
        LEGACY_MAP.put('4', "<dark_red>");
        LEGACY_MAP.put('5', "<dark_purple>");
        LEGACY_MAP.put('6', "<gold>");
        LEGACY_MAP.put('7', "<gray>");
        LEGACY_MAP.put('8', "<dark_gray>");
        LEGACY_MAP.put('9', "<blue>");
        LEGACY_MAP.put('a', "<green>");
        LEGACY_MAP.put('b', "<aqua>");
        LEGACY_MAP.put('c', "<red>");
        LEGACY_MAP.put('d', "<light_purple>");
        LEGACY_MAP.put('e', "<yellow>");
        LEGACY_MAP.put('f', "<white>");
        LEGACY_MAP.put('k', "<obfuscated>");
        LEGACY_MAP.put('l', "<bold>");
        LEGACY_MAP.put('m', "<strikethrough>");
        LEGACY_MAP.put('n', "<underlined>");
        LEGACY_MAP.put('o', "<italic>");
        LEGACY_MAP.put('r', "<reset>");
    }

    /* ================================================================== */
    /*  Constructor                                                         */
    /* ================================================================== */

    public ChatReminderModule(XiannnAddons plugin) {
        this.plugin = plugin;
        this.log    = new AddonLogger(plugin, getModuleName());
    }

    /* ================================================================== */
    /*  AddonModule identity                                               */
    /* ================================================================== */

    @Override
    public String getModuleName() {
        return "ChatReminder";
    }

    /* ================================================================== */
    /*  AddonModule — command declaration                                   */
    /* ================================================================== */

    @Override
    public Map<String, String> getCommands() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("tips", "Toggle or reload chat reminders");
        return commands;
    }

    /* ================================================================== */
    /*  AddonModule — command handling                                     */
    /* ================================================================== */

    @Override
    public boolean handleCommand(@NotNull CommandSender sender,
                                 @NotNull String command,
                                 @NotNull String[] args) {
        log.debug("handleCommand: command=" + command
                + " args=" + Arrays.toString(args)
                + " sender=" + sender.getName());

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "toggle" -> handleToggle(sender);
            case "reload" -> handleReload(sender);
            default       -> sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> handleTabComplete(@NotNull CommandSender sender,
                                          @NotNull String command,
                                          @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();

            if (sender.hasPermission("xiannnaddons.tips.toggle")) {
                completions.add("toggle");
            }
            if (sender.hasPermission("xiannnaddons.tips.reload")) {
                completions.add("reload");
            }

            String partial = args[0].toLowerCase();
            completions.removeIf(s -> !s.startsWith(partial));
            return completions;
        }

        return Collections.emptyList();
    }

    /* ================================================================== */
    /*  Lifecycle                                                           */
    /* ================================================================== */

    @Override
    public void onEnable() {
        log.info("Enabling...");

        moduleFolder = plugin.getModuleFolder(this);
        configFile   = new File(moduleFolder, "config.yml");
        playersFile  = new File(moduleFolder, "players.yml");
        messagesDir  = new File(moduleFolder, "messages");

        log.debug("Module folder: " + moduleFolder.getPath());

        if (!messagesDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            messagesDir.mkdirs();
        }

        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        File[] tipFiles = messagesDir.listFiles(
                (d, n) -> n.endsWith(".yml"));
        if (tipFiles == null || tipFiles.length == 0) {
            generateDefaultTips();
        }

        loadConfig();
        loadMessages();     // Optimization #1: pre-parses all messages
        loadPlayerToggles();// Optimization #3: loads once, never on join

        rebuildEligibleCache(); // Optimization #2: initial cache build

        startReminderTask();
        startAutoSaveTask();    // Optimization #3: periodic dirty save

        log.info("Enabled successfully.");
    }

    @Override
    public void onDisable() {
        log.info("Disabling...");
        stopReminderTask();
        stopAutoSaveTask();

        // Final save — only if dirty
        if (togglesDirty) {
            savePlayerToggles();
        }

        playerToggles.clear();
        eligiblePlayers.clear();
        parsedMessages.clear();
        rawMessagesDebug.clear();

        log.info("Disabled. All state cleaned up.");
    }

    @Override
    public void onReload() {
        log.info("Reloading (triggered by /xa reload)...");
        loadConfig();
        loadMessages();
        loadPlayerToggles();
        rebuildEligibleCache();
        startReminderTask();
        log.info("Reload complete.");
    }

    /* ================================================================== */
    /*  Command sub-handlers                                                */
    /* ================================================================== */

    private void handleToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    Component.text("Only players can toggle tips.",
                            NamedTextColor.RED));
            return;
        }

        if (!player.hasPermission("xiannnaddons.tips.toggle")) {
            sender.sendMessage(
                    Component.text("You don't have permission.",
                            NamedTextColor.RED));
            return;
        }

        UUID    uuid     = player.getUniqueId();
        boolean newState = !isReminderEnabled(uuid);

        // Update toggle map
        playerToggles.put(uuid, newState);
        togglesDirty = true;

        // Update eligible cache immediately
        if (newState) {
            eligiblePlayers.add(uuid);
        } else {
            eligiblePlayers.remove(uuid);
        }

        log.debug("Player " + player.getName() + " toggled tips: "
                + newState + " (eligible cache size: "
                + eligiblePlayers.size() + ")");

        Component status = newState
                ? Component.text("enabled", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("disabled", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        player.sendMessage(
                Component.text("Tips have been ", NamedTextColor.GRAY)
                        .append(status)
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.tips.reload")) {
            sender.sendMessage(
                    Component.text("You don't have permission.",
                            NamedTextColor.RED));
            return;
        }

        log.info("Manual reload initiated by " + sender.getName());

        loadConfig();
        loadMessages();
        rebuildEligibleCache();
        startReminderTask();

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text(
                                "ChatReminder reloaded! ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                parsedMessages.size() + " tip(s)",
                                NamedTextColor.AQUA))
                        .append(Component.text(", interval ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                delaySeconds + "s",
                                NamedTextColor.AQUA))
                        .append(Component.text(", order ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                randomOrder ? "random" : "linear",
                                NamedTextColor.AQUA))
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(
                Component.text("Usage: ", NamedTextColor.GOLD)
                        .append(Component.text(
                                "/tips <toggle|reload>",
                                NamedTextColor.YELLOW)));
    }

    /* ================================================================== */
    /*  Default file generation                                            */
    /* ================================================================== */

    private void saveDefaultConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of(
                "╔══════════════════════════════════════════════════════════════════╗",
                "║           XiannnAddons — ChatReminder Configuration             ║",
                "╚══════════════════════════════════════════════════════════════════╝",
                "",
                "Messages are stored as .yml files in 'messages/'.",
                "Each file supports a single string or a list of lines:",
                "",
                "  Single line:   message: '&6Hello!'",
                "  Multi-line:    message:",
                "                   - 'line 1'",
                "                   - 'line 2'",
                "",
                "If YAML parsing fails, a fallback parser handles it.",
                "Edit tip files freely — then run /tips reload."
        ));

        cfg.setComments("delay", List.of(
                "Seconds between each tip broadcast (minimum: 10)"));
        cfg.set("delay", 300);

        cfg.setComments("order", List.of(
                "\"linear\" = cycle in file-name order",
                "\"random\" = random pick"));
        cfg.set("order", "linear");

        cfg.setComments("default-enabled", List.of(
                "Should tips be ON by default for new players?"));
        cfg.set("default-enabled", true);

        cfg.setComments("sound", List.of(
                "Sound played alongside each tip"));
        cfg.set("sound.enabled", true);
        cfg.setComments("sound.type", List.of(
                "Minecraft sound key"));
        cfg.set("sound.type", "block.note_block.chime");
        cfg.set("sound.volume", 0.5);
        cfg.set("sound.pitch", 1.2);

        try {
            cfg.save(configFile);
            log.debug("Default config.yml saved.");
        } catch (IOException e) {
            log.error("Could not save default config.yml!", e);
        }
    }

    private void generateDefaultTips() {
        log.info("Generating default tip files...");

        List<List<String>> defaults = new ArrayList<>();

        defaults.add(List.of(
                "&6Welcome to the server! Enjoy your stay."));
        defaults.add(List.of(
                "&8&m─────────────────────────────────",
                "&#FF6B6B  Don't forget to set your home!",
                "  &7Use &f/sethome &7to save your location.",
                "&8&m─────────────────────────────────"));
        defaults.add(List.of(
                "<gradient:gold:yellow>Vote for us daily!</gradient>"));
        defaults.add(List.of(
                "&8&m─────────────────────────────────",
                "<gradient:#FF4500:#FF8C00>  Join our Discord!</gradient>",
                "  &7Stay updated with the latest news.",
                "  &7Link: &f&ndiscord.gg/example",
                "&8&m─────────────────────────────────"));
        defaults.add(List.of(
                "&aNeed help? Use &f/helpme &ato contact staff."));
        defaults.add(List.of(
                "&8&m─────────────────────────────────",
                "&#AAFFAA  ⛏ Protect your builds!",
                "  &7Use the golden shovel to create claims.",
                "&8&m─────────────────────────────────"));
        defaults.add(List.of(
                "<rainbow>Every day is a great day to play!</rainbow>"));
        defaults.add(List.of(
                "&8&m─────────────────────────────────",
                "&b  Player Market",
                "  &7Buy and sell items with others!",
                "  &7Warp there: &f/warp market",
                "&8&m─────────────────────────────────"));
        defaults.add(List.of(
                "&#7B68EE✧ &dDonation perks at &f&nstore.example.com"));
        defaults.add(List.of(
                "&8&m─────────────────────────────────",
                " ",
                "<gradient:#00C9FF:#92FE9D>  Stay hydrated.</gradient>",
                "  &7Take a break. &#FF69B4♥",
                " ",
                "&8&m─────────────────────────────────"));

        for (int i = 0; i < defaults.size(); i++) {
            File tipFile = new File(messagesDir,
                    "tip_" + (i + 1) + ".yml");
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.options().setHeader(List.of(
                    "Tip message file",
                    "Supports: Legacy (&6), Hex (&#FF5555),",
                    "MiniMessage (<gradient:red:blue>Text</gradient>)"));

            List<String> lines = defaults.get(i);
            if (lines.size() == 1) {
                cfg.set("message", lines.get(0));
            } else {
                cfg.set("message", lines);
            }

            try {
                cfg.save(tipFile);
                log.debug("Created " + tipFile.getName());
            } catch (IOException e) {
                log.warn("Could not create " + tipFile.getName(), e);
            }
        }

        log.info("Generated " + defaults.size() + " default tips.");
    }

    /* ================================================================== */
    /*  Config loading                                                      */
    /* ================================================================== */

    private void loadConfig() {
        log.debug("Loading config: " + configFile.getPath());

        YamlConfiguration cfg =
                YamlConfiguration.loadConfiguration(configFile);

        delaySeconds   = Math.max(10, cfg.getInt("delay", 300));
        randomOrder    = "random".equalsIgnoreCase(
                cfg.getString("order", "linear"));
        defaultEnabled = cfg.getBoolean("default-enabled", true);

        soundEnabled = cfg.getBoolean("sound.enabled", true);
        soundVolume  = (float) cfg.getDouble("sound.volume", 0.5);
        soundPitch   = (float) cfg.getDouble("sound.pitch", 1.2);

        String soundType = cfg.getString("sound.type",
                "block.note_block.chime");
        soundKey = soundType.contains(":")
                 ? Key.key(soundType)
                 : Key.key("minecraft", soundType);

        // Pre-build immutable Sound object
        cachedSound = soundEnabled
                ? Sound.sound(soundKey, Sound.Source.MASTER,
                        soundVolume, soundPitch)
                : null;

        log.info("Config loaded: delay=" + delaySeconds
                + "s, order=" + (randomOrder ? "random" : "linear")
                + ", sound=" + (soundEnabled ? soundType : "off"));
        log.debug("Sound: key=" + soundKey
                + " vol=" + soundVolume
                + " pitch=" + soundPitch);
    }

    /* ================================================================== */
    /*  Message loading + pre-parsing                                       */
    /*  OPTIMIZATION #1: Parse once, broadcast many.                       */
    /* ================================================================== */

    /**
     * Loads all tip files and <b>pre-parses</b> every line through
     * the formatting engine. The resulting {@link Component} objects
     * are stored in {@link #parsedMessages} and reused on every
     * broadcast — zero parsing at send time.
     */
    private void loadMessages() {
        parsedMessages.clear();
        rawMessagesDebug.clear();
        currentIndex = 0;

        File[] files = messagesDir.listFiles(
                (dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            log.warn("No .yml files in " + messagesDir.getPath());
            return;
        }

        log.debug("Found " + files.length + " .yml file(s).");

        Arrays.sort(files, Comparator.comparing(
                f -> naturalSortKey(f.getName())));

        int totalLines = 0;

        for (File file : files) {
            log.debug("Processing: " + file.getName());
            List<String> rawLines = loadTipFile(file);

            if (rawLines == null || rawLines.isEmpty()) {
                log.warn("No content in " + file.getName()
                        + " — skipping.");
                continue;
            }

            // ── Pre-parse every line into Component ─────────────────
            List<Component> parsed = new ArrayList<>(rawLines.size());
            for (String rawLine : rawLines) {
                if (rawLine == null || rawLine.isBlank()) {
                    parsed.add(Component.empty());
                    log.debug("  Pre-parsed: (empty line)");
                } else {
                    Component comp = parseFormatted(rawLine);
                    parsed.add(comp);
                    log.debug("  Pre-parsed: "
                            + truncate(rawLine, 50) + " → OK");
                }
            }

            parsedMessages.add(parsed);
            rawMessagesDebug.add(rawLines);
            totalLines += parsed.size();

            log.info("Loaded " + file.getName() + " ("
                    + rawLines.size() + " line"
                    + (rawLines.size() == 1 ? "" : "s")
                    + ", pre-parsed)");
        }

        log.info("Total: " + parsedMessages.size() + " tip(s), "
                + totalLines + " line(s) pre-parsed.");
    }

    /**
     * Loads raw lines from a tip file. Tries YAML, falls back
     * to manual parser.
     */
    private List<String> loadTipFile(File file) {

        log.debug("Trying YAML parser for: " + file.getName());

        YamlConfiguration tipCfg =
                YamlConfiguration.loadConfiguration(file);
        Object raw = tipCfg.get("message");

        if (raw != null) {
            log.debug("YAML returned: "
                    + raw.getClass().getSimpleName());
            List<String> lines = extractLinesFromYaml(raw);
            if (lines != null && !lines.isEmpty()) {
                log.debug("YAML OK: " + lines.size() + " line(s)");
                return lines;
            }
        } else {
            log.debug("YAML returned null for 'message'");
        }

        log.warn("YAML parse failed for " + file.getName()
                + " — using fallback parser.");
        return fallbackReadTipFile(file);
    }

    private List<String> extractLinesFromYaml(Object raw) {
        List<String> lines = new ArrayList<>();

        if (raw instanceof List<?> rawList) {
            for (Object element : rawList) {
                if (element != null) {
                    lines.add(element.toString());
                }
            }
        } else if (raw instanceof String str) {
            if (!str.isBlank()) {
                lines.add(str);
            }
        } else {
            log.warn("Unexpected YAML type: "
                    + raw.getClass().getSimpleName());
        }

        return lines.isEmpty() ? null : lines;
    }

    private List<String> fallbackReadTipFile(File file) {
        List<String> result = new ArrayList<>();
        boolean inMessageBlock = false;
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(
                new FileReader(file, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();

                if (trimmed.isEmpty() && !inMessageBlock) continue;
                if (trimmed.startsWith("#")) continue;

                if (trimmed.startsWith("message:")) {
                    inMessageBlock = true;
                    log.debug("Fallback: 'message:' at line "
                            + lineNumber);

                    String afterKey = trimmed.substring(
                            "message:".length()).trim();
                    if (!afterKey.isEmpty()) {
                        result.add(stripYamlQuotes(afterKey));
                    }
                    continue;
                }

                if (inMessageBlock) {
                    if (trimmed.startsWith("- ")) {
                        String value = trimmed.substring(2).trim();
                        String extracted = stripYamlQuotes(value);
                        result.add(extracted);
                        log.debug("Fallback: line " + lineNumber
                                + " → " + truncate(extracted, 50));
                    } else if (trimmed.equals("-")) {
                        result.add("");
                    } else if (!trimmed.startsWith("-")) {
                        inMessageBlock = false;
                    }
                }
            }

        } catch (IOException e) {
            log.warn("Could not read " + file.getName(), e);
            return null;
        }

        if (!result.isEmpty()) {
            log.info("Fallback recovered " + result.size()
                    + " line(s) from " + file.getName());
        }

        return result.isEmpty() ? null : result;
    }

    private String stripYamlQuotes(String value) {
        if (value == null || value.isEmpty()) return "";

        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last  = value.charAt(value.length() - 1);

            if (first == '\'' && last == '\'') {
                return value.substring(1, value.length() - 1)
                        .replace("''", "'");
            }
            if (first == '"' && last == '"') {
                return value.substring(1, value.length() - 1);
            }
        }

        return value;
    }

    private static String naturalSortKey(String name) {
        StringBuilder sb     = new StringBuilder();
        StringBuilder numBuf = new StringBuilder();

        for (char c : name.toCharArray()) {
            if (Character.isDigit(c)) {
                numBuf.append(c);
            } else {
                if (!numBuf.isEmpty()) {
                    sb.append(String.format("%010d",
                            Long.parseLong(numBuf.toString())));
                    numBuf.setLength(0);
                }
                sb.append(c);
            }
        }

        if (!numBuf.isEmpty()) {
            sb.append(String.format("%010d",
                    Long.parseLong(numBuf.toString())));
        }

        return sb.toString();
    }

    /* ================================================================== */
    /*  Player toggle persistence                                           */
    /*  OPTIMIZATION #3: Load once, save only when dirty.                  */
    /* ================================================================== */

    /**
     * Loads player toggles from disk <b>once</b>.
     * Never called on player join — only on enable/reload.
     */
    private void loadPlayerToggles() {
        playerToggles.clear();
        togglesDirty = false;

        if (!playersFile.exists()) {
            log.debug("No players.yml — starting fresh.");
            return;
        }

        log.debug("Loading player toggles from: "
                + playersFile.getPath());

        YamlConfiguration cfg =
                YamlConfiguration.loadConfiguration(playersFile);
        ConfigurationSection section =
                cfg.getConfigurationSection("players");
        if (section == null) {
            log.debug("No 'players' section.");
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                boolean enabled = section.getBoolean(
                        key, defaultEnabled);
                playerToggles.put(uuid, enabled);
                log.debug("  " + key + " = " + enabled);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping invalid UUID: " + key);
            }
        }

        log.info("Loaded " + playerToggles.size()
                + " player toggle state(s).");
    }

    /**
     * Saves player toggles to disk.
     * Only called when {@link #togglesDirty} is true.
     */
    private void savePlayerToggles() {
        if (!togglesDirty) {
            log.debug("Player toggles clean — skipping save.");
            return;
        }

        log.debug("Saving " + playerToggles.size()
                + " toggle(s) (dirty flag was set).");

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of(
                "XiannnAddons — ChatReminder player toggle states.",
                "Auto-managed. Manual edits are preserved."));

        for (Map.Entry<UUID, Boolean> entry :
                playerToggles.entrySet()) {
            cfg.set("players." + entry.getKey(),
                    entry.getValue());
        }

        try {
            cfg.save(playersFile);
            togglesDirty = false;
            log.debug("Player toggles saved, dirty flag cleared.");
        } catch (IOException e) {
            log.error("Could not save players.yml!", e);
        }
    }

    /* ================================================================== */
    /*  Eligible player cache                                               */
    /*  OPTIMIZATION #2: Set updated on events, not every broadcast.       */
    /* ================================================================== */

    /**
     * Rebuilds the eligible player cache from scratch.
     * Called on enable and reload.
     */
    private void rebuildEligibleCache() {
        eligiblePlayers.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            // Ensure toggle exists for all online players
            playerToggles.putIfAbsent(uuid, defaultEnabled);

            if (isReminderEnabled(uuid)) {
                eligiblePlayers.add(uuid);
            }
        }

        log.info("Eligible player cache built: "
                + eligiblePlayers.size() + " player(s) with tips on.");
        log.debug("Total online: "
                + Bukkit.getOnlinePlayers().size()
                + ", eligible: " + eligiblePlayers.size());
    }

    /* ================================================================== */
    /*  Scheduler                                                           */
    /* ================================================================== */

    private void startReminderTask() {
        stopReminderTask();

        if (parsedMessages.isEmpty()) {
            log.info("No messages — scheduler not started.");
            return;
        }

        long intervalTicks = delaySeconds * 20L;

        reminderTask = new BukkitRunnable() {
            @Override
            public void run() {
                broadcastNextTip();
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);

        log.info("Scheduler started: every " + delaySeconds
                + "s, " + parsedMessages.size() + " tip(s).");
    }

    private void stopReminderTask() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
            log.debug("Reminder scheduler stopped.");
        }
    }

    /**
     * Periodic auto-save task. Saves player toggles every 5 minutes
     * but <b>only if</b> the dirty flag is set. Prevents data loss
     * from crashes without saving on every toggle.
     */
    private void startAutoSaveTask() {
        stopAutoSaveTask();

        // Save every 5 minutes (6000 ticks) if dirty
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (togglesDirty) {
                    log.debug("Auto-save: dirty flag set, saving...");
                    savePlayerToggles();
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L);

        log.debug("Auto-save task started (every 5 min).");
    }

    private void stopAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
            log.debug("Auto-save task stopped.");
        }
    }

    /**
     * Picks the next pre-parsed tip and sends it to all
     * <b>eligible</b> players. Zero parsing happens here.
     *
     * <h4>Performance path</h4>
     * <pre>
     *   1. Pick tip (index or random)   → O(1)
     *   2. Grab pre-parsed Components   → O(1)
     *   3. Loop eligible UUIDs          → O(eligible), not O(total)
     *   4. Get Player from UUID         → O(1) Bukkit lookup
     *   5. Send pre-built Components    → O(lines)
     *   6. Play cached Sound object     → O(1)
     * </pre>
     * Total: O(eligible × lines) with zero parsing.
     */
    private void broadcastNextTip() {
        if (parsedMessages.isEmpty()) return;

        // ── Pick tip ────────────────────────────────────────────────
        int tipIndex;
        if (randomOrder) {
            tipIndex = random.nextInt(parsedMessages.size());
        } else {
            tipIndex = currentIndex;
            currentIndex = (currentIndex + 1)
                    % parsedMessages.size();
        }

        // ── Grab pre-parsed components (no parsing!) ────────────────
        List<Component> tipLines = parsedMessages.get(tipIndex);

        // ── Quick exit if nobody eligible ────────────────────────────
        if (eligiblePlayers.isEmpty()) {
            log.debug("Tip #" + (tipIndex + 1)
                    + " — no eligible players, skipping.");
            return;
        }

        log.debug("Broadcasting tip #" + (tipIndex + 1)
                + " (" + tipLines.size() + " pre-parsed lines)"
                + " to " + eligiblePlayers.size()
                + " eligible player(s).");

        // ── Send to eligible players only ───────────────────────────
        int sent = 0;
        for (UUID uuid : eligiblePlayers) {
            Player player = Bukkit.getPlayer(uuid);

            // Player may have disconnected between cache update
            // and broadcast tick — skip silently
            if (player == null || !player.isOnline()) continue;

            for (Component line : tipLines) {
                player.sendMessage(line);
            }

            if (cachedSound != null) {
                player.playSound(cachedSound);
            }

            sent++;
        }

        log.debug("Broadcast complete: sent to " + sent
                + " player(s).");
    }

    /* ================================================================== */
    /*  Formatting Engine                                                   */
    /*  Called ONLY during loadMessages() — never at broadcast time.       */
    /* ================================================================== */

    /**
     * Universal colour-code parser.
     * <b>Only invoked during message loading</b> — results are
     * cached as {@link Component} objects.
     */
    private Component parseFormatted(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }

        log.debug("Formatting: " + truncate(input, 80));

        // Step 1 — Hex shorthand
        String processed = input.replaceAll(
                "&#([0-9a-fA-F]{6})", "<color:#$1>");

        // Step 2 — Legacy codes
        for (Map.Entry<Character, String> entry :
                LEGACY_MAP.entrySet()) {
            char   code = entry.getKey();
            String tag  = entry.getValue();
            processed = processed.replace("&" + code, tag);
            processed = processed.replace(
                    "&" + Character.toUpperCase(code), tag);
        }

        log.debug("  Converted: " + truncate(processed, 80));

        // Step 3 — MiniMessage
        try {
            Component result = miniMessage.deserialize(processed);
            log.debug("  MiniMessage: OK");
            return result;
        } catch (Exception e) {
            log.warn("Parse failed, using plain: "
                    + truncate(input, 80));
            log.debug("  Error: " + e.getMessage());
            return Component.text(input);
        }
    }

    /* ================================================================== */
    /*  Event listeners                                                     */
    /* ================================================================== */

    /**
     * On join: register toggle default (no file I/O) and
     * add to eligible cache if tips are enabled.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        // putIfAbsent → no file I/O, just a HashMap operation
        boolean isNew = playerToggles.putIfAbsent(
                uuid, defaultEnabled) == null;

        if (isNew) {
            togglesDirty = true; // will be saved by auto-save task
            log.debug("New player: " + event.getPlayer().getName()
                    + " — default tips: " + defaultEnabled);
        }

        // Update eligible cache
        if (isReminderEnabled(uuid)) {
            eligiblePlayers.add(uuid);
        }

        log.debug("Player joined: " + event.getPlayer().getName()
                + " — eligible cache: " + eligiblePlayers.size());
    }

    /**
     * On quit: remove from eligible cache.
     * Toggle state stays in memory — no file I/O.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        eligiblePlayers.remove(uuid);

        log.debug("Player quit: " + event.getPlayer().getName()
                + " — eligible cache: " + eligiblePlayers.size());
    }

    /* ================================================================== */
    /*  Helpers                                                             */
    /* ================================================================== */

    private boolean isReminderEnabled(UUID uuid) {
        return playerToggles.getOrDefault(uuid, defaultEnabled);
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }
}
