package me.xiannn.addons.modules.staff.systems;

import me.xiannn.addons.modules.staff.StaffModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class StaffGUISystem implements Listener {

    private final StaffModule module;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public StaffGUISystem(StaffModule module) {
        this.module = module;
        module.getPlugin().getServer().getPluginManager().registerEvents(this, module.getPlugin());
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (!player.hasPermission("xiannnaddons.staff.gui")) return true;

        openMainMenu(player);
        return true;
    }

    // ── Menus ────────────────────────────────────────────────────────

    private void openMainMenu(Player player) {
        // Read config for size/title
        int size = module.getConfigManager().getConfig().getInt("gui.main-menu.size", 54);
        Inventory inv = Bukkit.createInventory(null, size, miniMessage.deserialize("Staff Panel"));

        // Load items from config (Simplified example)
        // In real impl, loop through config keys
        
        // 1. Online Players
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.displayName(miniMessage.deserialize("<green>Online Players"));
        List<Component> lore = new ArrayList<>();
        lore.add(miniMessage.deserialize("<gray>Click to browse players"));
        meta.lore(lore);
        head.setItemMeta(meta);
        inv.setItem(20, head);

        // 2. Vanish Toggle
        ItemStack eye = new ItemStack(Material.ENDER_EYE);
        meta = eye.getItemMeta();
        boolean isVanish = module.getVanish() != null && module.getVanish().isVanished(player);
        meta.displayName(miniMessage.deserialize(isVanish ? "<green>Vanish: ON" : "<red>Vanish: OFF"));
        lore = new ArrayList<>();
        lore.add(miniMessage.deserialize("<gray>Click to toggle vanish"));
        meta.lore(lore);
        eye.setItemMeta(meta);
        inv.setItem(24, eye);

        // 3. Staff List
        ItemStack book = new ItemStack(Material.BOOK);
        meta = book.getItemMeta();
        meta.displayName(miniMessage.deserialize("<gold>Staff List"));
        lore = new ArrayList<>();
        lore.add(miniMessage.deserialize("<gray>View online staff"));
        meta.lore(lore);
        book.setItemMeta(meta);
        inv.setItem(22, book);

        player.openInventory(inv);
    }

    private void openPlayerList(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, miniMessage.deserialize("Online Players"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setOwningPlayer(p);
            meta.displayName(miniMessage.deserialize("<white>" + p.getName()));
            List<Component> lore = new ArrayList<>();
            lore.add(miniMessage.deserialize("<gray>Click to open action menu"));
            meta.lore(lore);
            skull.setItemMeta(meta);
            inv.addItem(skull);
        }
        player.openInventory(inv);
    }

    private void openActionMenu(Player staff, Player target) {
        Inventory inv = Bukkit.createInventory(null, 27, miniMessage.deserialize("Actions: " + target.getName()));
        
        // Ban Item
        ItemStack ban = new ItemStack(Material.RED_WOOL);
        ItemMeta meta = ban.getItemMeta();
        meta.displayName(miniMessage.deserialize("<red>Ban"));
        ban.setItemMeta(meta);
        inv.setItem(11, ban);

        // Mute Item
        ItemStack mute = new ItemStack(Material.ORANGE_WOOL);
        meta = mute.getItemMeta();
        meta.displayName(miniMessage.deserialize("<gold>Mute"));
        mute.setItemMeta(meta);
        inv.setItem(13, mute);

        // Kick Item
        ItemStack kick = new ItemStack(Material.LEATHER_BOOTS);
        meta = kick.getItemMeta();
        meta.displayName(miniMessage.deserialize("<yellow>Kick"));
        kick.setItemMeta(meta);
        inv.setItem(15, kick);

        staff.openInventory(inv);
    }

    // ── Listeners ────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Component titleComponent = event.getView().title();
        
        // Convert component to plain text for title checking
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(titleComponent);

        // Main Menu
        if (plainTitle.contains("Staff Panel")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;

            if (clicked.getType() == Material.PLAYER_HEAD) {
                openPlayerList(player);
            } else if (clicked.getType() == Material.ENDER_EYE) {
                player.performCommand("vanish");
                player.closeInventory();
            } else if (clicked.getType() == Material.BOOK) {
                player.performCommand("stafflist");
                player.closeInventory();
            }
        }
        
        // Online Players List
        else if (plainTitle.contains("Online Players")) {
            event.setCancelled(true);
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                SkullMeta skulMeta = (SkullMeta) event.getCurrentItem().getItemMeta();
                if (skulMeta != null && skulMeta.getOwningPlayer() != null && skulMeta.getOwningPlayer().isOnline()) {
                    Player targetPlayer = skulMeta.getOwningPlayer().getPlayer();
                    if (targetPlayer != null) {
                        openActionMenu(player, targetPlayer);
                    }
                }
            }
        }

        // Action Menu
        else if (plainTitle.contains("Actions:")) {
            event.setCancelled(true);
            String targetName = plainTitle.replace("Actions: ", "");
            
            if (event.getCurrentItem() != null) {
                Material type = event.getCurrentItem().getType();
                player.closeInventory();
                
                if (type == Material.RED_WOOL) { // Ban
                    player.sendMessage(miniMessage.deserialize("<yellow>Type ban reason in chat"));
                    // Note: Full implementation would use chat conversation API
                } else if (type == Material.ORANGE_WOOL) { // Mute
                    player.sendMessage(miniMessage.deserialize("<yellow>Type mute reason in chat"));
                } else if (type == Material.LEATHER_BOOTS) { // Kick
                    player.performCommand("kick " + targetName + " Kicked by staff");
                }
            }
        }
    }
}
