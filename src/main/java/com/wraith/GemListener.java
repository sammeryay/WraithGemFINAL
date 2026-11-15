package com.wraith;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

public class GemListener implements Listener {

    private static final long DOUBLE_CLICK_MS = 500;

    private final WraithPlugin pl;
    private final Map<UUID, Long> guardActive = new HashMap<>(), guardCd = new HashMap<>();
    private final Map<UUID, Long> dashActive = new HashMap<>(), dashCd = new HashMap<>();
    private final Map<UUID, Long> lastOffhandClick = new HashMap<>();
    private final Map<UUID, ItemStack> keptGems = new HashMap<>();

    public GemListener(WraithPlugin pl) { this.pl = pl; }

    private FileConfiguration cfg() { return pl.getConfig(); }
    private long now() { return System.currentTimeMillis(); }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(pl, this::tickFast, 0L, 5L);
        Bukkit.getScheduler().runTaskTimer(pl, this::tickReach, 0L, 10L);
    }

    // ---------- ticking ----------
    private Component state(long active, long cd) {
        long n = now();
        if (active > n) return Component.text("Active..", NamedTextColor.BLUE);
        if (cd > n) return Component.text(((cd - n + 999) / 1000) + "s", NamedTextColor.GRAY);
        return Component.text("Ready", NamedTextColor.AQUA);
    }

    private boolean holdsGem(Player p) {
        return pl.isGem(p.getInventory().getItemInMainHand()) || pl.isGem(p.getInventory().getItemInOffHand());
    }

    private void tickFast() {
        Player w = pl.wraithPlayer();
        if (w != null) {
            UUID id = w.getUniqueId();
            pl.fx(w.getLocation().add(0, 1, 0), guardActive(w) ? 10 : 2, guardActive(w) ? 0.7 : 0.45);
            if (holdsGem(w)) {
                w.sendActionBar(Component.text("Guard: ", WraithPlugin.BLUE)
                        .append(state(guardActive.getOrDefault(id, 0L), guardCd.getOrDefault(id, 0L)))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Dash: ", WraithPlugin.BLUE))
                        .append(state(dashActive.getOrDefault(id, 0L), dashCd.getOrDefault(id, 0L))));
            }
        }
        long n = now();
        for (UUID id : new ArrayList<>(guardActive.keySet())) {
            if (guardActive.get(id) > n) continue;
            guardActive.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                removeGuardMods(p);
                p.sendActionBar(Component.text("Spectral Guard faded.", NamedTextColor.AQUA));
            }
        }
    }

    private void tickReach() {
        Player w = pl.wraithPlayer();
        if (w == null) return;
        AttributeInstance a = w.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (a == null) return;
        WraithPlugin.removeMod(a, pl.reachKey);
        double chance = cfg().getDouble("passives.reach-chance", 100) / 100.0;
        double extra = cfg().getDouble("passives.reach", 5.0) - a.getBaseValue();
        if (extra > 0 && pl.rng().nextDouble() < chance)
            a.addModifier(new AttributeModifier(pl.reachKey, extra, AttributeModifier.Operation.ADD_NUMBER));
    }

    // ---------- spectral guard ----------
    private boolean guardActive(Player p) { return guardActive.getOrDefault(p.getUniqueId(), 0L) > now(); }

    private void addGuardMods(Player p) {
        for (Attribute at : new Attribute[]{Attribute.KNOCKBACK_RESISTANCE, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE}) {
            AttributeInstance a = p.getAttribute(at);
            if (a == null) continue;
            WraithPlugin.removeMod(a, pl.guardKey);
            a.addModifier(new AttributeModifier(pl.guardKey, 1.0, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void removeGuardMods(Player p) {
        for (Attribute at : new Attribute[]{Attribute.KNOCKBACK_RESISTANCE, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE}) {
            AttributeInstance a = p.getAttribute(at);
            if (a != null) WraithPlugin.removeMod(a, pl.guardKey);
        }
    }

    private void guard(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        if (guardCd.getOrDefault(id, 0L) > n) {
            p.sendActionBar(Component.text("Spectral Guard is on cooldown.", NamedTextColor.RED));
            return;
        }
        long dur = (long) (cfg().getDouble("guard.duration", 10) * 1000);
        long cd = (long) (cfg().getDouble("guard.cooldown", 130) * 1000);
        int level = Math.max(1, cfg().getInt("guard.resistance", 5));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) (dur / 50), level - 1, false, true, true));
        addGuardMods(p);
        guardActive.put(id, n + dur);
        guardCd.put(id, n + dur + cd);

        Location l = p.getLocation().add(0, 1, 0);
        pl.fx(l, 60, 0.8);
        p.getWorld().playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
        p.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 0.5f, 0.5f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        if (guardActive(e.getPlayer())) e.setCancelled(true);
    }

    // ---------- dash ----------
    private void dash(Player p) {
        UUID id = p.getUniqueId();
        long n = now();
        if (dashCd.getOrDefault(id, 0L) > n) return;
        double max = cfg().getDouble("dash.distance", 7);
        Location start = p.getLocation();
        Vector dir = start.getDirection().normalize();
        World w = p.getWorld();
        BoundingBox box = p.getBoundingBox();
        double dist = 0;
        for (double d = 0.25; d <= max + 1e-6; d += 0.25) {
            if (w.hasCollisionsIn(box.clone().shift(dir.clone().multiply(d)))) break;
            dist = d;
        }
        if (dist < 0.5) { p.sendActionBar(Component.text("Something is in the way!", NamedTextColor.RED)); return; }
        for (double d = 0; d <= dist; d += 0.4)
            pl.fx(start.clone().add(dir.clone().multiply(d)).add(0, 1, 0), 4, 0.2);
        Location dest = start.clone().add(dir.clone().multiply(dist));
        p.teleport(dest);
        p.setFallDistance(0);
        w.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
        pl.fx(dest.clone().add(0, 1, 0), 30, 0.5);
        long cd = (long) (cfg().getDouble("dash.cooldown", 130) * 1000);
        dashActive.put(id, n + 500);
        dashCd.put(id, n + 500 + cd);
    }

    // ---------- events ----------
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player w)) return;
        if (!pl.isWraith(w) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        double chance = cfg().getDouble("passives.crit-chance", 50) / 100.0;
        if (!e.isCritical() && pl.rng().nextDouble() < chance) {
            e.setDamage(e.getDamage() * 1.5);
            Location l = e.getEntity().getLocation().add(0, 1, 0);
            w.getWorld().spawnParticle(Particle.CRIT, l, 15, 0.3, 0.4, 0.3, 0.2);
            pl.fx(l, 6, 0.4);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (it == null || !pl.isGem(it) || !pl.isWraith(p)) return;

        if (e.getHand() == EquipmentSlot.HAND) {            // main hand right click: dash
            e.setCancelled(true);
            dash(p);
        } else if (e.getHand() == EquipmentSlot.OFF_HAND) { // offhand double right click: spectral guard
            e.setCancelled(true);
            UUID id = p.getUniqueId();
            long n = now();
            Long last = lastOffhandClick.get(id);
            if (last != null && n - last <= DOUBLE_CLICK_MS) {
                lastOffhandClick.remove(id);
                guard(p);
            } else {
                lastOffhandClick.put(id, n);
            }
        }
    }

    // ---------- soulbound + join/respawn ----------
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (cfg().getBoolean("gem.soulbound", true) && pl.isGem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        guardActive.remove(p.getUniqueId());
        removeGuardMods(p);
        if (!cfg().getBoolean("gem.soulbound", true)) return;
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack st = it.next();
            if (pl.isGem(st)) {
                keptGems.put(p.getUniqueId(), st);
                it.remove();
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> {
            pl.refresh(p);
            removeGuardMods(p);
            ItemStack g = keptGems.remove(p.getUniqueId());
            if (g != null) p.getInventory().addItem(g);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        pl.refresh(p);
        if (!guardActive(p)) removeGuardMods(p);
    }

    public void clearAllGuards() {
        for (Player p : Bukkit.getOnlinePlayers()) removeGuardMods(p);
        guardActive.clear();
    }
}
