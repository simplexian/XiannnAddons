package me.xiannn.addons.modules.staff.systems;

import me.xiannn.addons.modules.staff.StaffConfig;
import me.xiannn.addons.modules.staff.StaffModule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GameModeSystem implements Listener {

    private final StaffModule module;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public GameModeSystem(StaffModule module) {
        this.module = module;
        module.getPlugin().getServer().getPluginManager().registerEvents(this, module.getPlugin());
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission("xiannnaddons.staff.gamemode")) {
            player.sendMessage(miniMessage.deserialize("<red>No permission."));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(miniMessage.deserialize("<yellow>Usage: /gm <0|1|2|3|s|c|a|sp>"));
            return true;
        }

        GameMode mode = parseGameMode(args[0]);
        if (mode == null) {
            player.sendMessage(miniMessage.deserialize("<red>Invalid gamemode."));
            return true;
        }

        player.setGameMode(mode);
        // Event listener below handles inventory swap & logging
        return true;
    }

    private GameMode parseGameMode(String arg) {
        return switch (arg.toLowerCase()) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    // ── Logic: Inventory Swap & Logging ──────────────────────────────

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode from = player.getGameMode();
        GameMode to = event.getNewGameMode();

        if (from == to) return;

        // 1. Separate Inventories
        if (module.getConfigManager().getConfig().getBoolean("gamemode.separate-inventories")) {
            saveInventory(player, from);
            // Clear current inv to prevent mixing before load
            player.getInventory().clear(); 
            // Load next tick to ensure GM switch happened
            module.getPlugin().getServer().getScheduler().runTask(module.getPlugin(), () -> {
                loadInventory(player, to);
            });
        }

        // 2. Discord Log
        logToDiscord(player, from, to);
        
        // 3. Notify
        String msg = module.getConfigManager().getConfig().getString("messages.gamemode.changed", "<green>Gamemode changed.");
        player.sendMessage(miniMessage.deserialize(msg.replace("{mode}", to.name())));
    }

    private void saveInventory(Player player, GameMode mode) {
        File file = getInvFile(player, mode);
        YamlConfiguration cfg = new YamlConfiguration();
        
        cfg.set("inventory", player.getInventory().getContents());
        cfg.set("armor", player.getInventory().getArmorContents());
        cfg.set("extra", player.getInventory().getExtraContents());
        cfg.set("xp", player.getExp());
        cfg.set("level", player.getLevel());
        cfg.set("food", player.getFoodLevel());
        cfg.set("health", player.getHealth());

        try {
            cfg.save(file);
        } catch (IOException e) {
            module.getLog().error("Failed to save inventory for " + player.getName(), e);
        }
    }

    private void loadInventory(Player player, GameMode mode) {
        File file = getInvFile(player, mode);
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        
        // Safety: ensure list isn't null
        List<ItemStack> inv = (List<ItemStack>) cfg.getList("inventory");
        if (inv != null) player.getInventory().setContents(inv.toArray(new ItemStack[0]));

        List<ItemStack> armor = (List<ItemStack>) cfg.getList("armor");
        if (armor != null) player.getInventory().setArmorContents(armor.toArray(new ItemStack[0]));
        
        List<ItemStack> extra = (List<ItemStack>) cfg.getList("extra");
        if (extra != null) player.getInventory().setExtraContents(extra.toArray(new ItemStack[0]));

        player.setExp((float) cfg.getDouble("xp", 0));
        player.setLevel(cfg.getInt("level", 0));
        player.setFoodLevel(cfg.getInt("food", 20));
        player.setHealth(cfg.getDouble("health", 20));
    }

    private File getInvFile(Player player, GameMode mode) {
        File folder = new File(module.getPlugin().getModuleFolder(module), "inventories");
        if (!folder.exists()) folder.mkdirs();
        return new File(folder, player.getUniqueId() + "_" + mode.name().toLowerCase() + ".yml");
    }

    private void logToDiscord(Player player, GameMode from, GameMode to) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{staff}", player.getName());
        placeholders.put("{from}", from.name());
        placeholders.put("{to}", to.name());
        placeholders.put("{world}", player.getWorld().getName());
        
        if (module.getDiscord() != null) {
            module.getDiscord().log("gamemode", "gamemode", placeholders);
        }
    }

    // ── Anti-Abuse Listeners ─────────────────────────────────────────

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!shouldRestrict(event.getPlayer(), "gamemode.restrictions.block-drop-items")) return;
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            event.setCancelled(true);
            sendRestrictedMessage(event.getPlayer());
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (!shouldRestrict(event.getPlayer(), "gamemode.restrictions.block-item-pickup")) return;
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.CREATIVE) return;

        // Container Check
        if (event.getClickedInventory() != null && event.getClickedInventory().getType() != org.bukkit.event.inventory.InventoryType.PLAYER) {
             if (shouldRestrict(player, "gamemode.restrictions.block-container-access")) {
                 event.setCancelled(true);
                 sendRestrictedMessage(player);
             }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!module.getConfigManager().getConfig().getBoolean("staff-mode.log-commands")) return;
        
        // Log all commands if in staff mode (simplified: if has permission)
        // Or if in Creative/Spectator
        Player p = event.getPlayer();
        if (p.hasPermission("xiannnaddons.staff.gamemode")) {
            List<String> exempt = module.getConfigManager().getConfig().getStringList("staff-mode.command-log-exempt");
            String cmd = event.getMessage().split(" ")[0];
            if (exempt.contains(cmd)) return;

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{staff}", p.getName());
            placeholders.put("{command}", event.getMessage());
            placeholders.put("{world}", p.getWorld().getName());
            
            if (module.getDiscord() != null) {
                module.getDiscord().log("commands", "command-log", placeholders);
            }
        }
    }

    private boolean shouldRestrict(Player player, String configKey) {
        if (!module.getConfigManager().getConfig().getBoolean(configKey + ".enabled")) return false;
        
        int maxTier = module.getConfigManager().getConfig().getInt(configKey + ".max-tier");
        StaffConfig.StaffRank rank = module.getConfigManager().getStaffRank(player);
        
        // If no rank (OP/Console), usually bypass, but if they have staff permission they have a rank.
        // If rank is null (not staff), they shouldn't be in creative anyway, but if they are, we restrict.
        if (rank == null) return true; 

        // Apply restriction if player tier <= maxTier
        // e.g. Max=2. Helper(1) <= 2 (Restricted). Admin(3) > 2 (Bypass).
        return rank.tier <= maxTier;
    }

    private void sendRestrictedMessage(Player player) {
        String msg = module.getConfigManager().getConfig().getString("messages.gamemode.restricted", "<red>Restricted in Creative.");
        player.sendMessage(miniMessage.deserialize(msg));
    }
}
