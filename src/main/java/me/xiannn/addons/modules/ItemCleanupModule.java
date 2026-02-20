package me.xiannn.addons.modules;

import me.xiannn.addons.AddonLogger;
import me.xiannn.addons.AddonModule;
import me.xiannn.addons.XiannnAddons;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Periodically removes dropped items from loaded chunks.
 * Features boss bar, action bar, sound effects, countdown
 * warnings, whitelist system, and per-player display toggles.
 *
 * <h3>File layout</h3>
 * <pre>
 * plugins/XiannnAddons/ItemCleanup/
 * ├── config.yml    — timing, sounds, bossbar, actionbar, whitelist, worlds
 * └── players.yml   — per-player bossbar/actionbar toggle persistence
 * </pre>
 */
public final class ItemCleanupModule implements AddonModule, Listener {

    private final XiannnAddons plugin;
    private final AddonLogger  log;
    private final MiniMessage  miniMessage = MiniMessage.miniMessage();

    /* ================================================================== */
    /*  Legacy → MiniMessage map                                           */
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
    /*  Configuration fields                                                */
    /* ================================================================== */

    private int           intervalSeconds     = 300;
    private boolean       countdownEnabled    = true;
    private List<Integer> countdownTimes      = new ArrayList<>();

    // Messages
    private String rawCountdownMsg = "";
    private String rawCleanupMsg   = "";

    // Sound — countdown
    private boolean countdownSoundEnabled = true;
    private Key     countdownSoundKey     = Key.key("block.note_block.pling");
    private float   countdownSoundVol     = 0.5F;
    private float   countdownSoundPitch   = 1.0F;
    private Sound   cachedCountdownSound;

    // Sound — cleanup
    private boolean cleanupSoundEnabled = true;
    private Key     cleanupSoundKey     = Key.key("entity.item.pickup");
    private float   cleanupSoundVol     = 0.7F;
    private float   cleanupSoundPitch   = 1.2F;
    private Sound   cachedCleanupSound;

    // Boss bar
    private boolean        bossbarEnabled   = true;
    private int            bossbarShowAt    = 60;
    private String         rawBossbarTitle  = "";
    private BossBar.Color  bossbarColor     = BossBar.Color.RED;
    private BossBar.Overlay bossbarStyle    = BossBar.Overlay.NOTCHED_10;
    private boolean        bossbarDefaultOn = true;

    // Action bar
    private boolean actionbarEnabled   = true;
    private int     actionbarShowAt    = 30;
    private String  rawActionbarMsg    = "";
    private boolean actionbarDefaultOn = true;

    // Whitelist
    private final Set<Material> whitelistedMats = new TreeSet<>();
    private boolean preserveEnchanted = true;
    private boolean preserveNamed     = true;

    // Worlds
    private boolean worldDefaultEnabled = true;
    private final Map<String, Boolean> worldToggles = new HashMap<>();

    /* ================================================================== */
    /*  Runtime state                                                       */
    /* ================================================================== */

    private File       moduleFolder;
    private File       configFile;
    private File       playersFile;
    private BukkitTask tickTask;
    private int        countdown;

    // Adventure boss bar instance (shared, add/remove viewers)
    private BossBar bossBar;

    // Currently showing boss bar (for cleanup on hide)
    private final Set<UUID> bossbarViewers = new HashSet<>();

    /* ── Per-player display toggles ── */
    private final Map<UUID, Boolean> playerBossbar   = new HashMap<>();
    private final Map<UUID, Boolean> playerActionbar  = new HashMap<>();
    private volatile boolean playersDirty = false;
    private BukkitTask autoSaveTask;

    /* ── Statistics ── */
    private int  totalCleanups     = 0;
    private long totalItemsRemoved = 0;
    private int  lastCleanupCount  = 0;
    private long lastCleanupTime   = 0;

    /* ================================================================== */
    /*  Constructor                                                         */
    /* ================================================================== */

    public ItemCleanupModule(XiannnAddons plugin) {
        this.plugin = plugin;
        this.log    = new AddonLogger(plugin, getModuleName());
    }

    /* ================================================================== */
    /*  AddonModule identity                                               */
    /* ================================================================== */

    @Override
    public String getModuleName() {
        return "ItemCleanup";
    }

    /* ================================================================== */
    /*  Commands                                                            */
    /* ================================================================== */

