package me.xiannn.addons.modules.staff.systems;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.xiannn.addons.modules.staff.StaffConfig;
import me.xiannn.addons.modules.staff.StaffModule;
import me.xiannn.addons.modules.staff.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PunishmentSystem implements Listener {

    private final StaffModule module;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PunishmentSystem(StaffModule module) {
        this.module = module;
        module.getPlugin().getServer().getPluginManager().registerEvents(this, module.getPlugin());
    }

    // ── Command Routing ──────────────────────────────────────────────

    public boolean handleCommand(CommandSender sender, String command, String[] args) {
        if (!sender.hasPermission("xiannnaddons.staff.punish")) {
            sender.sendMessage(miniMessage.deserialize("<red>No permission."));
            return true;
        }

        switch (command.toLowerCase()) {
            case "ban" -> handleBan(sender, args, true);
            case "tempban" -> handleBan(sender, args, false);
            case "mute" -> handleMute(sender, args, true);
            case "tempmute" -> handleMute(sender, args, false);
            case "kick" -> handleKick(sender, args);
            case "warn" -> handleWarn(sender, args);
            case "history" -> handleHistory(sender, args);
            case "alts" -> handleAlts(sender, args);
            case "unban" -> handleUnban(sender, args);
            case "unmute" -> handleUnmute(sender, args);
            case "punish" -> {
                // If args, treat as preset shortcut. If no args, open GUI (handled by StaffGUISystem)
                if (args.length > 0) handlePreset(sender, args);
                else return false; // Fallback to GUI routing
            }
        }
        return true;
    }

    // ── Logic: Ban ───────────────────────────────────────────────────

    private void handleBan(CommandSender sender, String[] args, boolean perm) {
        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /ban <player> [time] <reason>"));
            return;
        }

        final String targetName = args[0];
        long duration = 0;
        int reasonIndex = 1;

        // Parse duration if present (e.g. 1d, 30m)
        if (args.length > 2 && Character.isDigit(args[1].charAt(0))) {
            duration = TimeUtil.parseDuration(args[1]);
            reasonIndex = 2;
        }

        // Reconstruct reason
        StringBuilder sb = new StringBuilder();
        for (int i = reasonIndex; i < args.length; i++) sb.append(args[i]).append(" ");
        final String reason = sb.toString().trim().isEmpty() ? "No reason provided" : sb.toString().trim();

        // Capture variables for lambda
        final long finalDuration = duration;
        final String staffName = sender.getName();

        // Execute async
        Bukkit.getScheduler().runTaskAsynchronously(module.getPlugin(), () -> {
            UUID targetUUID = resolveUUID(targetName);
            if (targetUUID == null) {
                sender.sendMessage(miniMessage.deserialize("<red>Player not found."));
                return;
            }

            // Rank Check
            Player targetOnline = Bukkit.getPlayer(targetUUID);
            if (sender instanceof Player p && targetOnline != null && !module.getConfigManager().canPunish(p, targetOnline)) {
                sender.sendMessage(miniMessage.deserialize("<red>You cannot punish this player (higher rank)."));
                return;
            }

            // DB Insert
            String type = finalDuration > 0 ? "TEMPORARY" : "PERMANENT";
            long expires = finalDuration > 0 ? System.currentTimeMillis() + finalDuration : 0;
            String appealId = UUID.randomUUID().toString().substring(0, 8);

            try (Connection conn = module.getDatabase().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO bans (player_uuid, staff_uuid, reason, type, created, expires, active, appeal_id) VALUES (?, ?, ?, ?, ?, ?, 1, ?)")) {
                ps.setString(1, targetUUID.toString());
                ps.setString(2, sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE");
                ps.setString(3, reason);
                ps.setString(4, type);
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, expires);
                ps.setString(7, appealId);
                ps.executeUpdate();
            } catch (SQLException e) {
                module.getLog().error("Failed to ban " + targetName, e);
                return;
            }

            // Kick if online (Sync)
            final long finalExpires = expires;
            final String finalAppealId = appealId;

            Bukkit.getScheduler().runTask(module.getPlugin(), () -> {
                Player target = Bukkit.getPlayer(targetUUID);
                if (target != null) {
                    target.kick(formatBanScreen(reason, finalExpires, finalAppealId));
                }
                broadcast("ban", targetName, staffName, reason);
            });

            // Log to Discord
            Map<String, String> logMap = new HashMap<>();
            logMap.put("{player}", targetName);
            logMap.put("{staff}", staffName);
            logMap.put("{reason}", reason);
            logMap.put("{type}", type);
            logMap.put("{duration}", finalDuration > 0 ? TimeUtil.formatDuration(finalDuration) : "Permanent");
            logMap.put("{expires}", finalExpires > 0 ? TimeUtil.formatDuration(finalExpires - System.currentTimeMillis()) : "Never");
            logMap.put("{appeal_id}", finalAppealId);

            logToDiscord("punishments", "ban", logMap);
        });
    }

    // ── Logic: Mute ──────────────────────────────────────────────────

    private void handleMute(CommandSender sender, String[] args, boolean perm) {
        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /mute <player> [time] <reason>"));
            return;
        }

        final String targetName = args[0];
        long duration = 0;
        int reasonIndex = 1;

        if (args.length > 2 && Character.isDigit(args[1].charAt(0))) {
            duration = TimeUtil.parseDuration(args[1]);
            reasonIndex = 2;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = reasonIndex; i < args.length; i++) sb.append(args[i]).append(" ");
        final String reason = sb.toString().trim().isEmpty() ? "No reason provided" : sb.toString().trim();

        final long finalDuration = duration;
        final String staffName = sender.getName();

        Bukkit.getScheduler().runTaskAsynchronously(module.getPlugin(), () -> {
            UUID targetUUID = resolveUUID(targetName);
            if (targetUUID == null) {
                sender.sendMessage(miniMessage.deserialize("<red>Player not found."));
                return;
            }

            String type = finalDuration > 0 ? "TEMPORARY" : "PERMANENT";
            long expires = finalDuration > 0 ? System.currentTimeMillis() + finalDuration : 0;

            try (Connection conn = module.getDatabase().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO mutes (player_uuid, staff_uuid, reason, type, created, expires, active) VALUES (?, ?, ?, ?, ?, ?, 1)")) {
                ps.setString(1, targetUUID.toString());
                ps.setString(2, sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE");
                ps.setString(3, reason);
                ps.setString(4, type);
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, expires);
                ps.executeUpdate();
            } catch (SQLException e) {
                module.getLog().error("Failed to mute " + targetName, e);
            }

            final long finalExpires = expires;
            logToDiscord("punishments", "mute", Map.of(
                "{player}", targetName,
                "{staff}", staffName,
                "{reason}", reason,
                "{type}", type,
                "{duration}", finalDuration > 0 ? TimeUtil.formatDuration(finalDuration) : "Permanent",
                "{expires}", finalExpires > 0 ? TimeUtil.formatDuration(finalExpires - System.currentTimeMillis()) : "Never"
            ));
        });
    }

    // ── Logic: Kick ──────────────────────────────────────────────────

    private void handleKick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /kick <player> <reason>"));
            return;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(miniMessage.deserialize("<red>Player not online."));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) sb.append(args[i]).append(" ");
        final String reason = sb.toString().trim();
        final String staffName = sender.getName();

        // Rank Check
        if (sender instanceof Player p && !module.getConfigManager().canPunish(p, target)) {
            sender.sendMessage(miniMessage.deserialize("<red>Cannot kick higher rank."));
            return;
        }

        final String targetName = target.getName();
        final UUID targetUUID = target.getUniqueId();

        // DB Log
        Bukkit.getScheduler().runTaskAsynchronously(module.getPlugin(), () -> {
            try (Connection conn = module.getDatabase().getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO kicks (player_uuid, staff_uuid, reason, created) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, targetUUID.toString());
                ps.setString(2, sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE");
                ps.setString(3, reason);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) { 
                module.getLog().error("Kick log failed", e); 
            }
        });

        // Action
        target.kick(miniMessage.deserialize(
            module.getConfigManager().getConfig().getString("messages.kick-screen", "<red>Kicked")
            .replace("{reason}", reason)
        ));
        broadcast("kick", targetName, staffName, reason);
    }

    // ── Listeners: Enforce Punishments ───────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        // Check Active Ban
        try (Connection conn = module.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM bans WHERE player_uuid=? AND active=1 AND (expires=0 OR expires > ?)")) {
            
            ps.setString(1, event.getPlayer().getUniqueId().toString());
            ps.setLong(2, System.currentTimeMillis());
            
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String reason = rs.getString("reason");
                long expires = rs.getLong("expires");
                String appealId = rs.getString("appeal_id");
                
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, formatBanScreen(reason, expires, appealId));
            }
        } catch (SQLException e) {
            module.getLog().error("DB Error on login check", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        // Check Active Mute
        try (Connection conn = module.getDatabase().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT * FROM mutes WHERE player_uuid=? AND active=1 AND (expires=0 OR expires > ?)")) {
            
            ps.setString(1, event.getPlayer().getUniqueId().toString());
            ps.setLong(2, System.currentTimeMillis());
            
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                event.setCancelled(true);
                String reason = rs.getString("reason");
                long expires = rs.getLong("expires");
                
                // Notify player
                String msg = module.getConfigManager().getConfig().getString("messages.mute-message", "<red>Muted.");
                msg = msg.replace("{reason}", reason)
                         .replace("{expires}", expires == 0 ? "Never" : TimeUtil.formatDuration(expires - System.currentTimeMillis()));
                event.getPlayer().sendMessage(miniMessage.deserialize(msg));
            }
        } catch (SQLException e) {
            module.getLog().error("DB Error on chat check", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private UUID resolveUUID(String name) {
        // 1. Online
        Player p = Bukkit.getPlayer(name);
        if (p != null) return p.getUniqueId();
        
        // 2. DB Cache (Offline)
        // Simplified for brevity - would query 'players' table
        return null;
    }

    private Component formatBanScreen(String reason, long expires, String appealId) {
        String raw = module.getConfigManager().getConfig().getString("messages.ban-screen", "<red>Banned");
        String appealUrl = module.getConfigManager().getConfig().getString("punishments.appeal-url", "https://discord.gg/");
        String expireStr = expires == 0 ? "Never" : TimeUtil.formatDuration(expires - System.currentTimeMillis());
        
        return miniMessage.deserialize(raw
            .replace("{reason}", reason)
            .replace("{expires}", expireStr)
            .replace("{appeal_id}", appealId != null ? appealId : "Unknown")
            .replace("{appeal_url}", appealUrl));
    }

    private void broadcast(String type, String player, String staff, String reason) {
        String msg = module.getConfigManager().getConfig().getString("messages.broadcasts." + type);
        if (msg == null || msg.isEmpty()) return;
        
        Bukkit.broadcast(miniMessage.deserialize(msg
            .replace("{player}", player)
            .replace("{staff}", staff)
            .replace("{reason}", reason)));
    }

    private void logToDiscord(String category, String template, Map<String, String> placeholders) {
        if (module.getDiscord() != null) module.getDiscord().log(category, template, placeholders);
    }

    private void handlePreset(CommandSender sender, String[] args) {
        // /punish <player> <preset>
        // Read config presets, execute commands as console/sender
    }
    
    // Stub methods for other commands
    private void handleWarn(CommandSender s, String[] a) {}
    private void handleHistory(CommandSender s, String[] a) {}
    private void handleAlts(CommandSender s, String[] a) {}
    private void handleUnban(CommandSender s, String[] a) {}
    private void handleUnmute(CommandSender s, String[] a) {}
}
