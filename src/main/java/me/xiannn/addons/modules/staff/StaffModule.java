package me.xiannn.addons.modules.staff;

import me.xiannn.addons.AddonLogger;
import me.xiannn.addons.AddonModule;
import me.xiannn.addons.XiannnAddons;
import me.xiannn.addons.modules.staff.systems.StaffChatSystem;
import me.xiannn.addons.modules.staff.systems.VanishSystem;
import me.xiannn.addons.modules.staff.systems.StaffListSystem;
import me.xiannn.addons.modules.staff.systems.GameModeSystem;
import me.xiannn.addons.modules.staff.systems.PunishmentSystem;
import me.xiannn.addons.modules.staff.systems.StaffGUISystem;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent module for the Staff System.
 * Orchestrates configuration, database, discord, and all sub-systems.
 */
public class StaffModule implements AddonModule {

    private final XiannnAddons plugin;
    private final AddonLogger log;

    // Core Components
    private StaffConfig config;
    private StaffDatabase database;  // Part 3 ✓
    private DiscordLogger discord;   // Part 4 ✓

    // Sub-Systems
    private StaffChatSystem staffChat;   // Part 5 ✓
    private VanishSystem vanish;         // Part 6 ✓
    private StaffListSystem staffList;   // Part 7 ✓
    private GameModeSystem gameMode;     // Part 8 ✓
    private PunishmentSystem punishments; // Part 9-10 ✓
    private StaffGUISystem gui;          // Part 11-12 ✓

    public StaffModule(XiannnAddons plugin) {
        this.plugin = plugin;
        this.log = new AddonLogger(plugin, "StaffModule");
    }

    @Override
    public String getModuleName() {
        return "StaffModule";
    }

    @Override
    public void onEnable() {
        log.info("Enabling Staff Module...");

        // 1. Setup Config
        try {
            this.config = new StaffConfig(this);
            this.config.load();
        } catch (Exception e) {
            log.error("Failed to load configuration! Module disabling.", e);
            return;
        }

        // 2. Setup Database
        this.database = new StaffDatabase(this);
        this.database.connect();

        // 3. Setup Discord
        this.discord = new DiscordLogger(this);
        this.discord.connect();

        // 4. Initialize Sub-Systems
        this.staffChat = new StaffChatSystem(this);
        this.vanish = new VanishSystem(this);
        this.staffList = new StaffListSystem(this);
        this.gameMode = new GameModeSystem(this);
        this.punishments = new PunishmentSystem(this);
        this.gui = new StaffGUISystem(this);

        log.info("Staff Module enabled successfully.");
    }

    @Override
    public void onDisable() {
        log.info("Disabling Staff Module...");

        // Disable sub-systems
        if (discord != null) discord.disconnect();
        if (database != null) database.disconnect();

        log.info("Staff Module disabled.");
    }

    @Override
    public void onReload() {
        log.info("Reloading Staff Module...");
        
        // Reload Config
        if (config != null) config.load();
        
        // Reload Sub-systems
        // if (staffChat != null) staffChat.reload();
        // if (punishments != null) punishments.reload();
        // ... etc
        
        log.info("Reload complete.");
    }

    // ── Command Routing ──────────────────────────────────────────────
    // We register the main commands here so the AddonModule system
    // knows about them. The actual handling will be delegated to
    // the specific sub-systems once they are built.

    @Override
    public Map<String, String> getCommands() {
        Map<String, String> cmds = new HashMap<>();
        cmds.put("sc", "Toggle staff chat");
        cmds.put("staff", "Open staff GUI");
        cmds.put("stafflist", "List online staff");
        cmds.put("vanish", "Toggle vanish mode");
        cmds.put("gm", "Change gamemode");
        cmds.put("ban", "Ban a player");
        cmds.put("mute", "Mute a player");
        cmds.put("kick", "Kick a player");
        cmds.put("warn", "Warn a player");
        cmds.put("punish", "Open punishment GUI or use preset");
        cmds.put("history", "View player history");
        cmds.put("report", "Report a player");
        cmds.put("reports", "Manage reports");
        return cmds;
    }

    @Override
    public boolean handleCommand(@NotNull CommandSender sender, @NotNull String command, @NotNull String[] args) {
        // Delegate to sub-systems based on command name
        return switch (command) {
            case "sc" -> staffChat.handleCommand(sender, args);
            case "vanish" -> vanish.handleCommand(sender, args);
            case "stafflist" -> staffList.handleCommand(sender, args);
            case "gm" -> gameMode.handleCommand(sender, args);
            case "ban", "tempban", "mute", "tempmute", "kick", "warn", "history", "alts", "punish", "unban", "unmute" ->
                punishments.handleCommand(sender, command, args);
            case "staff" -> gui.handleCommand(sender, args);
            default -> false;
        };
    }

    @Override
    public List<String> handleTabComplete(@NotNull CommandSender sender, @NotNull String command, @NotNull String[] args) {
        // Delegate to sub-systems
        return Collections.emptyList();
    }

    // ── Getters for Sub-systems ──────────────────────────────────────

    public XiannnAddons getPlugin() { return plugin; }
    public AddonLogger getLog() { return log; }
    public StaffConfig getConfigManager() { return config; }
    public StaffDatabase getDatabase() { return database; }
    public DiscordLogger getDiscord() { return discord; }
    public StaffChatSystem getStaffChat() { return staffChat; }
    public VanishSystem getVanish() { return vanish; }
    public StaffListSystem getStaffList() { return staffList; }
    public GameModeSystem getGameMode() { return gameMode; }
    public PunishmentSystem getPunishments() { return punishments; }
    public StaffGUISystem getGui() { return gui; }
}
