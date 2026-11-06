package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareItemEnchantEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MaceTiersPlugin extends JavaPlugin implements Listener {

    private final List<Tier> tiers = new ArrayList<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private NamespacedKey maceKey;
    private File dataFile;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maceKey = new NamespacedKey(this, "tier_mace");
        dataFile = new File(getDataFolder(), "data.yml");

        loadTiers();
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        PlayerCommands pc = new PlayerCommands(this);
        for (String name : new String[]{"mace", "macestats", "macetop", "windburst"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(pc);
                c.setTabCompleter(pc);
            }
        }
        AdminCommand ac = new AdminCommand(this);
        PluginCommand admin = getCommand("macetiers");
        if (admin != null) {
            admin.setExecutor(ac);
            admin.setTabCompleter(ac);
        }

        // autosave every 5 minutes
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);

        refreshAllOnline();
    }

    @Override
    public void onDisable() {
        saveData();
    }

    public void reloadAll() {
        reloadConfig();
        loadTiers();
        refreshAllOnline();
    }

    // ------------------------------------------------------------------
    // Tiers (config <-> memory)
    // ------------------------------------------------------------------

    public List<Tier> tiers() {
        return tiers;
    }

    public Tier tierById(String id) {
        if (id == null) return null;
        for (Tier t : tiers) {
            if (t.id.equalsIgnoreCase(id)) return t;
        }
        return null;
    }

    public int indexOf(Tier t) {
        return Math.max(0, tiers.indexOf(t));
    }

    private void loadTiers() {
        tiers.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("tiers");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                Tier t = new Tier(key.toLowerCase(Locale.ROOT));
                t.display = s.getString("display", t.display);
                t.color = s.getString("color", "#FFFFFF");
                t.killsToNext = Math.max(1, s.getInt("kills-to-next", 1));
                ConfigurationSection ench = s.getConfigurationSection("enchants");
                if (ench != null) {
                    for (String en : ench.getKeys(false)) {
                        t.enchants.put(en.toLowerCase(Locale.ROOT), Math.max(1, ench.getInt(en, 1)));
                    }
                }
                t.rewardCommands.addAll(s.getStringList("reward-commands"));
                tiers.add(t);
            }
        }
        if (tiers.isEmpty()) {
            getLogger().warning("No tiers in config.yml, adding a default one.");
            tiers.add(new Tier("wood"));
        }
    }

    /** Writes the in-memory tier list back into config.yml. */
    public void saveTiers() {
        getConfig().set("tiers", null);
        for (Tier t : tiers) {
            String p = "tiers." + t.id;
            getConfig().set(p + ".display", t.display);
            getConfig().set(p + ".color", t.color);
            getConfig().set(p + ".kills-to-next", t.killsToNext);
            getConfig().createSection(p + ".enchants", new java.util.LinkedHashMap<String, Object>(t.enchants));
            getConfig().set(p + ".reward-commands", t.rewardCommands);
        }
        saveConfig();
    }

    /** Call after any admin edit to tiers or settings. */
    public void tiersChanged() {
        saveTiers();
        refreshAllOnline();
    }

    public Enchantment findEnchant(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        NamespacedKey key = NamespacedKey.fromString(n, null);
        if (key == null) key = NamespacedKey.minecraft(n);
        return Registry.ENCHANTMENT.get(key);
    }

    public List<String> enchantNames() {
        List<String> l = new ArrayList<>();
        for (Enchantment e : Registry.ENCHANTMENT) l.add(e.getKey().getKey());
        return l;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public String deathPenalty() {
        return getConfig().getString("settings.death-penalty", "RESET").toUpperCase(Locale.ROOT);
    }

    public int windMax() {
        return Math.max(1, getConfig().getInt("settings.windburst-max", 3));
    }

    private boolean disabledWorld(World w) {
        return getConfig().getStringList("settings.disabled-worlds").contains(w.getName());
    }

    public boolean hasWind(Tier t) {
        return t.enchants.containsKey("wind_burst");
    }

    // ------------------------------------------------------------------
    // Player data
    // ------------------------------------------------------------------

    public Map<UUID, PlayerData> allData() {
        return data;
    }

    public PlayerData data(Player p) {
        PlayerData d = data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
        d.name = p.getName();
        return d;
    }

    public Tier tierOf(PlayerData d) {
        Tier t = tierById(d.tier);
        return t != null ? t : tiers.get(0);
    }

    public void setTier(Player p, Tier t) {
        PlayerData d = data(p);
        d.tier = t.id;
        d.progress = 0;
        refresh(p);
    }

    private void loadData() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                PlayerData d = new PlayerData();
                d.name = s.getString(key + ".name", "?");
                d.tier = s.getString(key + ".tier");
                d.progress = s.getInt(key + ".progress");
                d.kills = s.getInt(key + ".kills");
                d.deaths = s.getInt(key + ".deaths");
                d.streak = s.getInt(key + ".streak");
                d.bestStreak = s.getInt(key + ".best-streak");
                d.windLevel = s.getInt(key + ".wind-level");
                data.put(id, d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerData> en : data.entrySet()) {
            String p = "players." + en.getKey();
            PlayerData d = en.getValue();
            y.set(p + ".name", d.name);
            y.set(p + ".tier", d.tier);
            y.set(p + ".progress", d.progress);
            y.set(p + ".kills", d.kills);
            y.set(p + ".deaths", d.deaths);
            y.set(p + ".streak", d.streak);
            y.set(p + ".best-streak", d.bestStreak);
            y.set(p + ".wind-level", d.windLevel);
        }
        try {
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Mace item
    // ------------------------------------------------------------------

    public boolean isMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(maceKey, PersistentDataType.BYTE);
    }

    private void applyTier(ItemStack item, Player p) {
        PlayerData d = data(p);
        Tier tier = tierOf(d);
        int idx = indexOf(tier);
        boolean last = idx >= tiers.size() - 1;
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(tier.display + " Mace", tier.textColor())
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, last));
        meta.setUnbreakable(getConfig().getBoolean("settings.unbreakable", true));
        meta.getPersistentDataContainer().set(maceKey, PersistentDataType.BYTE, (byte) 1);

        for (Enchantment e : new ArrayList<>(meta.getEnchants().keySet())) {
            meta.removeEnchant(e);
        }
        for (Map.Entry<String, Integer> en : tier.enchants.entrySet()) {
            Enchantment ench = findEnchant(en.getKey());
            if (ench == null) continue;
            int level = en.getValue();
            if (ench.equals(Enchantment.WIND_BURST) && d.windLevel > 0) {
                level = Math.min(d.windLevel, windMax());
            }
            meta.addEnchant(ench, Math.max(1, Math.min(255, level)), true);
        }

        List<Component> lore = new ArrayList<>();
        lore.add(line("Tier " + (idx + 1) + "/" + tiers.size(), NamedTextColor.GRAY));
        if (!last) {
            lore.add(line("Kills: " + d.progress + "/" + tier.killsToNext
                    + " to " + tiers.get(idx + 1).display, NamedTextColor.GRAY));
        }
        if (hasWind(tier)) {
            lore.add(line("/windburst <1-" + windMax() + "> changes Wind Burst", NamedTextColor.DARK_GRAY));
        }
        lore.add(line("Kill players to upgrade. Dying costs you.", NamedTextColor.DARK_GRAY));
        meta.lore(lore);

        item.setItemMeta(meta);
    }

    private Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    /** Ensures the player has exactly one mace and that it matches their tier. */
    public void refresh(Player p) {
        if (disabledWorld(p.getWorld())) return;
        boolean found = false;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isMace(it)) continue;
            if (found) {
                p.getInventory().setItem(i, null);
            } else {
                applyTier(it, p);
                p.getInventory().setItem(i, it);
                found = true;
            }
        }
        if (!found) {
            ItemStack mace = new ItemStack(Material.MACE);
            applyTier(mace, p);
            for (ItemStack left : p.getInventory().addItem(mace).values()) {
                p.getWorld().dropItem(p.getLocation(), left);
            }
        }
    }

    public void refreshAllOnline() {
        for (Player p : getServer().getOnlinePlayers()) refresh(p);
    }

    private void refreshLater(Player p) {
        getServer().getScheduler().runTask(this, () -> {
            if (p.isOnline()) refresh(p);
        });
    }

    // ------------------------------------------------------------------
    // Kills and deaths
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        Player killer = victim.getKiller();

        e.getDrops().removeIf(this::isMace);
        if (disabledWorld(victim.getWorld())) return;

        PlayerData vd = data(victim);
        vd.deaths++;
        vd.streak = 0;
        vd.progress = 0;
        String penalty = deathPenalty();
        if (penalty.equals("RESET")) {
            vd.tier = tiers.get(0).id;
        } else if (penalty.equals("DOWN_ONE")) {
            int idx = indexOf(tierOf(vd));
            vd.tier = tiers.get(Math.max(0, idx - 1)).id;
        }
        victim.sendMessage(Component.text("You died. Your mace is now "
                + tierOf(vd).display + " tier.", NamedTextColor.RED));

        if (killer != null && !killer.equals(victim) && !disabledWorld(killer.getWorld())) {
            registerKill(killer);
        }
    }

    private void registerKill(Player killer) {
        PlayerData d = data(killer);
        d.kills++;
        d.streak++;
        d.bestStreak = Math.max(d.bestStreak, d.streak);

        Tier cur = tierOf(d);
        int idx = indexOf(cur);
        if (idx >= tiers.size() - 1) {
            killer.sendActionBar(Component.text("Your mace is already maxed out!", NamedTextColor.LIGHT_PURPLE));
            return;
        }

        d.progress++;
        if (d.progress >= cur.killsToNext) {
            Tier up = tiers.get(idx + 1);
            d.progress = 0;
            d.tier = up.id;

            killer.sendMessage(Component.text("Your mace upgraded to ", NamedTextColor.GREEN)
                    .append(Component.text(up.display, up.textColor(), TextDecoration.BOLD))
                    .append(Component.text("!", NamedTextColor.GREEN)));
            if (hasWind(up) && !hasWind(cur)) {
                killer.sendMessage(Component.text("Wind Burst unlocked! Use /windburst <1-"
                        + windMax() + "> to choose its level.", NamedTextColor.AQUA));
            }
            if (getConfig().getBoolean("settings.upgrade-sound", true)) {
                killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
            if (indexOf(up) == tiers.size() - 1 && getConfig().getBoolean("settings.broadcast-final-tier", true)) {
                getServer().broadcast(Component.text(killer.getName() + " reached the ", NamedTextColor.GOLD)
                        .append(Component.text(up.display, up.textColor(), TextDecoration.BOLD))
                        .append(Component.text(" mace tier!", NamedTextColor.GOLD)));
            }
            for (String cmd : up.rewardCommands) {
                getServer().dispatchCommand(getServer().getConsoleSender(),
                        cmd.replace("%player%", killer.getName()));
            }
        } else {
            killer.sendActionBar(Component.text("Kills toward next tier: " + d.progress + "/"
                    + cur.killsToNext, NamedTextColor.YELLOW));
        }
        refresh(killer);
    }

    // ------------------------------------------------------------------
    // Join / respawn / quit
    // ------------------------------------------------------------------

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        refreshLater(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data(e.getPlayer());
        refreshLater(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data(e.getPlayer());
        saveData();
    }

    // ------------------------------------------------------------------
    // Protection: unenchantable, undroppable, stays in the player's inventory
    // ------------------------------------------------------------------

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        Inventory inv = e.getInventory();
        if (isMace(inv.getItem(0)) || isMace(inv.getItem(1))) e.setResult(null);
    }

    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        if (isMace(e.getItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        if (isMace(e.getItem())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isMace(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onItemFrame(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame) {
            Player p = e.getPlayer();
            if (isMace(p.getInventory().getItemInMainHand())
                    || isMace(p.getInventory().getItemInOffHand())) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) return;

        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

        if (clickedTop && isMace(e.getCursor())) {
            e.setCancelled(true);
            return;
        }
        if (!clickedTop && e.isShiftClick() && isMace(e.getCurrentItem())) {
            e.setCancelled(true);
            return;
        }
        if (clickedTop && (e.getClick() == ClickType.NUMBER_KEY || e.getClick() == ClickType.SWAP_OFFHAND)) {
            Player p = (Player) e.getWhoClicked();
            ItemStack swap = e.getClick() == ClickType.SWAP_OFFHAND
                    ? p.getInventory().getItemInOffHand()
                    : p.getInventory().getItem(e.getHotbarButton());
            if (isMace(swap)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || !isMace(e.getOldCursor())) return;
        for (int slot : e.getRawSlots()) {
            if (slot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Safety net (e.g. grindstone): restores the correct enchants after closing any menu. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) refreshLater(p);
    }

    /** Helper for commands: find a player's stored data by name (online or offline). */
    public PlayerData dataByName(String name) {
        for (PlayerData d : data.values()) {
            if (d.name.equalsIgnoreCase(name)) return d;
        }
        return null;
    }

}
