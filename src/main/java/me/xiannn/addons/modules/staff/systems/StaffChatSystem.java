package me.xiannn.addons.modules.staff.systems;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.xiannn.addons.modules.staff.StaffConfig;
import me.xiannn.addons.modules.staff.StaffModule;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StaffChatSystem implements Listener {

    private final StaffModule module;
    private final Set<UUID> toggleModePlayers = new HashSet<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public StaffChatSystem(StaffModule module) {
        this.module = module;
        module.getPlugin().getServer().getPluginManager().registerEvents(this, module.getPlugin());
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>Player only."));
            return true;
        }

        if (!player.hasPermission("xiannnaddons.staff.chat")) {
            sender.sendMessage(miniMessage.deserialize("<red>No permission."));
            return true;
        }

        if (args.length > 0) {
            // /sc <message> -> Send single message
            String message = String.join(" ", args);
            broadcast(player, message);
        } else {
            // /sc -> Toggle mode
            if (toggleModePlayers.contains(player.getUniqueId())) {
                toggleModePlayers.remove(player.getUniqueId());
                player.sendMessage(miniMessage.deserialize(
                    module.getConfigManager().getConfig().getString("messages.staff-chat.toggle-off", "<red>Staff chat disabled.")));
            } else {
                toggleModePlayers.add(player.getUniqueId());
                player.sendMessage(miniMessage.deserialize(
                    module.getConfigManager().getConfig().getString("messages.staff-chat.toggle-on", "<green>Staff chat enabled.")));
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!module.getConfigManager().isStaffChatEnabled()) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("xiannnaddons.staff.chat")) return;

        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        String prefix = module.getConfigManager().getConfig().getString("staff-chat.prefix-char", "#");

        boolean isStaffMessage = false;

        // Check Prefix
        if (plainMessage.startsWith(prefix)) {
            plainMessage = plainMessage.substring(prefix.length()).trim();
            if (!plainMessage.isEmpty()) {
                isStaffMessage = true;
            }
        } 
        // Check Toggle Mode
        else if (toggleModePlayers.contains(player.getUniqueId())) {
            isStaffMessage = true;
        }

        if (isStaffMessage) {
            event.setCancelled(true); // Don't send to public chat
            broadcast(player, plainMessage);
        }
    }

    private void broadcast(Player sender, String message) {
        StaffConfig.StaffRank rank = module.getConfigManager().getStaffRank(sender);
        String rankDisplay = rank != null ? rank.displayName : "";
        String rankColor = rank != null ? rank.displayColor : "#FFFFFF";

        // Format Message
        String format = module.getConfigManager().getStaffChatFormat();
        // Simple replace for placeholders (Adventure usually recommends Templates but this is quicker for config strings)
        format = format.replace("{rank}", rankDisplay)
                       .replace("{name}", sender.getName())
                       .replace("{message}", message)
                       .replace("{rank_color}", rankColor);

        Component component = miniMessage.deserialize(format);

        // Send to online staff
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("xiannnaddons.staff.chat")) {
                p.sendMessage(component);
                playSound(p);
            }
        }
        
        // Log to Console
        module.getPlugin().getComponentLogger().info(component);

        // Log to Discord
        logToDiscord(sender, rankDisplay, message);
    }

    private void playSound(Player p) {
        if (module.getConfigManager().getConfig().getBoolean("staff-chat.sound.enabled")) {
            try {
                String key = module.getConfigManager().getConfig().getString("staff-chat.sound.type");
                float vol = (float) module.getConfigManager().getConfig().getDouble("staff-chat.sound.volume");
                float pitch = (float) module.getConfigManager().getConfig().getDouble("staff-chat.sound.pitch");
                
                // Parse key properly (namespace:key)
                net.kyori.adventure.key.Key soundKey = key.contains(":") ? 
                    net.kyori.adventure.key.Key.key(key) : 
                    net.kyori.adventure.key.Key.key("minecraft", key);

                p.playSound(Sound.sound(soundKey, Sound.Source.MASTER, vol, pitch));
            } catch (Exception ignored) {}
        }
    }

    private void logToDiscord(Player sender, String rank, String message) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{staff}", sender.getName());
        placeholders.put("{rank}", rank);
        placeholders.put("{message}", message);
        
        if (module.getDiscord() != null) {
            module.getDiscord().log("staff-chat", "staff-chat", placeholders);
        }
    }
}
