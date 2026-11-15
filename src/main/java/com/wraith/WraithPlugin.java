package com.wraith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class WraithPlugin extends JavaPlugin implements TabExecutor {

    public static final TextColor BLUE = TextColor.color(0x3C8CFF);

    public NamespacedKey gemKey, healthKey, reachKey, guardKey;
    public UUID owner;
    public int heartsLost;
    public final Map<UUID, Integer> bonus = new HashMap<>();

    public HeartManager hearts;
    public GemListener gem;
    public ConfigMenu menu;

    private File dataFile;
    private YamlConfiguration data;
    private final Random rng = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        gemKey = new NamespacedKey(this, "wraith_gem");
        healthKey = new NamespacedKey(this, "wraith_health");
        reachKey = new NamespacedKey(this, "wraith_reach");
        guardKey = new NamespacedKey(this, "wraith_guard");

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        String o = data.getString("owner");
        owner = o == null ? null : UUID.fromString(o);
        heartsLost = data.getInt("hearts-lost");
        data.set("bonus", null);

        hearts = new HeartManager(this);
        gem = new GemListener(this);
        menu = new ConfigMenu(this);
        hearts.load();

        getServer().getPluginManager().registerEvents(hearts, this);
        getServer().getPluginManager().registerEvents(gem, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getCommand("wraith").setExecutor(this);
        getCommand("wraith").setTabCompleter(this);

        hearts.start();
        gem.start();
        refreshAll();
    }

    @Override
    public void onDisable() {
        gem.clearAllGuards();
        saveData();
    }

    // ---------- data ----------
    public YamlConfiguration data() { return data; }
    public Random rng() { return rng; }

    public void saveData() {
        data.set("owner", owner == null ? null : owner.toString());
        data.set("hearts-lost", heartsLost);
        data.set("bonus", null);
        for (Map.Entry<UUID, Integer> e : bonus.entrySet()) data.set("bonus." + e.getKey(), e.getValue());
        hearts.save();
        try { data.save(dataFile); } catch (IOException ex) { getLogger().warning("Could not save data.yml: " + ex.getMessage()); }
    }

    // ---------- wraith state ----------
    public boolean isWraith(Player p) { return owner != null && owner.equals(p.getUniqueId()); }
    public Player wraithPlayer() { return owner == null ? null : Bukkit.getPlayer(owner); }

    public boolean isGem(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(gemKey, PersistentDataType.BYTE);
    }

    public void removeGems(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isGem(inv.getItem(i))) inv.setItem(i, null);
    }

    // ---------- gem item ----------
    public ItemStack createGem() {
        Material m = Material.matchMaterial(getConfig().getString("gem.material", "ECHO_SHARD"));
        ItemStack it = new ItemStack(m == null ? Material.ECHO_SHARD : m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text("Wraith Gem", BLUE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("ᴘᴀssɪᴠᴇs", BLUE));
        lore.add(line(" ◆ Auto Crit (" + getConfig().getInt("passives.crit-chance") + "%)", NamedTextColor.GRAY));
        lore.add(line(" ◆ " + getConfig().getDouble("passives.reach") + " Block Reach", NamedTextColor.GRAY));
        lore.add(line(" ◆ " + getConfig().getInt("passives.hearts") + " Hearts", NamedTextColor.GRAY));
        lore.add(line("", NamedTextColor.GRAY));
        lore.add(line("ᴀʙɪʟɪᴛɪᴇs", BLUE));
        lore.add(line(" ◆ Offhand Double Click: Spectral Guard", NamedTextColor.GRAY));
        lore.add(line(" ◆ Right Click: Phantom Dash", NamedTextColor.GRAY));
        meta.lore(lore);
        int cmd = getConfig().getInt("gem.custom-model-data", 0);
        if (cmd > 0) meta.setCustomModelData(cmd);
        String model = getConfig().getString("gem.item-model", "wraith:wraith_gem");
        if (model != null && !model.isBlank()) {
            NamespacedKey mk = NamespacedKey.fromString(model);
            if (mk != null) meta.setItemModel(mk);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(gemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private Component line(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ---------- attributes ----------
    static void removeMod(AttributeInstance a, NamespacedKey k) {
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().equals(k)) a.removeModifier(m);
    }

    public void applyHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        removeMod(a, healthKey);
        double extra;
        if (isWraith(p)) {
            int h = Math.max(1, getConfig().getInt("passives.hearts", 15) - heartsLost);
            extra = h * 2.0 - 20.0;
        } else {
            extra = 0;
        }
        if (extra != 0) a.addModifier(new AttributeModifier(healthKey, extra, AttributeModifier.Operation.ADD_NUMBER));
        if (p.getHealth() > a.getValue()) p.setHealth(a.getValue());
    }

    public void refresh(Player p) {
        applyHealth(p);
        AttributeInstance r = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (r != null && !isWraith(p)) removeMod(r, reachKey);
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
    }

    // ---------- particles (blue + black) ----------
    public void fx(Location l, int count, double spread) {
        int n = (int) Math.round(count * getConfig().getDouble("particles.amount", 1.0));
        if (n <= 0 || l.getWorld() == null) return;
        Particle.DustOptions blue = new Particle.DustOptions(Color.fromRGB(30, 100, 255), 1.6f);
        Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(8, 8, 16), 1.8f);
        l.getWorld().spawnParticle(Particle.DUST, l, n, spread, spread, spread, 0, blue, true);
        l.getWorld().spawnParticle(Particle.DUST, l, n, spread, spread, spread, 0, black, true);
    }

    // ---------- commands ----------
    private Component msg(String s) {
        return Component.text("[Wraith] ", BLUE).append(Component.text(s, NamedTextColor.GRAY));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { s.sendMessage(msg("/wraith config | set heart <x> <y> <z> | set player <user> | reset hearts")); return true; }
        switch (a[0].toLowerCase()) {
            case "config" -> {
                if (!(s instanceof Player p)) { s.sendMessage(msg("Players only.")); return true; }
                menu.open(p);
            }
            case "set" -> {
                if (a.length < 2) { s.sendMessage(msg("/wraith set heart <x> <y> <z> | set player <user>")); return true; }
                if (a[1].equalsIgnoreCase("heart")) setHeart(s, a);
                else if (a[1].equalsIgnoreCase("player")) setPlayer(s, a);
                else s.sendMessage(msg("Unknown option."));
            }
            case "reset" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("hearts")) {
                    hearts.clear();
                    heartsLost = 0;
                    bonus.clear();
                    saveData();
                    refreshAll();
                    s.sendMessage(msg("Wraith Hearts and all heart changes were reset."));
                } else s.sendMessage(msg("/wraith reset hearts"));
            }
            default -> s.sendMessage(msg("Unknown subcommand."));
        }
        return true;
    }

    private static double coord(String s, double base) {
        if (s.startsWith("~")) return base + (s.length() == 1 ? 0 : Double.parseDouble(s.substring(1)));
        return Double.parseDouble(s);
    }

    private void setHeart(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage(msg("Players only.")); return; }
        if (a.length != 5) { s.sendMessage(msg("/wraith set heart <x> <y> <z>")); return; }
        Location base = p.getLocation();
        double x, y, z;
        try {
            x = coord(a[2], base.getX());
            y = coord(a[3], base.getY());
            z = coord(a[4], base.getZ());
        } catch (NumberFormatException ex) { s.sendMessage(msg("Invalid coordinates.")); return; }
        Location loc = new Location(p.getWorld(), Math.floor(x), Math.floor(y), Math.floor(z));
        if (hearts.isHeart(loc.getBlock())) { s.sendMessage(msg("There is already a Wraith Heart there.")); return; }
        int max = getConfig().getInt("hearts.max", 3);
        if (hearts.count() + hearts.broken >= max) { s.sendMessage(msg("All " + max + " Wraith Hearts were already placed. Use /wraith reset hearts first.")); return; }
        hearts.place(loc);
        saveData();
        s.sendMessage(msg("Wraith Heart placed (" + (hearts.count() + hearts.broken) + "/" + max + " placed)."));
    }

    private void setPlayer(CommandSender s, String[] a) {
        if (a.length != 3) { s.sendMessage(msg("/wraith set player <user>")); return; }
        Player t = Bukkit.getPlayerExact(a[2]);
        if (t == null) { s.sendMessage(msg("That player is not online.")); return; }
        Player old = wraithPlayer();
        if (old != null) removeGems(old);
        removeGems(t);
        owner = t.getUniqueId();
        saveData();
        if (old != null && !old.equals(t)) refresh(old);
        refresh(t);
        for (ItemStack left : t.getInventory().addItem(createGem()).values())
            t.getWorld().dropItemNaturally(t.getLocation(), left);
        fx(t.getLocation().add(0, 1, 0), 40, 0.6);
        s.sendMessage(msg(t.getName() + " is now the Wraith."));
        t.sendMessage(msg("You are the Wraith."));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(List.of("config", "set", "reset"), a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("set")) return filter(List.of("heart", "player"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("reset")) return filter(List.of("hearts"), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("set") && a[1].equalsIgnoreCase("player")) {
            List<String> n = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
            return filter(n, a[2]);
        }
        if (a.length >= 3 && a.length <= 5 && a[0].equalsIgnoreCase("set") && a[1].equalsIgnoreCase("heart")) return List.of("~");
        return List.of();
    }

    private List<String> filter(List<String> in, String pre) {
        List<String> out = new ArrayList<>();
        for (String s : in) if (s.toLowerCase().startsWith(pre.toLowerCase())) out.add(s);
        return out;
    }
}
