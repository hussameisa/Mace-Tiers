package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;

/**
 * The "Super Ultimate Mace fuses with your body" cutscene.
 * The player is frozen and floats up while a giant mace spirals down into their chest.
 */
final class MaceAnimation implements Runnable {

    private final MaceTiersPlugin plugin;
    private final Player player;
    private final Tier sup;
    private final Location base;
    private final float yaw;
    private final float pitch;
    private final double rise;
    private final int total;
    private final int absorbTick;

    private int tick = 0;
    private boolean granted = false;
    private boolean finished = false;
    private ItemDisplay display;
    private BukkitTask task;
    private boolean wasInvulnerable;
    private boolean wasAllowFlight;

    MaceAnimation(MaceTiersPlugin plugin, Player player, Tier sup) {
        this.plugin = plugin;
        this.player = player;
        this.sup = sup;
        this.base = player.getLocation().clone();
        this.yaw = base.getYaw();
        this.pitch = base.getPitch();
        this.total = Math.max(40, plugin.getConfig().getInt("super-mace.animation.duration-ticks", 100));
        this.absorbTick = (int) (total * 0.7);
        this.rise = computeRise(plugin.getConfig().getDouble("super-mace.animation.float-height", 1.5));
    }

    /** How high we can float without hitting a ceiling. */
    private double computeRise(double max) {
        double best = 0;
        for (double h = 0.25; h <= max; h += 0.25) {
            Location l = base.clone().add(0, h, 0);
            if (!l.getBlock().isPassable() || !l.clone().add(0, 1, 0).getBlock().isPassable()) break;
            best = h;
        }
        return best;
    }

    void start() {
        if (!player.isOnline() || player.isDead()) {
            plugin.endAnimation(player, !player.isDead());
            return;
        }
        player.closeInventory();

        wasInvulnerable = player.isInvulnerable();
        wasAllowFlight = player.getAllowFlight();
        if (plugin.getConfig().getBoolean("super-mace.animation.invulnerable", true)) {
            player.setInvulnerable(true);
        }
        player.setAllowFlight(true); // stops the anti-fly kick while floating
        player.setVelocity(new Vector(0, 0, 0));

        World w = base.getWorld();
        display = w.spawn(base.clone().add(0, 4.5, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.MACE));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setPersistent(false);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 1f, 0f),
                    new Vector3f(2.5f, 2.5f, 2.5f),
                    new AxisAngle4f(0f, 0f, 1f, 0f)));
        });

        w.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.6f);
        w.playSound(base, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.8f);

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        if (!player.isOnline() || player.isDead()) {
            plugin.endAnimation(player, !player.isDead());
            return;
        }
        tick++;

        // Freeze the player: fixed spot, fixed look direction, slowly rising
        double f = Math.min(1.0, tick / (double) absorbTick);
        double lift = rise * (1 - (1 - f) * (1 - f));
        Location t = base.clone().add(0, lift, 0);
        t.setYaw(yaw);
        t.setPitch(pitch);
        player.teleport(t);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);

        World w = t.getWorld();
        Location chest = t.clone().add(0, 1.0, 0);

        if (tick < absorbTick) {
            double ff = tick / (double) absorbTick;
            double r = 2.0 * (1 - ff);
            double ang = tick * 0.6;
            double y = 4.5 - 3.5 * ff * ff;
            Location dl = t.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r);
            dl.setYaw(tick * 30f);
            dl.setPitch(0f);
            if (display != null && display.isValid()) display.teleport(dl);

            w.spawnParticle(Particle.END_ROD, dl, 2, 0.1, 0.1, 0.1, 0.01);
            w.spawnParticle(Particle.PORTAL, chest, 6, 0.4, 0.6, 0.4, 0.6);
            for (int k = 0; k < 2; k++) {
                double a = tick * 0.5 + k * Math.PI;
                double rr = 1.2 * (1 - ff) + 0.4;
                w.spawnParticle(Particle.ENCHANT,
                        t.clone().add(Math.cos(a) * rr, 0.2 + ff * 1.8, Math.sin(a) * rr),
                        3, 0.05, 0.05, 0.05, 0.1);
            }
            if (tick % 10 == 0) {
                w.playSound(chest, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.6f + 0.8f * (float) ff);
            }
        } else if (tick == absorbTick) {
            absorb(chest);
        } else {
            int since = tick - absorbTick;
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, chest, 3, 0.3, 0.5, 0.3, 0.1);
            if (since % 3 == 0) {
                double rad = 0.3 + since * 0.08;
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8;
                    w.spawnParticle(Particle.END_ROD,
                            chest.clone().add(Math.cos(a) * rad, 0, Math.sin(a) * rad), 1, 0, 0, 0, 0);
                }
            }
        }

        if (tick >= total) {
            plugin.endAnimation(player, true);
        }
    }

    /** The mace enters the body: flash, boom, and the real mace replaces the old one. */
    private void absorb(Location chest) {
        World w = chest.getWorld();
        if (display != null) {
            display.remove();
            display = null;
        }
        w.spawnParticle(Particle.EXPLOSION_EMITTER, chest, 1);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, chest, 100, 0.4, 0.8, 0.4, 0.5);
        w.spawnParticle(Particle.ELECTRIC_SPARK, chest, 60, 0.5, 0.9, 0.5, 0.3);
        w.playSound(chest, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 1f);
        w.playSound(chest, Sound.ITEM_TOTEM_USE, 1f, 1f);

        granted = true;
        plugin.grantSuper(player, sup);

        player.showTitle(Title.title(
                Component.text(sup.display + " Mace", sup.textColor(), TextDecoration.BOLD),
                Component.text("fused with your body", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(600))));
    }

    /** Ends the cutscene and gives everything back to the player. */
    void finish(boolean grantIfMissing) {
        if (finished) return;
        finished = true;
        if (task != null) task.cancel();
        if (display != null) {
            display.remove();
            display = null;
        }
        player.setInvulnerable(wasInvulnerable);
        if (!wasAllowFlight) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        player.setFallDistance(0f);
        if (grantIfMissing && !granted) {
            granted = true;
            plugin.grantSuper(player, sup);
        }
    }
}
