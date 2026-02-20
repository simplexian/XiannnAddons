package me.xiannn.addons;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Contract that every feature module must implement.
 * <p>
 * <b>Lifecycle</b> — the main class calls {@link #onEnable()},
 * {@link #onDisable()}, and {@link #onReload()} at appropriate times.
 * If a module also implements {@link org.bukkit.event.Listener},
 * events are wired automatically.
 * <p>
 * <b>Commands</b> — modules declare commands via {@link #getCommands()}
 * and handle them via {@link #handleCommand} / {@link #handleTabComplete}.
 * The main class routes both {@code /xa <cmd>} and dynamic aliases.
 * <p>
 * <b>Logging</b> — each module should create an {@link AddonLogger}
 * in its constructor for consistent, prefixed log output:
 * <pre>
 * private final AddonLogger log;
 *
 * public MyModule(XiannnAddons plugin) {
 *     this.plugin = plugin;
 *     this.log    = new AddonLogger(plugin, getModuleName());
 * }
 * </pre>
 */
public interface AddonModule {

    /** Called when the module is enabled. Config folder already exists. */
    void onEnable();

    /** Called when the module is disabled. Persist state here. */
    void onDisable();

    /**
     * Called when {@code /xa reload} is executed and this module
     * is already enabled. Reload configs / restart tasks here.
     */
    default void onReload() { }

    /**
     * Human-readable name used for logging, config keys, and
     * the per-module config folder name.
     */
    default String getModuleName() {
        return getClass().getSimpleName();
    }

    /**
     * Commands this module provides.
     * <p>
     * Key = command name (e.g. {@code "tips"}), used for routing.<br>
     * Value = short description shown in {@code /xa help}.
     *
     * @return map of commands (empty if none)
     */
    default Map<String, String> getCommands() {
        return Collections.emptyMap();
    }

    /**
     * Handle a command routed to this module.
     *
     * @param sender  who ran the command
     * @param command the command name (e.g. {@code "tips"})
     * @param args    arguments after the command name
     * @return true if handled
     */
    default boolean handleCommand(@NotNull CommandSender sender,
                                  @NotNull String command,
                                  @NotNull String[] args) {
        return false;
    }

    /**
     * Tab-complete a command routed to this module.
     *
     * @param sender  who is tab-completing
     * @param command the command name
     * @param args    current arguments
     * @return list of completions
     */
    default List<String> handleTabComplete(@NotNull CommandSender sender,
                                           @NotNull String command,
                                           @NotNull String[] args) {
        return Collections.emptyList();
    }
}
