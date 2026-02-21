package me.xiannn.addons.modules.staff;

import me.xiannn.addons.AddonLogger;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Manages configuration and the Staff Rank Hierarchy.
 */
public class StaffConfig {

    private final StaffModule module;
    private final AddonLogger log;
    
    private File configFile;
    private YamlConfiguration config;

    // Rank Cache: Tier -> RankDefinition
    // TreeMap for easy sorting (Higher key = Higher tier)
    private final TreeMap<Integer, StaffRank> rankLadder = new TreeMap<>();
    private boolean useLuckPerms = false;

    public StaffConfig(StaffModule module) {
        this.module = module;
        this.log = module.getLog();
    }

    public void load() {
        configFile = new File(module.getPlugin().getModuleFolder(module), "config.yml");
        
        if (!configFile.exists()) {
            // Main plugin handles resource saving usually, but we ensure it exists
            module.getPlugin().saveResource("StaffModule/config.yml", false); 
            // Note: Since we are in a sub-module structure, we might need manual copying 
            // if the main plugin's saveResource doesn't target the sub-folder correctly.
            // Assuming XiannnAddons.java helper handles this or we manually create it.
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        loadRanks();
    }

    private void loadRanks() {
        rankLadder.clear();
        useLuckPerms = config.getBoolean("ranks.use-luckperms", false);

        String sectionKey = useLuckPerms ? "ranks.luckperms-groups" : "ranks.builtin-ranks";
        ConfigurationSection section = config.getConfigurationSection(sectionKey);

        if (section == null) {
            log.warn("No rank definitions found in config under " + sectionKey);
            return;
        }

        for (String key : section.getKeys(false)) {
            int tier = section.getInt(key + ".tier");
            String display = section.getString(key + ".display-name", key);
            String color = section.getString(key + ".display-color", "#FFFFFF");
            String perm = section.getString(key + ".permission", null); // Only for builtin

            StaffRank rank = new StaffRank(key, tier, display, color, perm);
            rankLadder.put(tier, rank);
        }
        
        log.info("Loaded " + rankLadder.size() + " staff ranks. Mode: " + (useLuckPerms ? "LuckPerms" : "Built-in"));
    }

    /**
     * Gets the staff rank of a player.
     * @return The highest priority StaffRank found, or null if not staff.
     */
    public StaffRank getStaffRank(Player player) {
        if (useLuckPerms) {
            return getLuckPermsRank(player);
        } else {
            return getBuiltinRank(player);
        }
    }

    private StaffRank getBuiltinRank(Player player) {
        // Iterate descending (Highest tier first)
        for (Integer tier : rankLadder.descendingKeySet()) {
            StaffRank rank = rankLadder.get(tier);
            if (rank.permission != null && player.hasPermission(rank.permission)) {
                return rank;
            }
        }
        return null;
    }

    private StaffRank getLuckPermsRank(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            log.warn("LuckPerms is enabled in config but not found on server!");
            return null;
        }

        LuckPerms lp = LuckPermsProvider.get();
        User user = lp.getUserManager().getUser(player.getUniqueId());
        if (user == null) return null;

        // Check primary group first (often the most relevant)
        String primaryGroup = user.getPrimaryGroup();
        StaffRank primaryRank = findRankByGroup(primaryGroup);
        if (primaryRank != null) return primaryRank;

        // If primary isn't a staff rank, check inherited groups
        // This is expensive, so we might want to cache or optimize
        // But for typical staff counts, it's negligible.
        Set<String> groups = user.getNodes().stream()
            .filter(net.luckperms.api.node.NodeType.INHERITANCE::matches)
            .map(net.luckperms.api.node.NodeType.INHERITANCE::cast)
            .map(net.luckperms.api.node.types.InheritanceNode::getGroupName)
            .collect(java.util.stream.Collectors.toSet());

        // Find highest tier among all groups user has
        StaffRank highest = null;
        for (String group : groups) {
            StaffRank r = findRankByGroup(group);
            if (r != null) {
                if (highest == null || r.tier > highest.tier) {
                    highest = r;
                }
            }
        }
        return highest;
    }

    private StaffRank findRankByGroup(String groupName) {
        for (StaffRank rank : rankLadder.values()) {
            if (rank.id.equalsIgnoreCase(groupName)) {
                return rank;
            }
        }
        return null;
    }

    /**
     * Checks if executor can punish target based on rank tier.
     * @return true if executor tier > target tier
     */
    public boolean canPunish(Player executor, Player target) {
        StaffRank execRank = getStaffRank(executor);
        StaffRank targetRank = getStaffRank(target);

        // If executor isn't staff (console or error), assume yes usually, but here strict.
        if (execRank == null) return false; 
        
        // If target isn't staff, staff can always punish.
        if (targetRank == null) return true;

        // Compare tiers
        return execRank.tier > targetRank.tier;
    }

    // ── Getters for raw config access ──
    
    public YamlConfiguration getConfig() { return config; }
    
    public boolean isStaffChatEnabled() { return config.getBoolean("staff-chat.enabled"); }
    public String getStaffChatFormat() { return config.getString("staff-chat.format"); }
    
    // ... add specific getters as needed for other systems

    /**
     * Data class representing a staff rank
     */
    public static class StaffRank {
        public final String id; // Group name or config key
        public final int tier;
        public final String displayName;
        public final String displayColor;
        public final String permission; // null if LuckPerms mode

        public StaffRank(String id, int tier, String displayName, String displayColor, String permission) {
            this.id = id;
            this.tier = tier;
            this.displayName = displayName;
            this.displayColor = displayColor;
            this.permission = permission;
        }
    }
}
