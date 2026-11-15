package com.wraith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class ConfigMenu implements Listener {

    record Setting(String path, String name, Material icon, double min, double max, double step, String unit, boolean bool) {}

    static final List<Setting> SETTINGS = List.of(
            new Setting("guard.cooldown", "Guard Cooldown", Material.CLOCK, 5, 600, 5, "s", false),
            new Setting("guard.duration", "Guard Duration", Material.SOUL_LANTERN, 1, 60, 1, "s", false),
            new Setting("guard.resistance", "Guard Resistance Level", Material.IRON_CHESTPLATE, 1, 10, 1, "", false),
            new Setting("dash.cooldown", "Dash Cooldown", Material.CLOCK, 5, 600, 5, "s", false),
            new Setting("dash.distance", "Dash Distance", Material.FEATHER, 1, 20, 1, " blocks", false),
            new Setting("passives.crit-chance", "Auto Crit Chance", Material.DIAMOND_SWORD, 0, 100, 5, "%", false),
            new Setting("passives.reach", "Reach", Material.SPYGLASS, 3, 8, 0.5, " blocks", false),
            new Setting("passives.reach-chance", "Reach Active Time", Material.ENDER_EYE, 0, 100, 5, "%", false),
            new Setting("passives.hearts", "Wraith Hearts", Material.RED_DYE, 1, 40, 1, " hearts", false),
            new Setting("particles.amount", "Particle Amount", Material.BLUE_DYE, 0, 5, 0.25, "x", false),
            new Setting("hearts.steal", "Hearts Stolen Per Break", Material.CRYING_OBSIDIAN, 1, 10, 1, " hearts", false),
            new Setting("hearts.max", "Max Wraith Hearts Placed", Material.OBSIDIAN, 1, 10, 1, "", false),
            new Setting("gem.soulbound", "Soulbound Gem", Material.LEAD, 0, 1, 1, "", true)
    );

    static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24};
    static final int RESET_SLOT = 31;

    static class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final WraithPlugin pl;

    public ConfigMenu(WraithPlugin pl) { this.pl = pl; }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 36, Component.text("Wraith Gem Config", WraithPlugin.BLUE, TextDecoration.BOLD));
        h.inv = inv;
        for (int i = 0; i < 36; i++) {
            ItemStack pane = new ItemStack(i % 2 == 0 ? Material.BLACK_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE);
            ItemMeta m = pane.getItemMeta();
            m.displayName(Component.text(" "));
            pane.setItemMeta(m);
            inv.setItem(i, pane);
        }
        render(inv);
        p.openInventory(inv);
    }

    private String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0);
    }

    private void render(Inventory inv) {
        FileConfiguration c = pl.getConfig();
        for (int i = 0; i < SETTINGS.size(); i++) {
            Setting s = SETTINGS.get(i);
            ItemStack it = new ItemStack(s.icon());
            ItemMeta m = it.getItemMeta();
            m.displayName(Component.text(s.name(), WraithPlugin.BLUE).decoration(TextDecoration.ITALIC, false));
            String val = s.bool() ? (c.getBoolean(s.path()) ? "ON" : "OFF") : fmt(c.getDouble(s.path())) + s.unit();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Current: ", NamedTextColor.GRAY).append(Component.text(val, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false));
            if (s.bool()) {
                lore.add(Component.text("Click: toggle", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Left click: +" + fmt(s.step()), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Right click: -" + fmt(s.step()), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Shift: x5", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            m.lore(lore);
            it.setItemMeta(m);
            inv.setItem(SLOTS[i], it);
        }
        ItemStack reset = new ItemStack(Material.BARRIER);
        ItemMeta rm = reset.getItemMeta();
        rm.displayName(Component.text("Reset to defaults", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        reset.setItemMeta(rm);
        inv.setItem(RESET_SLOT, reset);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.hasPermission("wraith.admin")) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        FileConfiguration c = pl.getConfig();
        int slot = e.getRawSlot();
        if (slot == RESET_SLOT) {
            for (Setting s : SETTINGS) c.set(s.path(), c.getDefaults().get(s.path()));
        } else {
            int idx = -1;
            for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == slot) idx = i;
            if (idx < 0) return;
            Setting s = SETTINGS.get(idx);
            if (s.bool()) {
                c.set(s.path(), !c.getBoolean(s.path()));
            } else {
                double mult = e.isShiftClick() ? 5 : 1;
                double d = e.isLeftClick() ? s.step() * mult : e.isRightClick() ? -s.step() * mult : 0;
                double v = Math.round((c.getDouble(s.path()) + d) * 100) / 100.0;
                v = Math.max(s.min(), Math.min(s.max(), v));
                c.set(s.path(), v);
            }
        }
        pl.saveConfig();
        pl.refreshAll();
        render(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
