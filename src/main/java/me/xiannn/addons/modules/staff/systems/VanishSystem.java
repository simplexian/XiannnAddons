package me.xiannn.addons.modules.staff.systems;

import me.xiannn.addons.modules.staff.StaffModule;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class VanishSystem implements Listener {

    private final StaffModule module;
    private final Set<UUID> vanishedPlayers = new HashSet<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public VanishSystem(StaffModule module) {
        this.module = module;
        module.getPlugin().getServer().getPluginManager().registerEvents(this, module.getPlugin());
    }

    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!player.hasPermission("xiannnaddons.staff.vanish")) {
            player.sendMessage(miniMessage.deserialize("<red>No permission."));
            return true;
        }

        toggleVanish(player);
        return true;
    }

    private void toggleVanish(Player player) {
        if (vanishedPlayers.contains(player.getUniqueId())) {
            disableVanish(player);
        } else {
            enableVanish(player);
        }
    }

    private void enableVanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        
        // Hide from online players
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!canSee(online, player)) {
                online.hidePlayer(module.getPlugin(), player);
            }
        }

        // Effects
        if (module.getConfigManager().getConfig().getBoolean("vanish.features.night-vision")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false));
        }
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setSleepingIgnored(true);

        // Notify
        player.sendMessage(miniMessage.deserialize(
            module.getConfigManager().getConfig().getString("messages.vanish.enabled", "<green>Vanish enabled.")
        ));

        // Fake Leave Message
        if (module.getConfigManager().getConfig().getBoolean("vanish.fake-messages.enabled")) {
            String msg = module.getConfigManager().getConfig().getString("vanish.fake-messages.leave");
            if (msg != null) Bukkit.broadcast(miniMessage.deserialize(msg.replace("{name}", player.getName())));
        }

        // Discord Log
        logToDiscord(player, "enabled");
    }

    private void disableVanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());

        // Show to everyone
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showPlayer(module.getPlugin(), player);
        }

        // Remove Effects
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.setSleepingIgnored(false);
        // Note: We don't auto-disable flight to prevent falling death

        // Notify
        player.sendMessage(miniMessage.deserialize(
            module.getConfigManager().getConfig().getString("messages.vanish.disabled", "<red>Vanish disabled.")
        ));

        // Fake Join Message
        if (module.getConfigManager().getConfig().getBoolean("vanish.fake-messages.enabled")) {
            String msg = module.getConfigManager().getConfig().getString("vanish.fake-messages.join");
            if (msg != null) Bukkit.broadcast(miniMessage.deserialize(msg.replace("{name}", player.getName())));
        }

        // Discord Log
        logToDiscord(player, "disabled");
    }

    private boolean canSee(Player observer, Player target) {
        // Observer is the person LOOKING
        // Target is the VANISHED person
        if (!vanishedPlayers.contains(target.getUniqueId())) return true;
        
        // Config: staff-see-vanished
        if (module.getConfigManager().getConfig().getBoolean("vanish.features.staff-see-vanished")) {
            return observer.hasPermission("xiannnaddons.staff.vanish");
        }
        return false;
    }

    // ── Listeners ────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joiner = event.getPlayer();

        // 1. Hide existing vanished players from joiner
        for (UUID uuid : vanishedPlayers) {
            Player vanished = Bukkit.getPlayer(uuid);
            if (vanished != null && !canSee(joiner, vanished)) {
                joiner.hidePlayer(module.getPlugin(), vanished);
            }
        }

        // 2. Handle joiner's own vanish state (if persistent)
        // For simplicity in this version, we don't persist across restart (per Q17 answer: No)
        // But if they rejoin same session, we assume visible unless logic added later.
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        vanishedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            if (module.getConfigManager().getConfig().getBoolean("vanish.features.no-item-pickup")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && isVanished(player)) {
            if (module.getConfigManager().getConfig().getBoolean("vanish.features.no-mob-targeting")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isVanished(event.getPlayer()) && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (module.getConfigManager().getConfig().getBoolean("vanish.features.silent-chest")) {
                // Chest silence is complex (needs packets), simplified here:
                // Just cancel the sound/animation if possible, or use spectator mode logic.
                // For MVP, we let them open, but true silence requires ProtocolLib usually.
                // Bukkit doesn't have a clean "silent open" API without NMS/ProtocolLib.
            }
        }
    }

    private void logToDiscord(Player player, String state) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{staff}", player.getName());
        placeholders.put("{state}", state);
        
        if (module.getDiscord() != null) {
            module.getDiscord().log("vanish", "vanish", placeholders);
        }
    }
}