    @Override
    public Map<String, String> getCommands() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("cleanup",
                "Manage item cleanup (now/status/whitelist/reload/bossbar/actionbar)");
        return commands;
    }

    @Override
    public boolean handleCommand(@NotNull CommandSender sender,
                                 @NotNull String command,
                                 @NotNull String[] args) {
        log.debug("handleCommand: cleanup args="
                + Arrays.toString(args));

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "now", "force"      -> handleNow(sender);
            case "status", "stats"   -> handleStatus(sender);
            case "whitelist", "wl"   -> handleWhitelist(sender, args);
            case "reload"            -> handleReload(sender);
            case "bossbar", "bb"     -> handleToggleBossbar(sender);
            case "actionbar", "ab"   -> handleToggleActionbar(sender);
            default                  -> sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> handleTabComplete(@NotNull CommandSender sender,
                                          @NotNull String command,
                                          @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();

            if (sender.hasPermission("xiannnaddons.cleanup.now"))
                completions.add("now");
            if (sender.hasPermission("xiannnaddons.cleanup.status"))
                completions.add("status");
            if (sender.hasPermission("xiannnaddons.cleanup.whitelist"))
                completions.add("whitelist");
            if (sender.hasPermission("xiannnaddons.cleanup.reload"))
                completions.add("reload");
            if (sender.hasPermission("xiannnaddons.cleanup.toggle")) {
                completions.add("bossbar");
                completions.add("actionbar");
            }

            String partial = args[0].toLowerCase();
            completions.removeIf(s -> !s.startsWith(partial));
            return completions;
        }

        if (args.length == 2 &&
                (args[0].equalsIgnoreCase("whitelist")
                        || args[0].equalsIgnoreCase("wl"))) {
            if (!sender.hasPermission("xiannnaddons.cleanup.whitelist"))
                return Collections.emptyList();

            List<String> subs = new ArrayList<>(
                    List.of("add", "remove", "list"));
            String partial = args[1].toLowerCase();
            subs.removeIf(s -> !s.startsWith(partial));
            return subs;
        }

        if (args.length == 3 &&
                (args[0].equalsIgnoreCase("whitelist")
                        || args[0].equalsIgnoreCase("wl"))) {
            if (!sender.hasPermission("xiannnaddons.cleanup.whitelist"))
                return Collections.emptyList();

            String sub = args[1].toLowerCase();
            String partial = args[2].toUpperCase();

            if (sub.equals("add")) {
                List<String> mats = new ArrayList<>();
                for (Material m : Material.values()) {
                    if (m.isItem() && !whitelistedMats.contains(m)
                            && m.name().startsWith(partial)) {
                        mats.add(m.name());
                    }
                    if (mats.size() > 50) break;
                }
                return mats;
            }

            if (sub.equals("remove")) {
                List<String> mats = new ArrayList<>();
                for (Material m : whitelistedMats) {
                    if (m.name().startsWith(partial))
                        mats.add(m.name());
                }
                return mats;
            }
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

        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        loadConfig();
        loadPlayerToggles();
        createBossBar();
        startTickTask();
        startAutoSaveTask();

        log.info("Enabled. interval=" + intervalSeconds
                + "s, whitelist=" + whitelistedMats.size()
                + ", bossbar=" + (bossbarEnabled ? "ON" : "OFF")
                + ", actionbar=" + (actionbarEnabled ? "ON" : "OFF"));
    }

    @Override
    public void onDisable() {
        log.info("Disabling...");
        stopTickTask();
        stopAutoSaveTask();
        hideBossBarFromAll();

        if (playersDirty) {
            savePlayerToggles();
        }

        playerBossbar.clear();
        playerActionbar.clear();
        bossbarViewers.clear();

        log.info("Disabled.");
    }

    @Override
    public void onReload() {
        log.info("Reloading...");
        hideBossBarFromAll();
        loadConfig();
        loadPlayerToggles();
        createBossBar();
        startTickTask();
        log.info("Reloaded.");
    }

    /* ================================================================== */
    /*  Default config generation                                           */
    /* ================================================================== */

    private void saveDefaultConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of(
                "╔══════════════════════════════════════════════════════════════════╗",
                "║           XiannnAddons — Item Cleanup Configuration             ║",
                "╚══════════════════════════════════════════════════════════════════╝",
                "",
                "Periodically removes dropped items from loaded chunks.",
                "Features: boss bar, action bar, sound effects, countdown",
                "warnings, whitelist, per-world and per-player toggles."
        ));

        // Timing
        cfg.setComments("interval", List.of(
                "Seconds between each cleanup cycle (minimum: 30)"));
        cfg.set("interval", 300);

        // Countdown
        cfg.setComments("countdown", List.of(
                "Chat warnings before each cleanup.",
                "Times in seconds before cleanup runs."));
        cfg.set("countdown.enabled", true);
        cfg.set("countdown.times",
                List.of(60, 30, 10, 5, 4, 3, 2, 1));

        // Sound
        cfg.setComments("sound", List.of(
                "Sound effects for countdown and cleanup.",
                "Keys: https://minecraft.wiki/w/Sounds.json"));
        cfg.set("sound.countdown.enabled", true);
        cfg.set("sound.countdown.type", "block.note_block.pling");
        cfg.set("sound.countdown.volume", 0.5);
        cfg.set("sound.countdown.pitch", 1.0);
        cfg.set("sound.cleanup.enabled", true);
        cfg.set("sound.cleanup.type", "entity.item.pickup");
        cfg.set("sound.cleanup.volume", 0.7);
        cfg.set("sound.cleanup.pitch", 1.2);

        // Boss bar
        cfg.setComments("bossbar", List.of(
                "Progress bar shown before cleanup.",
                "Players toggle with /cleanup bossbar.",
                "Colors: PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE",
                "Styles: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20"));
        cfg.set("bossbar.enabled", true);
        cfg.set("bossbar.default-on", true);
        cfg.set("bossbar.show-at", 60);
        cfg.set("bossbar.title",
                "<color:#FF6347>⚠</color> <white>Item Cleanup in "
                + "<color:#FFD700>{time}</color></white>");
        cfg.set("bossbar.color", "RED");
        cfg.set("bossbar.style", "NOTCHED_10");

        // Action bar
        cfg.setComments("actionbar", List.of(
                "Compact countdown above the hotbar.",
                "Players toggle with /cleanup actionbar."));
        cfg.set("actionbar.enabled", true);
        cfg.set("actionbar.default-on", true);
        cfg.set("actionbar.show-at", 30);
        cfg.set("actionbar.message",
                "<color:#FF6347>⚠</color> <white>Cleanup: "
                + "<color:#FFD700>{time}</color></white>");

        // Messages
        cfg.setComments("messages", List.of(
                "Supports: Legacy (&6), Hex (&#FF5555), MiniMessage",
                "Placeholders: {time}, {count}"));
        cfg.set("messages.countdown",
                "<color:#FF6347>⚠</color> <white>Item cleanup in "
                + "<color:#FFD700>{time}</color>! "
                + "Pick up your items!</white>");
        cfg.set("messages.cleanup",
                "<color:#2ECC71>✔</color> <white>Cleaned up "
                + "<color:#FFD700>{count}</color> "
                + "item(s) from the ground.</white>");

        // Whitelist
        cfg.setComments("whitelist", List.of(
                "Items matching these rules are NEVER removed."));
        cfg.set("whitelist.materials", List.of(
                "SHULKER_BOX",
                "WHITE_SHULKER_BOX", "ORANGE_SHULKER_BOX",
                "MAGENTA_SHULKER_BOX", "LIGHT_BLUE_SHULKER_BOX",
                "YELLOW_SHULKER_BOX", "LIME_SHULKER_BOX",
                "PINK_SHULKER_BOX", "GRAY_SHULKER_BOX",
                "LIGHT_GRAY_SHULKER_BOX", "CYAN_SHULKER_BOX",
                "PURPLE_SHULKER_BOX", "BLUE_SHULKER_BOX",
                "BROWN_SHULKER_BOX", "GREEN_SHULKER_BOX",
                "RED_SHULKER_BOX", "BLACK_SHULKER_BOX",
                "NETHER_STAR", "ELYTRA", "BEACON",
                "TOTEM_OF_UNDYING", "DRAGON_EGG",
                "ENCHANTED_GOLDEN_APPLE"));
        cfg.set("whitelist.preserve-enchanted", true);
        cfg.set("whitelist.preserve-named", true);

        // Worlds
        cfg.setComments("worlds", List.of(
                "Per-world cleanup toggle. Unlisted default to 'default'."));
        cfg.set("worlds.default", true);

        try {
            cfg.save(configFile);
            log.info("Created default config.yml.");
        } catch (IOException e) {
            log.error("Could not save default config!", e);
        }
    }

    /* ================================================================== */
    /*  Config loading                                                      */
    /* ================================================================== */

    private void loadConfig() {
        log.debug("Loading config: " + configFile.getPath());
        YamlConfiguration cfg =
                YamlConfiguration.loadConfiguration(configFile);

        // Timing
        intervalSeconds = Math.max(30, cfg.getInt("interval", 300));
        countdown = intervalSeconds;

        // Countdown
        countdownEnabled = cfg.getBoolean("countdown.enabled", true);
        countdownTimes = cfg.getIntegerList("countdown.times");
        countdownTimes.sort(Collections.reverseOrder());

        // Messages
        rawCountdownMsg = cfg.getString("messages.countdown",
                "⚠ Cleanup in {time}!");
        rawCleanupMsg = cfg.getString("messages.cleanup",
                "✔ Cleaned {count} item(s).");

        // Sound — countdown
        countdownSoundEnabled = cfg.getBoolean(
                "sound.countdown.enabled", true);
        String csType = cfg.getString(
                "sound.countdown.type", "block.note_block.pling");
        countdownSoundKey = csType.contains(":")
                ? Key.key(csType) : Key.key("minecraft", csType);
        countdownSoundVol = (float) cfg.getDouble(
                "sound.countdown.volume", 0.5);
        countdownSoundPitch = (float) cfg.getDouble(
                "sound.countdown.pitch", 1.0);
        cachedCountdownSound = countdownSoundEnabled
                ? Sound.sound(countdownSoundKey,
                        Sound.Source.MASTER,
                        countdownSoundVol, countdownSoundPitch)
                : null;

        // Sound — cleanup
        cleanupSoundEnabled = cfg.getBoolean(
                "sound.cleanup.enabled", true);
        String clType = cfg.getString(
                "sound.cleanup.type", "entity.item.pickup");
        cleanupSoundKey = clType.contains(":")
                ? Key.key(clType) : Key.key("minecraft", clType);
        cleanupSoundVol = (float) cfg.getDouble(
                "sound.cleanup.volume", 0.7);
        cleanupSoundPitch = (float) cfg.getDouble(
                "sound.cleanup.pitch", 1.2);
        cachedCleanupSound = cleanupSoundEnabled
                ? Sound.sound(cleanupSoundKey,
                        Sound.Source.MASTER,
                        cleanupSoundVol, cleanupSoundPitch)
                : null;

        // Boss bar
        bossbarEnabled = cfg.getBoolean("bossbar.enabled", true);
        bossbarDefaultOn = cfg.getBoolean("bossbar.default-on", true);
        bossbarShowAt = cfg.getInt("bossbar.show-at", 60);
        rawBossbarTitle = cfg.getString("bossbar.title",
                "⚠ Cleanup in {time}");

        try {
            bossbarColor = BossBar.Color.valueOf(
                    cfg.getString("bossbar.color", "RED")
                            .toUpperCase());
        } catch (IllegalArgumentException e) {
            bossbarColor = BossBar.Color.RED;
            log.warn("Invalid bossbar color, using RED.");
        }

        try {
            bossbarStyle = BossBar.Overlay.valueOf(
                    cfg.getString("bossbar.style", "NOTCHED_10")
                            .toUpperCase());
        } catch (IllegalArgumentException e) {
            bossbarStyle = BossBar.Overlay.NOTCHED_10;
            log.warn("Invalid bossbar style, using NOTCHED_10.");
        }

        // Action bar
        actionbarEnabled = cfg.getBoolean(
                "actionbar.enabled", true);
        actionbarDefaultOn = cfg.getBoolean(
                "actionbar.default-on", true);
        actionbarShowAt = cfg.getInt("actionbar.show-at", 30);
        rawActionbarMsg = cfg.getString("actionbar.message",
                "⚠ Cleanup: {time}");

        // Whitelist
        whitelistedMats.clear();
        for (String name : cfg.getStringList("whitelist.materials")) {
            try {
                whitelistedMats.add(
                        Material.valueOf(name.toUpperCase().trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown whitelist material: " + name);
            }
        }
        preserveEnchanted = cfg.getBoolean(
                "whitelist.preserve-enchanted", true);
        preserveNamed = cfg.getBoolean(
                "whitelist.preserve-named", true);

        // Worlds
        worldToggles.clear();
        worldDefaultEnabled = cfg.getBoolean("worlds.default", true);
        ConfigurationSection worldSec =
                cfg.getConfigurationSection("worlds");
        if (worldSec != null) {
            for (String key : worldSec.getKeys(false)) {
                if (!key.equals("default")) {
                    worldToggles.put(key,
                            worldSec.getBoolean(key, true));
                }
            }
        }

        log.info("Config: interval=" + intervalSeconds
                + "s, whitelist=" + whitelistedMats.size()
                + ", bossbar=" + (bossbarEnabled
                        ? "ON@" + bossbarShowAt + "s" : "OFF")
                + ", actionbar=" + (actionbarEnabled
                        ? "ON@" + actionbarShowAt + "s" : "OFF")
                + ", sound.cd=" + (countdownSoundEnabled
                        ? csType : "OFF")
                + ", sound.cl=" + (cleanupSoundEnabled
                        ? clType : "OFF"));
    }

    private void saveWhitelist() {
        YamlConfiguration cfg =
                YamlConfiguration.loadConfiguration(configFile);
        cfg.set("whitelist.materials",
                whitelistedMats.stream()
                        .map(Material::name)
                        .collect(Collectors.toList()));
        try {
            cfg.save(configFile);
            log.debug("Whitelist saved.");
        } catch (IOException e) {
            log.error("Could not save whitelist!", e);
        }
    }

    /* ================================================================== */
    /*  Player toggle persistence — players.yml                            */
    /* ================================================================== */

    private void loadPlayerToggles() {
        playerBossbar.clear();
        playerActionbar.clear();
        playersDirty = false;

        if (!playersFile.exists()) {
            log.debug("No players.yml — using defaults.");
            return;
        }

        YamlConfiguration cfg =
                YamlConfiguration.loadConfiguration(playersFile);
        ConfigurationSection section =
                cfg.getConfigurationSection("players");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                playerBossbar.put(uuid,
                        section.getBoolean(
                                key + ".bossbar", bossbarDefaultOn));
                playerActionbar.put(uuid,
                        section.getBoolean(
                                key + ".actionbar", actionbarDefaultOn));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping invalid UUID: " + key);
            }
        }

        log.info("Loaded " + playerBossbar.size()
                + " player display preference(s).");
    }

    private void savePlayerToggles() {
        if (!playersDirty) return;

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().setHeader(List.of(
                "XiannnAddons — ItemCleanup player display preferences.",
                "Auto-managed. bossbar/actionbar = per-player toggle."));

        // Collect all unique UUIDs
        Set<UUID> allUuids = new HashSet<>();
        allUuids.addAll(playerBossbar.keySet());
        allUuids.addAll(playerActionbar.keySet());

        for (UUID uuid : allUuids) {
            cfg.set("players." + uuid + ".bossbar",
                    playerBossbar.getOrDefault(
                            uuid, bossbarDefaultOn));
            cfg.set("players." + uuid + ".actionbar",
                    playerActionbar.getOrDefault(
                            uuid, actionbarDefaultOn));
        }

        try {
            cfg.save(playersFile);
            playersDirty = false;
            log.debug("Player toggles saved.");
        } catch (IOException e) {
            log.error("Could not save players.yml!", e);
        }
    }

    private boolean isBossbarOn(UUID uuid) {
        if (!bossbarEnabled) return false;
        return playerBossbar.getOrDefault(uuid, bossbarDefaultOn);
    }

    private boolean isActionbarOn(UUID uuid) {
        if (!actionbarEnabled) return false;
        return playerActionbar.getOrDefault(uuid, actionbarDefaultOn);
    }

    /* ================================================================== */
    /*  Boss bar management                                                 */
    /* ================================================================== */

    /**
     * Creates (or recreates) the shared Adventure BossBar instance.
     */
    private void createBossBar() {
        if (bossBar != null) {
            hideBossBarFromAll();
        }

        bossBar = BossBar.bossBar(
                Component.text("Item Cleanup"),
                1.0F,
                bossbarColor,
                bossbarStyle
        );

        log.debug("BossBar created: color=" + bossbarColor
                + " style=" + bossbarStyle);
    }

    /**
     * Updates the boss bar title, progress, and viewer list.
     */
    private void updateBossBar(int secondsLeft) {
        if (!bossbarEnabled || bossBar == null) return;

        // Progress: 1.0 at showAt, 0.0 at 0
        float progress = Math.max(0F, Math.min(1F,
                (float) secondsLeft / (float) bossbarShowAt));

        // Title with {time} replaced
        String timeStr = formatTime(secondsLeft);
        String filled = rawBossbarTitle.replace("{time}", timeStr);
        Component title = parseFormatted(filled);

        bossBar.name(title);
        bossBar.progress(progress);

        // Change color when getting close
        if (secondsLeft <= 5) {
            bossBar.color(BossBar.Color.RED);
        } else if (secondsLeft <= 15) {
            bossBar.color(BossBar.Color.YELLOW);
        } else {
            bossBar.color(bossbarColor);
        }

        // Show to eligible players
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();

            if (isBossbarOn(uuid)) {
                if (!bossbarViewers.contains(uuid)) {
                    player.showBossBar(bossBar);
                    bossbarViewers.add(uuid);
                    log.debug("BossBar shown to: "
                            + player.getName());
                }
            } else {
                if (bossbarViewers.contains(uuid)) {
                    player.hideBossBar(bossBar);
                    bossbarViewers.remove(uuid);
                }
            }
        }
    }

    /**
     * Hides the boss bar from all current viewers.
     */
    private void hideBossBarFromAll() {
        if (bossBar == null) return;

        for (UUID uuid : new HashSet<>(bossbarViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.hideBossBar(bossBar);
            }
        }
        bossbarViewers.clear();

        log.debug("BossBar hidden from all viewers.");
    }

    /* ================================================================== */
    /*  Tick task — 1 second interval                                       */
    /* ================================================================== */

    private void startTickTask() {
        stopTickTask();
        countdown = intervalSeconds;

        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);

        log.debug("Tick task started. Cleanup in "
                + intervalSeconds + "s.");
    }

    private void stopTickTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void startAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (playersDirty) {
                    savePlayerToggles();
                }
            }
        }.runTaskTimer(plugin, 6000L, 6000L); // 5 min
    }

    private void stopAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    /**
     * Called every second.
     */
    private void tick() {
        countdown--;

        // ── Boss bar ────────────────────────────────────────────────
        if (bossbarEnabled && countdown > 0
                && countdown <= bossbarShowAt) {
            updateBossBar(countdown);
        } else if (bossbarEnabled && countdown > bossbarShowAt) {
            // Outside range — hide if showing
            if (!bossbarViewers.isEmpty()) {
                hideBossBarFromAll();
            }
        }

        // ── Action bar ──────────────────────────────────────────────
        if (actionbarEnabled && countdown > 0
                && countdown <= actionbarShowAt) {
            sendActionBar(countdown);
        }

        // ── Chat countdown warnings ─────────────────────────────────
        if (countdownEnabled && countdown > 0
                && countdownTimes.contains(countdown)) {
            broadcastCountdown(countdown);
        }

        // ── Cleanup ─────────────────────────────────────────────────
        if (countdown <= 0) {
            performCleanup(false);
            countdown = intervalSeconds;
        }
    }

    /* ================================================================== */
    /*  Cleanup execution                                                    */
    /* ================================================================== */

    private void performCleanup(boolean forced) {
        long startTime = System.currentTimeMillis();
        log.debug("Cleanup starting (forced=" + forced + ")...");

        int removedCount = 0;
        int skippedCount = 0;
        int worldsScanned = 0;

        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) {
                log.debug("Skipping world: " + world.getName());
                continue;
            }

            worldsScanned++;

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Item item)) continue;

                ItemStack stack = item.getItemStack();

                if (isWhitelisted(stack)) {
                    skippedCount++;
                    continue;
                }

                item.remove();
                removedCount++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Stats
        totalCleanups++;
        totalItemsRemoved += removedCount;
        lastCleanupCount = removedCount;
        lastCleanupTime = System.currentTimeMillis();

        log.info("Cleanup: removed=" + removedCount
                + ", preserved=" + skippedCount
                + ", worlds=" + worldsScanned
                + ", " + elapsed + "ms"
                + (forced ? " (forced)" : ""));

        // Hide boss bar
        hideBossBarFromAll();

        // Broadcast + sound only if items removed
        if (removedCount > 0) {
            broadcastCleanup(removedCount);

            // Cleanup sound
            if (cachedCleanupSound != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(cachedCleanupSound);
                }
            }
        }
    }

    /* ================================================================== */
    /*  Whitelist                                                            */
    /* ================================================================== */

    private boolean isWhitelisted(ItemStack stack) {
        if (stack == null) return false;

        if (whitelistedMats.contains(stack.getType())) return true;

        if (preserveEnchanted
                && !stack.getEnchantments().isEmpty()) return true;

        if (preserveNamed && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) return true;
        }

        return false;
    }

    private boolean isWorldEnabled(String worldName) {
        return worldToggles.getOrDefault(
                worldName, worldDefaultEnabled);
    }

    /* ================================================================== */
    /*  Broadcasting                                                        */
    /* ================================================================== */

    private void broadcastCountdown(int secondsLeft) {
        String timeStr = formatTime(secondsLeft);
        String filled = rawCountdownMsg.replace("{time}", timeStr);
        Component message = parseFormatted(filled);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);

            if (cachedCountdownSound != null) {
                player.playSound(cachedCountdownSound);
            }
        }
    }

    private void broadcastCleanup(int count) {
        String filled = rawCleanupMsg.replace("{count}",
                String.valueOf(count));
        Component message = parseFormatted(filled);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    private void sendActionBar(int secondsLeft) {
        String timeStr = formatTime(secondsLeft);
        String filled = rawActionbarMsg.replace("{time}", timeStr);
        Component message = parseFormatted(filled);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isActionbarOn(player.getUniqueId())) {
                player.sendActionBar(message);
            }
        }
    }

    /* ================================================================== */
    /*  Event listeners                                                     */
    /* ================================================================== */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        boolean bbNew = playerBossbar.putIfAbsent(
                uuid, bossbarDefaultOn) == null;
        boolean abNew = playerActionbar.putIfAbsent(
                uuid, actionbarDefaultOn) == null;

        if (bbNew || abNew) {
            playersDirty = true;
            log.debug("New player display defaults set: "
                    + event.getPlayer().getName());
        }

        // If bossbar is currently in range, show immediately
        if (bossbarEnabled && countdown > 0
                && countdown <= bossbarShowAt
                && isBossbarOn(uuid)) {
            event.getPlayer().showBossBar(bossBar);
            bossbarViewers.add(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        if (bossbarViewers.remove(uuid)) {
            event.getPlayer().hideBossBar(bossBar);
        }
    }

    /* ================================================================== */
    /*  Command handlers                                                    */
    /* ================================================================== */

    // ── /cleanup now ──

    private void handleNow(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.cleanup.now")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        log.info("Force cleanup by " + sender.getName());
        performCleanup(true);
        countdown = intervalSeconds;

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text(
                                "Cleanup forced. Removed ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                lastCleanupCount + " item(s)",
                                NamedTextColor.AQUA))
                        .append(Component.text(
                                ". Timer reset.",
                                NamedTextColor.GRAY)));
    }

    // ── /cleanup bossbar ──

    private void handleToggleBossbar(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    Component.text("Only players can toggle.",
                            NamedTextColor.RED));
            return;
        }

        if (!player.hasPermission("xiannnaddons.cleanup.toggle")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        if (!bossbarEnabled) {
            sender.sendMessage(
                    Component.text("Boss bar is disabled in config.",
                            NamedTextColor.RED));
            return;
        }

        UUID uuid = player.getUniqueId();
        boolean newState = !isBossbarOn(uuid);
        playerBossbar.put(uuid, newState);
        playersDirty = true;

        // Immediate visual update
        if (!newState && bossbarViewers.contains(uuid)) {
            player.hideBossBar(bossBar);
            bossbarViewers.remove(uuid);
        } else if (newState && countdown > 0
                && countdown <= bossbarShowAt
                && !bossbarViewers.contains(uuid)) {
            player.showBossBar(bossBar);
            bossbarViewers.add(uuid);
        }

        log.debug(player.getName() + " bossbar: " + newState);

        Component status = newState
                ? Component.text("enabled", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("disabled", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        player.sendMessage(
                Component.text("Cleanup boss bar has been ",
                        NamedTextColor.GRAY)
                        .append(status)
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    // ── /cleanup actionbar ──

    private void handleToggleActionbar(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    Component.text("Only players can toggle.",
                            NamedTextColor.RED));
            return;
        }

        if (!player.hasPermission("xiannnaddons.cleanup.toggle")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        if (!actionbarEnabled) {
            sender.sendMessage(
                    Component.text(
                            "Action bar is disabled in config.",
                            NamedTextColor.RED));
            return;
        }

        UUID uuid = player.getUniqueId();
        boolean newState = !isActionbarOn(uuid);
        playerActionbar.put(uuid, newState);
        playersDirty = true;

        log.debug(player.getName() + " actionbar: " + newState);

        Component status = newState
                ? Component.text("enabled", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("disabled", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        player.sendMessage(
                Component.text("Cleanup action bar has been ",
                        NamedTextColor.GRAY)
                        .append(status)
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    // ── /cleanup status ──

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.cleanup.status")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        int currentItems = 0;
        int activeWorlds = 0;
        for (World world : Bukkit.getWorlds()) {
            if (!isWorldEnabled(world.getName())) continue;
            activeWorlds++;
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item) currentItems++;
            }
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" Item Cleanup Status",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());

        statusLine(sender, "State",
                Component.text("● ACTIVE", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD));
        statusLine(sender, "Interval",
                Component.text(intervalSeconds + "s ("
                        + formatTimeCompact(intervalSeconds) + ")",
                        NamedTextColor.WHITE));
        statusLine(sender, "Next cleanup in",
                Component.text(formatTimeCompact(countdown),
                        NamedTextColor.YELLOW));

        NamedTextColor itemColor = currentItems > 100
                ? NamedTextColor.RED
                : currentItems > 50
                        ? NamedTextColor.YELLOW
                        : NamedTextColor.GREEN;
        statusLine(sender, "Items on ground",
                Component.text(String.valueOf(currentItems),
                        itemColor));

        // Display section
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Display",
                        NamedTextColor.GOLD));
        statusLine(sender, "  Boss bar",
                Component.text(bossbarEnabled
                        ? "ON (show at " + bossbarShowAt + "s)"
                        : "OFF",
                        bossbarEnabled
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Action bar",
                Component.text(actionbarEnabled
                        ? "ON (show at " + actionbarShowAt + "s)"
                        : "OFF",
                        actionbarEnabled
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Sound (countdown)",
                Component.text(countdownSoundEnabled
                        ? "ON (" + countdownSoundKey.value() + ")"
                        : "OFF",
                        countdownSoundEnabled
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Sound (cleanup)",
                Component.text(cleanupSoundEnabled
                        ? "ON (" + cleanupSoundKey.value() + ")"
                        : "OFF",
                        cleanupSoundEnabled
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Countdown warnings",
                Component.text(countdownEnabled
                        ? countdownTimes.size() + " times"
                        : "OFF",
                        countdownEnabled
                                ? NamedTextColor.WHITE
                                : NamedTextColor.RED));

        // Last cleanup
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Last Cleanup",
                        NamedTextColor.GOLD));
        if (lastCleanupTime > 0) {
            statusLine(sender, "  Removed",
                    Component.text(lastCleanupCount + " items",
                            NamedTextColor.WHITE));
            statusLine(sender, "  Time",
                    Component.text(formatAgo(lastCleanupTime),
                            NamedTextColor.WHITE));
        } else {
            statusLine(sender, "  Status",
                    Component.text("No cleanups yet",
                            NamedTextColor.GRAY));
        }

        // Lifetime
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Lifetime Stats",
                        NamedTextColor.GOLD));
        statusLine(sender, "  Total cleanups",
                Component.text(String.valueOf(totalCleanups),
                        NamedTextColor.WHITE));
        statusLine(sender, "  Total removed",
                Component.text(totalItemsRemoved + " items",
                        NamedTextColor.WHITE));

        // Config
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Config",
                        NamedTextColor.GOLD));
        statusLine(sender, "  Whitelist",
                Component.text(whitelistedMats.size()
                        + " materials",
                        NamedTextColor.WHITE));
        statusLine(sender, "  Preserve enchanted",
                Component.text(preserveEnchanted ? "Yes" : "No",
                        preserveEnchanted
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Preserve named",
                Component.text(preserveNamed ? "Yes" : "No",
                        preserveNamed
                                ? NamedTextColor.GREEN
                                : NamedTextColor.RED));
        statusLine(sender, "  Active worlds",
                Component.text(String.valueOf(activeWorlds),
                        NamedTextColor.WHITE));

        // Player personal toggles (if player)
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            sender.sendMessage(Component.empty());
            sender.sendMessage(
                    Component.text("  Your Preferences",
                            NamedTextColor.GOLD));
            statusLine(sender, "  Boss bar",
                    Component.text(
                            isBossbarOn(uuid) ? "ON" : "OFF",
                            isBossbarOn(uuid)
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.RED));
            statusLine(sender, "  Action bar",
                    Component.text(
                            isActionbarOn(uuid) ? "ON" : "OFF",
                            isActionbarOn(uuid)
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.RED));
        }

        sender.sendMessage(Component.empty());
    }

    private void statusLine(CommandSender sender,
                            String label, Component value) {
        String padded = String.format("  %-22s", label + ":");
        sender.sendMessage(
                Component.text(padded, NamedTextColor.GRAY)
                        .append(value));
    }

    // ── /cleanup whitelist ──

    private void handleWhitelist(CommandSender sender,
                                 String[] args) {
        if (!sender.hasPermission(
                "xiannnaddons.cleanup.whitelist")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(
                    Component.text("Usage: /cleanup whitelist "
                            + "<add|remove|list>",
                            NamedTextColor.YELLOW));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "list"   -> whitelistList(sender);
            case "add"    -> whitelistAdd(sender, args);
            case "remove" -> whitelistRemove(sender, args);
            default -> sender.sendMessage(
                    Component.text("Usage: /cleanup whitelist "
                            + "<add|remove|list>",
                            NamedTextColor.YELLOW));
        }
    }

    private void whitelistList(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" Cleanup Whitelist ",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text(
                                "(" + whitelistedMats.size() + ")",
                                NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD,
                                        false)));
        sender.sendMessage(Component.empty());

        if (whitelistedMats.isEmpty()) {
            sender.sendMessage(
                    Component.text("  (empty)",
                            NamedTextColor.GRAY));
        } else {
            List<Material> sorted =
                    new ArrayList<>(whitelistedMats);
            StringBuilder line = new StringBuilder("  ");
            int col = 0;
            for (Material mat : sorted) {
                if (col > 0) line.append(", ");
                line.append(mat.name());
                col++;
                if (col >= 3) {
                    sender.sendMessage(
                            Component.text(line.toString(),
                                    NamedTextColor.AQUA));
                    line = new StringBuilder("  ");
                    col = 0;
                }
            }
            if (col > 0) {
                sender.sendMessage(
                        Component.text(line.toString(),
                                NamedTextColor.AQUA));
            }
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Rules:",
                        NamedTextColor.GOLD));
        sender.sendMessage(
                Component.text("    Preserve enchanted: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                preserveEnchanted ? "Yes" : "No",
                                preserveEnchanted
                                        ? NamedTextColor.GREEN
                                        : NamedTextColor.RED)));
        sender.sendMessage(
                Component.text("    Preserve named: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                preserveNamed ? "Yes" : "No",
                                preserveNamed
                                        ? NamedTextColor.GREEN
                                        : NamedTextColor.RED)));
        sender.sendMessage(Component.empty());
    }

    private void whitelistAdd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(
                    Component.text("Usage: /cleanup whitelist "
                            + "add <MATERIAL>",
                            NamedTextColor.YELLOW));
            return;
        }

        String input = args[2].toUpperCase().trim();
        Material mat;

        try {
            mat = Material.valueOf(input);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(
                    Component.text("Unknown material: ",
                            NamedTextColor.RED)
                            .append(Component.text(input,
                                    NamedTextColor.YELLOW)));
            return;
        }

        if (whitelistedMats.contains(mat)) {
            sender.sendMessage(
                    Component.text(mat.name()
                            + " is already whitelisted.",
                            NamedTextColor.YELLOW));
            return;
        }

        whitelistedMats.add(mat);
        saveWhitelist();
        log.info(mat.name() + " added by " + sender.getName());

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text("Added ",
                                NamedTextColor.GRAY))
                        .append(Component.text(mat.name(),
                                NamedTextColor.AQUA))
                        .append(Component.text(
                                " to the whitelist.",
                                NamedTextColor.GRAY)));
    }

    private void whitelistRemove(CommandSender sender,
                                 String[] args) {
        if (args.length < 3) {
            sender.sendMessage(
                    Component.text("Usage: /cleanup whitelist "
                            + "remove <MATERIAL>",
                            NamedTextColor.YELLOW));
            return;
        }

        String input = args[2].toUpperCase().trim();
        Material mat;

        try {
            mat = Material.valueOf(input);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(
                    Component.text("Unknown material: ",
                            NamedTextColor.RED)
                            .append(Component.text(input,
                                    NamedTextColor.YELLOW)));
            return;
        }

        if (!whitelistedMats.contains(mat)) {
            sender.sendMessage(
                    Component.text(mat.name()
                            + " is not whitelisted.",
                            NamedTextColor.YELLOW));
            return;
        }

        whitelistedMats.remove(mat);
        saveWhitelist();
        log.info(mat.name() + " removed by " + sender.getName());

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text("Removed ",
                                NamedTextColor.GRAY))
                        .append(Component.text(mat.name(),
                                NamedTextColor.AQUA))
                        .append(Component.text(
                                " from the whitelist.",
                                NamedTextColor.GRAY)));
    }

    // ── /cleanup reload ──

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.cleanup.reload")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        log.info("Reload by " + sender.getName());
        hideBossBarFromAll();
        loadConfig();
        loadPlayerToggles();
        createBossBar();
        startTickTask();

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text(
                                "ItemCleanup reloaded! ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                "interval=" + intervalSeconds + "s",
                                NamedTextColor.AQUA))
                        .append(Component.text(", ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                whitelistedMats.size()
                                        + " whitelisted",
                                NamedTextColor.AQUA))
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    // ── usage ──

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" Item Cleanup Commands",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
        usageLine(sender, "/cleanup now",
                "Force immediate cleanup");
        usageLine(sender, "/cleanup status",
                "View stats & config");
        usageLine(sender, "/cleanup whitelist <add|remove|list>",
                "Manage protected items");
        usageLine(sender, "/cleanup reload",
                "Reload config");
        usageLine(sender, "/cleanup bossbar",
                "Toggle boss bar for yourself");
        usageLine(sender, "/cleanup actionbar",
                "Toggle action bar for yourself");
        sender.sendMessage(Component.empty());
    }

    private void usageLine(CommandSender sender,
                           String cmd, String desc) {
        sender.sendMessage(
                Component.text("  " + cmd,
                        NamedTextColor.YELLOW)
                        .append(Component.text(" — " + desc,
                                NamedTextColor.GRAY)));
    }

    /* ================================================================== */
    /*  Time formatting                                                     */
    /* ================================================================== */

    private String formatTime(int seconds) {
        if (seconds <= 0) return "now";
        if (seconds < 60) {
            return seconds + " second"
                    + (seconds == 1 ? "" : "s");
        }
        int mins = seconds / 60;
        int secs = seconds % 60;
        if (secs == 0) {
            return mins + " minute"
                    + (mins == 1 ? "" : "s");
        }
        return mins + "m " + secs + "s";
    }

    private String formatTimeCompact(int seconds) {
        if (seconds <= 0) return "now";
        if (seconds < 60) return seconds + "s";
        int mins = seconds / 60;
        int secs = seconds % 60;
        if (secs == 0) return mins + "m";
        return mins + "m " + secs + "s";
    }

    private String formatAgo(long timestampMs) {
        if (timestampMs <= 0) return "never";
        long elapsed = (System.currentTimeMillis() - timestampMs)
                / 1000;
        if (elapsed < 60) return elapsed + "s ago";
        if (elapsed < 3600) return (elapsed / 60) + "m ago";
        return (elapsed / 3600) + "h ago";
    }

    /* ================================================================== */
    /*  Formatting Engine                                                   */
    /* ================================================================== */

    private Component parseFormatted(String input) {
        if (input == null || input.isEmpty())
            return Component.empty();

        String processed = input.replaceAll(
                "&#([0-9a-fA-F]{6})", "<color:#$1>");

        for (Map.Entry<Character, String> entry :
                LEGACY_MAP.entrySet()) {
            char   code = entry.getKey();
            String tag  = entry.getValue();
            processed = processed.replace("&" + code, tag);
            processed = processed.replace(
                    "&" + Character.toUpperCase(code), tag);
        }

        try {
            return miniMessage.deserialize(processed);
        } catch (Exception e) {
            log.warn("Parse failed: " + input);
            return Component.text(input);
        }
    }
}
