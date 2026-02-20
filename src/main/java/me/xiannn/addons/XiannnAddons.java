package me.xiannn.addons;

import me.xiannn.addons.modules.ChatReminderModule;
import me.xiannn.addons.modules.ItemCleanupModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Main entry-point for XiannnAddons.
 */
public final class XiannnAddons extends JavaPlugin
        implements CommandExecutor, TabCompleter {

    /* ── Logging ── */
    private AddonLogger log;

    /* ── Module registries ── */
    private final Map<String, AddonModule> allModules     = new LinkedHashMap<>();
    private final Map<String, AddonModule> enabledModules = new LinkedHashMap<>();

    /* ── Dynamic alias tracking ── */
    private final List<String> registeredAliasNames = new ArrayList<>();

    /* ── Main config ── */
    private File              mainConfigFile;
    private YamlConfiguration mainConfig;

    /* ================================================================== */
    /*  Bukkit lifecycle                                                    */
    /* ================================================================== */

    @Override
    public void onEnable() {
        log = new AddonLogger(this);

        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        // ── Config ──────────────────────────────────────────────────
        mainConfigFile = new File(getDataFolder(), "config.yml");
        if (!mainConfigFile.exists()) {
            saveResource("config.yml", false);
            log.info("Created default config.yml.");
        }
        loadMainConfig();

        // ── Discover modules ────────────────────────────────────────
        discoverModules();
        ensureConfigComplete();

        // ── Enable modules ──────────────────────────────────────────
        enableConfiguredModules();

        // ── Master command ──────────────────────────────────────────
        PluginCommand xaCmd = getCommand("xiannnaddons");
        if (xaCmd != null) {
            xaCmd.setExecutor(this);
            xaCmd.setTabCompleter(this);
            log.debug("Master command /xa registered.");
        } else {
            log.error("Could not register 'xiannnaddons' command!");
        }

        // ── Dynamic aliases ─────────────────────────────────────────
        registerCommandAliases();

        // ── Banner ──────────────────────────────────────────────────
        if (mainConfig.getBoolean("settings.startup-banner", true)) {
            printBanner();
        }
    }

    @Override
    public void onDisable() {
        log.info("Disabling XiannnAddons...");

        List<String> names = new ArrayList<>(enabledModules.keySet());
        Collections.reverse(names);
        for (String name : names) {
            disableModule(allModules.get(name));
        }

        unregisterCommandAliases();
        HandlerList.unregisterAll(this);
        allModules.clear();
        enabledModules.clear();
        AddonLogger.clearSources();

        log.info("XiannnAddons disabled.");
    }

    /* ================================================================== */
    /*  Module discovery                                                    */
    /* ================================================================== */

    private void discoverModules() {
        log.debug("Discovering modules...");

        addModule(new ChatReminderModule(this));
        addModule(new ItemCleanupModule(this));
        // Future:
        // addModule(new AutoPlantModule(this));

        log.debug("Discovered " + allModules.size() + " module(s).");
    }

    private void addModule(AddonModule module) {
        String name = module.getModuleName();
        allModules.put(name, module);

        // Register logging source
        boolean logDefault = mainConfig != null
                ? mainConfig.getBoolean(
                        "logging.sources." + name, true)
                : true;
        AddonLogger.registerSource(name, logDefault);

        File folder = getModuleFolder(module);
        if (!folder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            folder.mkdirs();
            log.debug("Created folder: " + folder.getPath());
        }
    }

    /* ================================================================== */
    /*  Enable / Disable                                                    */
    /* ================================================================== */

    private void enableConfiguredModules() {
        log.debug("Evaluating " + allModules.size()
                + " module(s)...");

        for (Map.Entry<String, AddonModule> entry :
                allModules.entrySet()) {
            String name = entry.getKey();
            boolean enabled = mainConfig.getBoolean(
                    "modules." + name + ".enabled", true);

            log.debug("Module " + name + ": enabled=" + enabled);

            if (enabled) {
                enableModule(entry.getValue());
            } else {
                log.info("Module skipped (disabled): " + name);
            }
        }
    }

    private void enableModule(AddonModule module) {
        String name = module.getModuleName();
        if (enabledModules.containsKey(name)) return;

        try {
            if (module instanceof Listener listener) {
                getServer().getPluginManager()
                        .registerEvents(listener, this);
                log.debug("Events registered: " + name);
            }
            module.onEnable();
            enabledModules.put(name, module);
            log.info("Module enabled: " + name);
        } catch (Exception e) {
            log.error("Failed to enable: " + name, e);
        }
    }

    private void disableModule(AddonModule module) {
        String name = module.getModuleName();
        if (!enabledModules.containsKey(name)) return;

        try {
            module.onDisable();
            if (module instanceof Listener listener) {
                HandlerList.unregisterAll(listener);
            }
            enabledModules.remove(name);
            log.info("Module disabled: " + name);
        } catch (Exception e) {
            log.error("Failed to disable: " + name, e);
        }
    }

    /* ================================================================== */
    /*  Main config                                                         */
    /* ================================================================== */

    private void loadMainConfig() {
        mainConfig = YamlConfiguration.loadConfiguration(
                mainConfigFile);

        // ── Logging settings ────────────────────────────────────────
        loadLoggingConfig();

        log.info("Main configuration loaded.");
        log.debug("Config path: " + mainConfigFile.getPath());
    }

    /**
     * Reads the {@code logging} section and applies all toggles
     * to {@link AddonLogger}.
     */
    private void loadLoggingConfig() {
        boolean globalOn = mainConfig.getBoolean(
                "logging.enabled", true);
        boolean debugOn  = mainConfig.getBoolean(
                "logging.debug", false);

        AddonLogger.setGlobalEnabled(globalOn);
        AddonLogger.setDebugEnabled(debugOn);

        // Register Core source
        AddonLogger.registerSource("Core",
                mainConfig.getBoolean(
                        "logging.sources.Core", true));

        // Load per-source toggles
        ConfigurationSection sources =
                mainConfig.getConfigurationSection(
                        "logging.sources");
        if (sources != null) {
            for (String key : sources.getKeys(false)) {
                boolean enabled = sources.getBoolean(key, true);
                AddonLogger.setSourceEnabled(key, enabled);
            }
        }

        // Log status (this respects the toggles we just set)
        log.info("Logging: global="
                + (globalOn ? "ON" : "OFF")
                + ", debug=" + (debugOn ? "ON" : "OFF"));

        log.debug("Source toggles: "
                + AddonLogger.getSourceToggles());
    }

    /**
     * Saves current logging state back to config and disk.
     */
    private void saveLoggingConfig() {
        mainConfig.set("logging.enabled",
                AddonLogger.isGlobalEnabled());
        mainConfig.set("logging.debug",
                AddonLogger.isDebugEnabled());

        for (Map.Entry<String, Boolean> entry :
                AddonLogger.getSourceToggles().entrySet()) {
            mainConfig.set("logging.sources." + entry.getKey(),
                    entry.getValue());
        }

        saveMainConfig();
    }

    private void saveMainConfig() {
        try {
            mainConfig.save(mainConfigFile);
            log.debug("Config saved.");
        } catch (IOException e) {
            log.error("Could not save config.yml!", e);
        }
    }

    /**
     * Auto-adds missing modules and sources to config.
     */
    private void ensureConfigComplete() {
        boolean changed = false;

        for (String name : allModules.keySet()) {
            // Module toggle
            String modulePath = "modules." + name + ".enabled";
            if (!mainConfig.contains(modulePath)) {
                mainConfig.set(modulePath, true);
                log.info("Added module to config: " + name);
                changed = true;
            }

            // Logging source
            String sourcePath = "logging.sources." + name;
            if (!mainConfig.contains(sourcePath)) {
                mainConfig.set(sourcePath, true);
                AddonLogger.registerSource(name, true);
                log.info("Added logging source: " + name);
                changed = true;
            }
        }

        // Ensure Core source exists
        if (!mainConfig.contains("logging.sources.Core")) {
            mainConfig.set("logging.sources.Core", true);
            changed = true;
        }

        if (changed) saveMainConfig();
    }

    /* ================================================================== */
    /*  Dynamic command aliases                                             */
    /* ================================================================== */

    private void registerCommandAliases() {
        ConfigurationSection section =
                mainConfig.getConfigurationSection("command-aliases");
        if (section == null) return;

        for (String cmdName : section.getKeys(false)) {
            List<String> aliases = section.getStringList(cmdName);

            for (String alias : aliases) {
                String lower = alias.toLowerCase();
                AliasCommand cmd = new AliasCommand(
                        lower, this, cmdName);
                getServer().getCommandMap()
                        .register("xiannnaddons", cmd);
                registeredAliasNames.add(lower);
                log.debug("Alias: /" + lower + " → /xa " + cmdName);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }

        if (!registeredAliasNames.isEmpty()) {
            log.info("Registered aliases: "
                    + String.join(", ", registeredAliasNames));
        }
    }

    private void unregisterCommandAliases() {
        if (registeredAliasNames.isEmpty()) return;

        Map<String, Command> known =
                getServer().getCommandMap().getKnownCommands();

        for (String alias : registeredAliasNames) {
            known.remove(alias);
            known.remove("xiannnaddons:" + alias);
        }
        registeredAliasNames.clear();

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    /* ================================================================== */
    /*  Command routing                                                     */
    /* ================================================================== */

    boolean routeModuleCommand(CommandSender sender,
                               String command, String[] args) {
        log.debug("Routing: " + command
                + " args=" + Arrays.toString(args));

        for (AddonModule module : enabledModules.values()) {
            if (module.getCommands().containsKey(command)) {
                log.debug("Routed to: " + module.getModuleName());
                return module.handleCommand(sender, command, args);
            }
        }

        for (AddonModule module : allModules.values()) {
            if (module.getCommands().containsKey(command)) {
                sender.sendMessage(
                        Component.text("Module ",
                                NamedTextColor.RED)
                                .append(Component.text(
                                        module.getModuleName(),
                                        NamedTextColor.YELLOW))
                                .append(Component.text(
                                        " is disabled.",
                                        NamedTextColor.RED)));
                return true;
            }
        }

        return false;
    }

    List<String> routeModuleTabComplete(CommandSender sender,
                                        String command,
                                        String[] args) {
        for (AddonModule module : enabledModules.values()) {
            if (module.getCommands().containsKey(command)) {
                return module.handleTabComplete(
                        sender, command, args);
            }
        }
        return Collections.emptyList();
    }

    /* ================================================================== */
    /*  Master command — /xa                                                */
    /* ================================================================== */

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            showHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help", "?"       -> showHelp(sender, label);
            case "reload"          -> handleReload(sender);
            case "modules", "list" -> showModules(sender);
            case "enable"          -> handleToggleModule(
                    sender, args, true);
            case "disable"         -> handleToggleModule(
                    sender, args, false);
            case "version", "ver"  -> showVersion(sender);
            case "log", "logging"  -> handleLog(sender, args);
            default -> {
                String sub = args[0].toLowerCase();
                String[] subArgs = Arrays.copyOfRange(
                        args, 1, args.length);
                if (!routeModuleCommand(sender, sub, subArgs)) {
                    showHelp(sender, label);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String label,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("help");
            completions.add("version");

            if (sender.hasPermission("xiannnaddons.reload")) {
                completions.add("reload");
            }
            if (sender.hasPermission("xiannnaddons.modules")) {
                completions.add("modules");
                completions.add("enable");
                completions.add("disable");
            }
            if (sender.hasPermission("xiannnaddons.admin")) {
                completions.add("log");
            }

            for (AddonModule m : enabledModules.values()) {
                completions.addAll(m.getCommands().keySet());
            }

            String partial = args[0].toLowerCase();
            completions.removeIf(
                    s -> !s.toLowerCase().startsWith(partial));
            return completions;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();

            if (sub.equals("enable") &&
                    sender.hasPermission("xiannnaddons.modules")) {
                List<String> list = new ArrayList<>();
                for (String n : allModules.keySet()) {
                    if (!enabledModules.containsKey(n)) list.add(n);
                }
                String p = args[1].toLowerCase();
                list.removeIf(s -> !s.toLowerCase().startsWith(p));
                return list;
            }

            if (sub.equals("disable") &&
                    sender.hasPermission("xiannnaddons.modules")) {
                List<String> list =
                        new ArrayList<>(enabledModules.keySet());
                String p = args[1].toLowerCase();
                list.removeIf(s -> !s.toLowerCase().startsWith(p));
                return list;
            }

            if ((sub.equals("log") || sub.equals("logging")) &&
                    sender.hasPermission("xiannnaddons.admin")) {
                List<String> list = new ArrayList<>();
                list.add("all");
                list.add("debug");
                // Add all source names
                list.add("Core");
                for (String n : allModules.keySet()) {
                    list.add(n);
                }
                String p = args[1].toLowerCase();
                list.removeIf(s -> !s.toLowerCase().startsWith(p));
                return list;
            }

            return routeModuleTabComplete(sender, sub,
                    Arrays.copyOfRange(args, 1, args.length));
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();

            if ((sub.equals("log") || sub.equals("logging")) &&
                    sender.hasPermission("xiannnaddons.admin")) {
                List<String> list = new ArrayList<>();
                list.add("on");
                list.add("off");
                String p = args[2].toLowerCase();
                list.removeIf(s -> !s.startsWith(p));
                return list;
            }

            return routeModuleTabComplete(sender, sub,
                    Arrays.copyOfRange(args, 1, args.length));
        }

        if (args.length > 3) {
            String sub = args[0].toLowerCase();
            return routeModuleTabComplete(sender, sub,
                    Arrays.copyOfRange(args, 1, args.length));
        }

        return Collections.emptyList();
    }

    /* ================================================================== */
    /*  Sub-command: help                                                    */
    /* ================================================================== */

    private void showHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" XiannnAddons",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text(
                                " v" + getPluginMeta().getVersion(),
                                NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD,
                                        false)));
        sender.sendMessage(Component.empty());

        helpLine(sender, "/" + label + " help",
                "Show this help page");
        helpLine(sender, "/" + label + " reload",
                "Reload all configurations");
        helpLine(sender, "/" + label + " modules",
                "List all modules & status");
        helpLine(sender, "/" + label + " enable <module>",
                "Enable a module");
        helpLine(sender, "/" + label + " disable <module>",
                "Disable a module");
        helpLine(sender, "/" + label + " log",
                "View & toggle logging settings");
        helpLine(sender, "/" + label + " log <target> <on|off>",
                "Toggle: all, debug, or a source name");
        helpLine(sender, "/" + label + " version",
                "Version & platform info");

        // Module commands
        boolean header = false;
        for (AddonModule module : allModules.values()) {
            for (Map.Entry<String, String> cmd :
                    module.getCommands().entrySet()) {
                if (!header) {
                    sender.sendMessage(Component.empty());
                    sender.sendMessage(Component.text(
                            " Module Commands",
                            NamedTextColor.GOLD));
                    header = true;
                }
                helpLine(sender,
                        "/" + label + " " + cmd.getKey(),
                        cmd.getValue()
                                + " [" + module.getModuleName() + "]");
            }
        }

        // Aliases
        ConfigurationSection aliasSection =
                mainConfig.getConfigurationSection("command-aliases");
        if (aliasSection != null &&
                !aliasSection.getKeys(false).isEmpty()) {
            sender.sendMessage(Component.empty());
            sender.sendMessage(Component.text(
                    " Active Aliases", NamedTextColor.GOLD));
            for (String cmdName : aliasSection.getKeys(false)) {
                List<String> aliases =
                        aliasSection.getStringList(cmdName);
                if (!aliases.isEmpty()) {
                    String str = "/" + String.join(", /", aliases);
                    sender.sendMessage(
                            Component.text("  " + str,
                                    NamedTextColor.AQUA)
                                    .append(Component.text(
                                            " → /xa " + cmdName,
                                            NamedTextColor.GRAY)));
                }
            }
        }

        sender.sendMessage(Component.empty());
    }

    private void helpLine(CommandSender sender,
                          String cmd, String desc) {
        sender.sendMessage(
                Component.text("  " + cmd, NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to suggest",
                                        NamedTextColor.GRAY)))
                        .clickEvent(ClickEvent.suggestCommand(cmd))
                        .append(Component.text(" — " + desc,
                                NamedTextColor.GRAY)));
    }

    /* ================================================================== */
    /*  Sub-command: log                                                     */
    /* ================================================================== */

    private void handleLog(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xiannnaddons.admin")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        // /xa log — show status
        if (args.length == 1) {
            showLogStatus(sender);
            return;
        }

        // /xa log <target> — toggle without on/off (flip)
        // /xa log <target> <on|off> — explicit set
        String target = args[1].toLowerCase();
        Boolean explicitState = null;

        if (args.length >= 3) {
            String val = args[2].toLowerCase();
            if (val.equals("on") || val.equals("true")
                    || val.equals("enable")) {
                explicitState = true;
            } else if (val.equals("off") || val.equals("false")
                    || val.equals("disable")) {
                explicitState = false;
            } else {
                sender.sendMessage(
                        Component.text("Use: on/off",
                                NamedTextColor.RED));
                return;
            }
        }

        switch (target) {
            case "all", "global" -> {
                boolean newState = explicitState != null
                        ? explicitState
                        : !AddonLogger.isGlobalEnabled();
                AddonLogger.setGlobalEnabled(newState);
                saveLoggingConfig();

                log.info("Global logging "
                        + (newState ? "enabled" : "disabled")
                        + " by " + sender.getName());

                sendToggleResult(sender, "Global logging",
                        newState);
            }

            case "debug" -> {
                boolean newState = explicitState != null
                        ? explicitState
                        : !AddonLogger.isDebugEnabled();
                AddonLogger.setDebugEnabled(newState);
                saveLoggingConfig();

                log.info("Debug logging "
                        + (newState ? "enabled" : "disabled")
                        + " by " + sender.getName());

                sendToggleResult(sender, "Debug logging",
                        newState);
            }

            default -> {
                // Per-source toggle — find matching source name
                String matchedSource = findSourceName(target);

                if (matchedSource == null) {
                    sender.sendMessage(
                            Component.text("Unknown source: ",
                                    NamedTextColor.RED)
                                    .append(Component.text(target,
                                            NamedTextColor.YELLOW)));
                    sender.sendMessage(
                            Component.text("  Available: Core, ",
                                    NamedTextColor.GRAY)
                                    .append(Component.text(
                                            String.join(", ",
                                                    allModules.keySet()),
                                            NamedTextColor.AQUA)));
                    return;
                }

                boolean newState = explicitState != null
                        ? explicitState
                        : !AddonLogger.isSourceEnabled(matchedSource);
                AddonLogger.setSourceEnabled(matchedSource, newState);
                saveLoggingConfig();

                log.info("Logging for " + matchedSource + " "
                        + (newState ? "enabled" : "disabled")
                        + " by " + sender.getName());

                sendToggleResult(sender,
                        matchedSource + " logging", newState);
            }
        }
    }

    /**
     * Shows the full logging status page with clickable toggle buttons.
     */
    private void showLogStatus(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" Logging Status",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());

        // ── Global ──
        boolean globalOn = AddonLogger.isGlobalEnabled();
        sender.sendMessage(buildToggleLine(
                "Global Logging", globalOn,
                "/xa log all " + (globalOn ? "off" : "on"),
                globalOn ? "Click to disable ALL logs"
                         : "Click to enable ALL logs"));

        // ── Debug ──
        boolean debugOn = AddonLogger.isDebugEnabled();
        sender.sendMessage(buildToggleLine(
                "Debug Mode", debugOn,
                "/xa log debug " + (debugOn ? "off" : "on"),
                debugOn ? "Click to disable debug"
                        : "Click to enable debug"));

        // ── Sources ──
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Sources:",
                        NamedTextColor.GOLD));

        // Core first
        boolean coreOn = AddonLogger.isSourceEnabled("Core");
        sender.sendMessage(buildSourceLine(
                "Core", coreOn));

        // Then all modules
        for (String name : allModules.keySet()) {
            boolean srcOn = AddonLogger.isSourceEnabled(name);
            sender.sendMessage(buildSourceLine(name, srcOn));
        }

        // ── Usage hint ──
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text("  Tip: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                "/xa log <all|debug|source> <on|off>",
                                NamedTextColor.YELLOW)
                                .hoverEvent(HoverEvent.showText(
                                        Component.text(
                                                "Click to suggest",
                                                NamedTextColor.GRAY)))
                                .clickEvent(ClickEvent.suggestCommand(
                                        "/xa log "))));
        sender.sendMessage(Component.empty());
    }

    /**
     * Builds a toggle line with a status indicator and clickable button.
     *
     * <pre>
     *   Global Logging:   ON    [OFF]
     *   Debug Mode:       OFF   [ON]
     * </pre>
     */
    private Component buildToggleLine(String label, boolean isOn,
                                      String command, String hover) {
        Component status = isOn
                ? Component.text("ON ", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("OFF ", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        Component button = isOn
                ? Component.text("[OFF]", NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(
                                Component.text(hover,
                                        NamedTextColor.RED)))
                        .clickEvent(ClickEvent.runCommand(command))
                : Component.text("[ON]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(
                                Component.text(hover,
                                        NamedTextColor.GREEN)))
                        .clickEvent(ClickEvent.runCommand(command));

        // Pad label for alignment
        String padded = String.format("  %-20s", label + ":");

        return Component.text(padded, NamedTextColor.GRAY)
                .append(status)
                .append(Component.text("  "))
                .append(button);
    }

    /**
     * Builds a source toggle line with clickable button.
     *
     * <pre>
     *     Core            ON    [OFF]
     *     ChatReminder    ON    [OFF]
     * </pre>
     */
    private Component buildSourceLine(String sourceName,
                                      boolean isOn) {
        String command = "/xa log " + sourceName + " "
                + (isOn ? "off" : "on");
        String hover = isOn
                ? "Click to silence " + sourceName
                : "Click to enable " + sourceName + " logs";

        Component status = isOn
                ? Component.text("ON ", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("OFF ", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        Component button = isOn
                ? Component.text("[OFF]", NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(
                                Component.text(hover,
                                        NamedTextColor.RED)))
                        .clickEvent(ClickEvent.runCommand(command))
                : Component.text("[ON]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(
                                Component.text(hover,
                                        NamedTextColor.GREEN)))
                        .clickEvent(ClickEvent.runCommand(command));

        String padded = String.format("    %-18s", sourceName);

        return Component.text(padded, NamedTextColor.YELLOW)
                .append(status)
                .append(Component.text("  "))
                .append(button);
    }

    /**
     * Finds a source name by case-insensitive match.
     */
    private String findSourceName(String input) {
        if ("core".equalsIgnoreCase(input)) return "Core";

        for (String name : allModules.keySet()) {
            if (name.equalsIgnoreCase(input)) return name;
        }

        // Check registered sources
        for (String name : AddonLogger.getSourceNames()) {
            if (name.equalsIgnoreCase(input)) return name;
        }

        return null;
    }

    /**
     * Sends a standardized toggle result message.
     */
    private void sendToggleResult(CommandSender sender,
                                  String label, boolean newState) {
        Component status = newState
                ? Component.text("enabled", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                : Component.text("disabled", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD);

        sender.sendMessage(
                Component.text(label + " has been ",
                        NamedTextColor.GRAY)
                        .append(status)
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    /* ================================================================== */
    /*  Sub-command: reload                                                 */
    /* ================================================================== */

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.reload")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        log.info("Reload initiated by " + sender.getName());

        unregisterCommandAliases();
        loadMainConfig();

        int reloaded = 0, toggled = 0;
        for (Map.Entry<String, AddonModule> entry :
                allModules.entrySet()) {
            String name = entry.getKey();
            AddonModule module = entry.getValue();
            boolean shouldEnable = mainConfig.getBoolean(
                    "modules." + name + ".enabled", true);
            boolean isEnabled = enabledModules.containsKey(name);

            log.debug("Reload: " + name + " should=" + shouldEnable
                    + " is=" + isEnabled);

            if (shouldEnable && !isEnabled) {
                enableModule(module);
                toggled++;
            } else if (!shouldEnable && isEnabled) {
                disableModule(module);
                toggled++;
            } else if (shouldEnable) {
                try {
                    module.onReload();
                    reloaded++;
                    log.info("Reloaded: " + name);
                } catch (Exception e) {
                    log.error("Reload error: " + name, e);
                }
            }
        }

        registerCommandAliases();

        sender.sendMessage(
                Component.text("✔ ", NamedTextColor.GREEN)
                        .append(Component.text(
                                "XiannnAddons reloaded! ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                reloaded + " reloaded",
                                NamedTextColor.AQUA))
                        .append(Component.text(", ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                toggled + " toggled",
                                NamedTextColor.AQUA))
                        .append(Component.text(", ",
                                NamedTextColor.GRAY))
                        .append(Component.text(
                                enabledModules.size() + "/"
                                        + allModules.size()
                                        + " active",
                                NamedTextColor.GREEN))
                        .append(Component.text(".",
                                NamedTextColor.GRAY)));
    }

    /* ================================================================== */
    /*  Sub-command: modules                                                */
    /* ================================================================== */

    private void showModules(CommandSender sender) {
        if (!sender.hasPermission("xiannnaddons.modules")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" Modules ", NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text(
                                "(" + enabledModules.size() + "/"
                                        + allModules.size() + ")",
                                NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD,
                                        false)));
        sender.sendMessage(Component.empty());

        for (Map.Entry<String, AddonModule> entry :
                allModules.entrySet()) {
            String name = entry.getKey();
            boolean enabled = enabledModules.containsKey(name);

            Component status = enabled
                    ? Component.text(" ENABLED ",
                            NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD)
                    : Component.text(" DISABLED ",
                            NamedTextColor.RED)
                            .decorate(TextDecoration.BOLD);

            Component toggleBtn = enabled
                    ? Component.text(" [DISABLE]",
                            NamedTextColor.RED)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(
                                            "Click to disable " + name,
                                            NamedTextColor.RED)))
                            .clickEvent(ClickEvent.runCommand(
                                    "/xa disable " + name))
                    : Component.text(" [ENABLE]",
                            NamedTextColor.GREEN)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(
                                            "Click to enable " + name,
                                            NamedTextColor.GREEN)))
                            .clickEvent(ClickEvent.runCommand(
                                    "/xa enable " + name));

            Map<String, String> cmds =
                    entry.getValue().getCommands();
            Component cmdInfo = Component.empty();
            if (!cmds.isEmpty()) {
                cmdInfo = Component.text(" /" + String.join(
                        ", /", cmds.keySet()),
                        NamedTextColor.DARK_GRAY);
            }

            // Logging status indicator
            boolean logOn = AddonLogger.isSourceEnabled(name);
            Component logIcon = logOn
                    ? Component.text(" 📝",
                            NamedTextColor.GREEN)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(
                                            "Logging: ON\nClick to silence",
                                            NamedTextColor.GRAY)))
                            .clickEvent(ClickEvent.runCommand(
                                    "/xa log " + name + " off"))
                    : Component.text(" 🔇",
                            NamedTextColor.RED)
                            .hoverEvent(HoverEvent.showText(
                                    Component.text(
                                            "Logging: OFF\nClick to enable",
                                            NamedTextColor.GRAY)))
                            .clickEvent(ClickEvent.runCommand(
                                    "/xa log " + name + " on"));

            sender.sendMessage(
                    Component.text("  " + name,
                            NamedTextColor.YELLOW)
                            .append(status)
                            .append(toggleBtn)
                            .append(logIcon)
                            .append(cmdInfo));
        }

        sender.sendMessage(Component.empty());
    }

    /* ================================================================== */
    /*  Sub-command: enable / disable                                        */
    /* ================================================================== */

    private void handleToggleModule(CommandSender sender,
                                    String[] args, boolean enable) {
        if (!sender.hasPermission("xiannnaddons.modules")) {
            sender.sendMessage(
                    Component.text("No permission.",
                            NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(
                    Component.text("Usage: /xa "
                            + (enable ? "enable" : "disable")
                            + " <module>",
                            NamedTextColor.YELLOW));
            return;
        }

        String inputName = args[1];
        AddonModule module = null;
        String actualName = null;

        for (Map.Entry<String, AddonModule> entry :
                allModules.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(inputName)) {
                module = entry.getValue();
                actualName = entry.getKey();
                break;
            }
        }

        if (module == null) {
            sender.sendMessage(
                    Component.text("Module not found: ",
                            NamedTextColor.RED)
                            .append(Component.text(inputName,
                                    NamedTextColor.YELLOW)));
            return;
        }

        if (enable) {
            if (enabledModules.containsKey(actualName)) {
                sender.sendMessage(
                        Component.text(actualName
                                + " is already enabled.",
                                NamedTextColor.YELLOW));
                return;
            }
            enableModule(module);
            mainConfig.set("modules." + actualName + ".enabled",
                    true);
            saveMainConfig();
            sender.sendMessage(
                    Component.text("✔ ", NamedTextColor.GREEN)
                            .append(Component.text(actualName,
                                    NamedTextColor.YELLOW))
                            .append(Component.text(
                                    " has been enabled.",
                                    NamedTextColor.GREEN)));
        } else {
            if (!enabledModules.containsKey(actualName)) {
                sender.sendMessage(
                        Component.text(actualName
                                + " is already disabled.",
                                NamedTextColor.YELLOW));
                return;
            }
            disableModule(module);
            mainConfig.set("modules." + actualName + ".enabled",
                    false);
            saveMainConfig();
            sender.sendMessage(
                    Component.text("✔ ", NamedTextColor.GREEN)
                            .append(Component.text(actualName,
                                    NamedTextColor.YELLOW))
                            .append(Component.text(
                                    " has been disabled.",
                                    NamedTextColor.RED)));
        }
    }

    /* ================================================================== */
    /*  Sub-command: version                                                */
    /* ================================================================== */

    private void showVersion(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(
                Component.text(" XiannnAddons",
                        NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD));
        sender.sendMessage(
                Component.text("  Version: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                getPluginMeta().getVersion(),
                                NamedTextColor.WHITE)));
        sender.sendMessage(
                Component.text("  Author: ",
                        NamedTextColor.GRAY)
                        .append(Component.text("Xiannn",
                                NamedTextColor.YELLOW)));
        sender.sendMessage(
                Component.text("  Platform: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                Bukkit.getName() + " "
                                        + Bukkit.getMinecraftVersion(),
                                NamedTextColor.AQUA)));
        sender.sendMessage(
                Component.text("  Java: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                System.getProperty("java.version"),
                                NamedTextColor.AQUA)));
        sender.sendMessage(
                Component.text("  Modules: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(
                                enabledModules.size() + "/"
                                        + allModules.size()
                                        + " enabled",
                                NamedTextColor.GREEN)));

        // Logging summary
        String logSummary = "global="
                + (AddonLogger.isGlobalEnabled() ? "ON" : "OFF")
                + ", debug="
                + (AddonLogger.isDebugEnabled() ? "ON" : "OFF");
        sender.sendMessage(
                Component.text("  Logging: ",
                        NamedTextColor.GRAY)
                        .append(Component.text(logSummary,
                                NamedTextColor.AQUA)));

        sender.sendMessage(Component.empty());
    }

    /* ================================================================== */
    /*  Console banner                                                      */
    /* ================================================================== */

    private void printBanner() {
        log.divider();
        log.info("  XiannnAddons v"
                + getPluginMeta().getVersion());
        log.info("  Modules: " + enabledModules.size()
                + "/" + allModules.size() + " enabled");
        log.info("  Logging: global="
                + (AddonLogger.isGlobalEnabled() ? "ON" : "OFF")
                + ", debug="
                + (AddonLogger.isDebugEnabled() ? "ON" : "OFF"));
        log.divider();
    }

    /* ================================================================== */
    /*  Public API                                                          */
    /* ================================================================== */

    public File getModuleFolder(AddonModule module) {
        return new File(getDataFolder(), module.getModuleName());
    }

    public boolean isModuleEnabled(String name) {
        return enabledModules.containsKey(name);
    }

    public List<AddonModule> getModules() {
        return List.copyOf(allModules.values());
    }

    public AddonLogger getAddonLogger() {
        return log;
    }

    /* ================================================================== */
    /*  Alias command                                                        */
    /* ================================================================== */

    private static class AliasCommand extends Command {

        private final XiannnAddons plugin;
        private final String       targetCommand;

        protected AliasCommand(String alias, XiannnAddons plugin,
                               String targetCommand) {
            super(alias);
            this.plugin        = plugin;
            this.targetCommand = targetCommand;
            setDescription("Shortcut for /xa " + targetCommand);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender,
                               @NotNull String label,
                               @NotNull String[] args) {
            if (!plugin.isEnabled()) return false;
            plugin.log.debug("Alias /" + label
                    + " → " + targetCommand);
            return plugin.routeModuleCommand(
                    sender, targetCommand, args);
        }

        @Override
        public @NotNull List<String> tabComplete(
                @NotNull CommandSender sender,
                @NotNull String alias,
                @NotNull String[] args) {
            if (!plugin.isEnabled())
                return Collections.emptyList();
            return plugin.routeModuleTabComplete(
                    sender, targetCommand, args);
        }
    }
}
