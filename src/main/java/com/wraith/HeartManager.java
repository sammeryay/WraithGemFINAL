package com.wraith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.*;

public class HeartManager implements Listener {

    private final WraithPlugin pl;
    private final Set<String> keys = new LinkedHashSet<>();
    /** How many Wraith Hearts have been broken so far (saved in data.yml). */
    public int broken;

    public HeartManager(WraithPlugin pl) { this.pl = pl; }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private Location toLoc(String k) {
        String[] p = k.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    public void load() {
        keys.addAll(pl.data().getStringList("hearts"));
        broken = pl.data().getInt("hearts-broken", 0);
    }

    public void save() {
        pl.data().set("hearts", new ArrayList<>(keys));
        pl.data().set("hearts-broken", broken);
    }

    public int count() { return keys.size(); }
    public boolean isHeart(Block b) { return keys.contains(key(b)); }

    public void place(Location l) {
        Block b = l.getBlock();
        b.setType(Material.OBSIDIAN);
        keys.add(key(b));
        pl.fx(l.clone().add(0.5, 0.5, 0.5), 40, 0.6);
        l.getWorld().playSound(l, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 0.5f);
    }

    public void clear() {
        for (String k : keys) {
            Location l = toLoc(k);
            if (l != null && l.getBlock().getType() == Material.OBSIDIAN) l.getBlock().setType(Material.AIR);
        }
        keys.clear();
        broken = 0;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            for (String k : keys) {
                Location l = toLoc(k);
                if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
                Location c = l.clone().add(0.5, 0.5, 0.5);
                l.getWorld().spawnParticle(Particle.ENCHANT, c, 14, 0.5, 0.5, 0.5, 0.4);
                pl.fx(c, 5, 0.55);
            }
        }, 0L, 10L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isHeart(b)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (pl.owner == null) { p.sendActionBar(Component.text("There is no Wraith yet.", NamedTextColor.RED)); return; }
        if (pl.isWraith(p)) { p.sendActionBar(Component.text("You cannot break your own heart.", NamedTextColor.RED)); return; }

        // the wraith loses hearts; the player who broke it gets nothing
        int base = pl.getConfig().getInt("passives.hearts", 15);
        int current = Math.max(1, base - pl.heartsLost);
        int lost = Math.min(pl.getConfig().getInt("hearts.steal", 2), current - 1);

        keys.remove(key(b));
        b.setType(Material.AIR);
        broken++;
        if (lost > 0) pl.heartsLost += lost;
        pl.saveData();

        Player w = pl.wraithPlayer();
        if (w != null) {
            pl.refresh(w);
            w.sendMessage(Component.text("A Wraith Heart was broken! You lost " + lost + " hearts.", NamedTextColor.RED));
        }

        int total = Math.max(pl.getConfig().getInt("hearts.max", 3), broken + keys.size());
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        pl.fx(c, 80, 1.0);
        c.getWorld().playSound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.7f);
        Bukkit.broadcast(Component.text("[Wraith] ", WraithPlugin.BLUE)
                .append(Component.text("A Wraith Heart was broken! The Wraith lost " + lost + " hearts. ("
                        + broken + "/" + total + " broken)", NamedTextColor.GRAY)));
        if (broken >= total && keys.isEmpty())
            Bukkit.broadcast(Component.text("[Wraith] ", WraithPlugin.BLUE)
                    .append(Component.text("All " + total + " Wraith Hearts have been destroyed!", NamedTextColor.AQUA)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isHeart); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isHeart); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (isHeart(b)) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (isHeart(b)) { e.setCancelled(true); return; }
    }
}
