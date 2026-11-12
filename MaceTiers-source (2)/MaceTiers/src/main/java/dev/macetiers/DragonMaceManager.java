package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DragonMaceManager implements Listener {

    private final MaceTiersPlugin plugin;
    private static NamespacedKey dragonKey;
    private UUID currentHolder = null;
    private final Map<UUID, Integer> feedCounter = new HashMap<>();

    public DragonMaceManager(MaceTiersPlugin plugin) {
        this.plugin = plugin;
        dragonKey = new NamespacedKey(plugin, "dragon_mace");
    }

    public static boolean isDragonMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(dragonKey, PersistentDataType.BYTE);
    }

    public static ItemStack createDragonMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta = mace.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("🐉 Dragon Mace", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.asList(
                    Component.text("Forged from the fallen Ender Dragon.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Right-Click: Dash | Shift + Right-Click: Ground Slam", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                    Component.text("Drops on death. Upgrades your mace on pickup!", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
            ));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(dragonKey, PersistentDataType.BYTE, (byte) 1);
            mace.setItemMeta(meta);
        }
        return mace;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftPrepare(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        boolean hasBreezeRod = false;
        boolean hasDragonEgg = false;
        int itemCount = 0;

        for (ItemStack item : matrix) {
            if (item == null || item.getType() == Material.AIR) continue;
            itemCount++;
            if (item.getType() == Material.BREEZE_ROD) hasBreezeRod = true;
            if (item.getType() == Material.DRAGON_EGG) hasDragonEgg = true;
        }

        // Works anywhere in any crafting grid instantly!
        if (itemCount == 2 && hasBreezeRod && hasDragonEgg) {
            event.getInventory().setResult(createDragonMace());
        }
    }

    @EventHandler
    public void onCraftFinish(CraftItemEvent event) {
        if (!isDragonMace(event.getCurrentItem())) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Location loc = player.getLocation();
        World w = loc.getWorld();
        if (w != null) {
            w.strikeLightningEffect(loc);
            w.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, 1, 0), 80, 0.8, 0.8, 0.8, 0.1);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, 1, 0), 1);
            w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.8f);
            w.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5f, 1.0f);
        }

        currentHolder = player.getUniqueId();
        feedCounter.clear();

        Bukkit.getScheduler().runTask(plugin, () -> plugin.refresh(player));
        Bukkit.broadcast(Component.text("🚨 " + player.getName() + " has awakened the secret ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("Dragon Mace", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.LIGHT_PURPLE)));
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack picked = event.getItem().getItemStack();
        if (!isDragonMace(picked)) return;

        currentHolder = player.getUniqueId();
        feedCounter.clear();

        Bukkit.getScheduler().runTask(plugin, () -> plugin.refresh(player));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
        Bukkit.broadcast(Component.text("⚔️ " + player.getName() + " picked up the Dragon Mace!", NamedTextColor.LIGHT_PURPLE));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (currentHolder != null && victim.getUniqueId().equals(currentHolder)) {
            currentHolder = null;
            feedCounter.clear();
            Bukkit.broadcast(Component.text("💀 The Dragon Mace holder " + victim.getName() + " has fallen! The mace lies on the ground...", NamedTextColor.RED));
            return;
        }

        if (currentHolder != null && killer != null && killer.getUniqueId().equals(currentHolder)) {
            int maxDeaths = plugin.getConfig().getInt("dragon-mace.max-deaths-allowed", 5);
            int deaths = feedCounter.getOrDefault(victim.getUniqueId(), 0) + 1;
            feedCounter.put(victim.getUniqueId(), deaths);

            if (deaths >= maxDeaths) {
                feedCounter.remove(victim.getUniqueId());
                String reason = plugin.getConfig().getString("dragon-mace.ban-reason",
                        "Death banned: killed " + maxDeaths + " times by the Dragon Mace holder!");
                if (plugin.deathBan(victim, reason)) {
                    Bukkit.broadcast(Component.text("☠️ " + victim.getName() + " died " + maxDeaths + " times to the Dragon Mace and was death banned!", NamedTextColor.DARK_RED));
                }
            } else {
                victim.sendMessage(Component.text("⚠️ Killed by the Dragon Mace! (" + deaths + "/" + maxDeaths + " before death ban)", NamedTextColor.RED));
            }
        }
    }
}
