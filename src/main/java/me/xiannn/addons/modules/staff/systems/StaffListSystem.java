package me.xiannn.addons.modules.staff.systems;

import me.xiannn.addons.modules.staff.StaffConfig;
import me.xiannn.addons.modules.staff.StaffModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StaffListSystem {

    private final StaffModule module;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public StaffListSystem(StaffModule module) {
        this.module = module;
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xiannnaddons.staff.list")) {
            sender.sendMessage(miniMessage.deserialize("<red>No permission."));
            return true;
        }

        showStaffList(sender);
        return true;
    }

    private void showStaffList(CommandSender viewer) {
        viewer.sendMessage(miniMessage.deserialize("<gold><bold>Online Staff:"));

        List<StaffEntry> onlineStaff = new ArrayList<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            StaffConfig.StaffRank rank = module.getConfigManager().getStaffRank(p);
            if (rank != null) {
                // Check Vanish visibility
                boolean isVanished = false;
                if (module.getVanish() != null) {
                    isVanished = module.getVanish().isVanished(p);
                }

                // If vanished, only show to those who can see vanished staff
                if (isVanished && !viewer.hasPermission("xiannnaddons.staff.vanish")) {
                    continue; 
                }

                onlineStaff.add(new StaffEntry(p, rank, isVanished));
            }
        }

        // Sort by Tier (Descending)
        onlineStaff.sort(Comparator.comparingInt((StaffEntry e) -> e.rank.tier).reversed());

        if (onlineStaff.isEmpty()) {
            viewer.sendMessage(miniMessage.deserialize("<gray>No staff online."));
        } else {
            for (StaffEntry entry : onlineStaff) {
                String line = formatEntry(entry);
                viewer.sendMessage(miniMessage.deserialize(line));
            }
        }
    }

    private String formatEntry(StaffEntry entry) {
        // Format: [Rank] Name [V] [GM]
        String rankPrefix = "<color:" + entry.rank.displayColor + ">" + entry.rank.displayName + "</color> ";
        String name = "<white>" + entry.player.getName() + "</white>";
        
        String tags = "";
        
        // Vanish Tag
        if (entry.isVanished) {
            String vTag = module.getConfigManager().getConfig().getString("vanish.vanish-indicator", "&7[V]");
            // Convert legacy & to <color> roughly or assume config uses MiniMessage format
            tags += " " + vTag; 
        }

        // Gamemode Tag
        switch (entry.player.getGameMode()) {
            case CREATIVE -> tags += " <gray>[C]</gray>";
            case SPECTATOR -> tags += " <gray>[SP]</gray>";
            case ADVENTURE -> tags += " <gray>[A]</gray>";
            default -> {} // Survival - no tag
        }

        return "  " + rankPrefix + name + tags;
    }

    private static class StaffEntry {
        final Player player;
        final StaffConfig.StaffRank rank;
        final boolean isVanished;

        StaffEntry(Player player, StaffConfig.StaffRank rank, boolean isVanished) {
            this.player = player;
            this.rank = rank;
            this.isVanished = isVanished;
        }
    }
}
