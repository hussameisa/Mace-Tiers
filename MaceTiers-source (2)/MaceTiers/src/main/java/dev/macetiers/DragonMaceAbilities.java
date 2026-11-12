package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DragonMaceAbilities implements Listener {

    private final Map<UUID, Long> dashCooldown = new HashMap<>();
    private final Map<UUID, Long> slamCooldown = new HashMap<>();

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!DragonMaceManager.isDragonMace(item)) return;

        event.setCancelled(true);

        if (player.isSneaking()) {
            executeGroundSlam(player);
        } else {
            executeDash(player);
        }
    }

    private void executeDash(Player player) {
        long now = System.currentTimeMillis();
        if (dashCooldown.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(Component.text("Dash on cooldown!", NamedTextColor.RED));
            return;
        }
        dashCooldown.put(player.getUniqueId(), now + 3000L);

        Vector dir = player.getLocation().getDirection().normalize().multiply(1.9);
        dir.setY(Math.max(dir.getY(), 0.25));
        player.setVelocity(dir);

        player.getWorld().spawnParticle(Particle.SONIC_BOOM, player.getLocation().add(0, 1, 0), 1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.8f);
        player.sendActionBar(Component.text("⚡ Dragon Dash!", NamedTextColor.LIGHT_PURPLE));
    }

    private void executeGroundSlam(Player player) {
        long now = System.currentTimeMillis();
        if (slamCooldown.getOrDefault(player.getUniqueId(), 0L) > now) {
            player.sendActionBar(Component.text("Ground Slam on cooldown!", NamedTextColor.RED));
            return;
        }
        slamCooldown.put(player.getUniqueId(), now + 5000L);

        player.setVelocity(new Vector(0, -2.8, 0));
        player.setFallDistance(0f);

        player.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, player.getLocation(), 2);
        player.getWorld().spawnParticle(Particle.DRAGON_BREATH, player.getLocation(), 60, 2.5, 0.5, 2.5, 0.1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.6f);
        player.sendActionBar(Component.text("☄️ GROUND SLAM!", NamedTextColor.DARK_PURPLE));

        for (Entity entity : player.getNearbyEntities(5.0, 4.0, 5.0)) {
            if (entity.equals(player) || !(entity instanceof LivingEntity target)) continue;

            Vector away = target.getLocation().toVector().subtract(player.getLocation().toVector());
            if (away.lengthSquared() == 0) away = new Vector(1, 0, 0);
            away.normalize().multiply(1.8).setY(0.75);
            target.setVelocity(away);
            target.damage(8.0, player);
        }
    }
}
