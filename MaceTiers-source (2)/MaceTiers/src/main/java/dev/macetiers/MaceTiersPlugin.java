package dev.macetiers;

import net.kyori.adventure.text.Component;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Keyed;
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
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MaceTiersPlugin extends JavaPlugin implements Listener {

    private final List<Tier> tiers = new ArrayList<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Set<UUID> pendingBans = new HashSet<>();
    private final Map<UUID, MaceAnimation> animations = new HashMap<>();
    private NamespacedKey maceKey;
    private NamespacedKey recipeKey;
    private File dataFile;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maceKey = new NamespacedKey(this, "tier_mace");
        recipeKey = new NamespacedKey(this, "super_mace");
        dataFile = new File(getDataFolder(), "data.yml");

        try {
            getServer().getPluginManager().addPermission(
                    new Permission("macetiers.bypass", "Can never be death banned by MaceTiers",
                            PermissionDefault.FALSE));
        } catch (IllegalArgumentException ignored) {
            // already registered
        }

        loadTiers();
        loadData();
        registerRecipe();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new DragonMaceManager(this), this);
        getServer().getPluginManager().registerEvents(new DragonMaceAbilities(), this);

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

        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);

        refreshAllOnline();
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(animations.keySet())) {
            Player ap = getServer().getPlayer(id);
            if (ap != null) endAnimation(ap, true);
            else animations.remove(id);
        }
        getServer().removeRecipe(recipeKey);
        saveData();
    }

    public void reloadAll() {
        reloadConfig();
        loadTiers();
        registerRecipe();
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

        // Older configs don't have the Super Ultimate tier yet, so add it automatically.
        if (getConfig().getBoolean("super-mace.enabled", true) && tierById(superTierId()) == null) {
            Tier sup = new Tier(superTierId());
            sup.display = "Super Ultimate";
            sup.color = "#FF3333";
            sup.killsToNext = 1;
            sup.enchants.put("density", 6);
            sup.enchants.put("wind_burst", 1);
            tiers.add(sup);
            saveTiers();
            getLogger().info("Added the '" + sup.id + "' tier to config.yml.");
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
    // Super tier helpers
    // ------------------------------------------------------------------

    public String superTierId() {
        return getConfig().getString("super-mace.tier", "super_ultimate").toLowerCase(Locale.ROOT);
    }

    public boolean isSuperTier(Tier t) {
        return t != null && t.id.equals(superTierId());
    }

    /** The next tier reachable by kills, or null if this is the last one (the super tier is craft-only). */
    public Tier nextKillTier(Tier cur) {
        int idx = tiers.indexOf(cur);
        if (idx < 0 || idx + 1 >= tiers.size()) return null;
        Tier n = tiers.get(idx + 1);
        return isSuperTier(n) ? null : n;
    }

    public Tier lastKillTier() {
        Tier t = tiers.get(0);
        Tier n;
        while ((n = nextKillTier(t)) != null) t = n;
        return t;
    }

    public int killsToBan() {
        return Math.max(1, getConfig().getInt("super-mace.kills-to-ban", 3));
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
                ConfigurationSection h = s.getConfigurationSection(key + ".hunts");
                if (h != null) {
                    for (String vk : h.getKeys(false)) {
                        try {
                            d.hunts.put(UUID.fromString(vk), h.getInt(vk));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
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
            for (Map.Entry<UUID, Integer> h : d.hunts.entrySet()) {
                y.set(p + ".hunts." + h.getKey(), h.getValue());
            }
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

    private boolean isBundle(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return item.getItemMeta() instanceof BundleMeta;
    }

    private void applyTier(ItemStack item, Player p) {
        PlayerData d = data(p);
        Tier tier = tierOf(d);
        int idx = indexOf(tier);
        Tier next = nextKillTier(tier);
        boolean last = next == null;
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
        if (next != null) {
            lore.add(line("Kills: " + d.progress + "/" + tier.killsToNext
                    + " to " + next.display, NamedTextColor.GRAY));
        }
        if (isSuperTier(tier)) {
            lore.add(line("Kill the same player " + killsToBan() + " times to death ban them.",
                    NamedTextColor.RED));
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
            ItemStack it = contents[i];\
            if (DragonMaceManager.isDragonMace(it)) {
                found = true;
                continue;
            }
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
    // Super Ultimate Mace crafting (dragon egg on top of a breeze rod)
    // ------------------------------------------------------------------

    public void registerRecipe() {
        getServer().removeRecipe(recipeKey);
        if (!getConfig().getBoolean("super-mace.enabled", true)) return;

        Tier sup = tierById(superTierId());
        String name = sup != null ? sup.display : "Super Ultimate";
        TextColor color = sup != null ? sup.textColor() : NamedTextColor.RED;

        ItemStack result = new ItemStack(Material.MACE);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text(name + " Mace", color).decoration(TextDecoration.ITALIC, false));
        result.setItemMeta(meta);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, result);
        recipe.shape("E", "B");
        recipe.setIngredient('E', Material.DRAGON_EGG);
        recipe.setIngredient('B', Material.BREEZE_ROD);
        getServer().addRecipe(recipe);
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!(e.getRecipe() instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        e.setCancelled(true); // we handle the crafting ourselves
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isAnimating(p)) return;

        Tier sup = tierById(superTierId());
        if (sup == null) return;
        PlayerData d = data(p);
        Tier cur = tierOf(d);

        if (isSuperTier(cur)) {
            p.sendMessage(Component.text("You already have the " + sup.display + " Mace!", NamedTextColor.RED));
            return;
        }
        if (getConfig().getBoolean("super-mace.require-final-tier", true) && nextKillTier(cur) != null) {
            p.sendMessage(Component.text("You must reach the " + lastKillTier().display
                    + " tier before you can craft this.", NamedTextColor.RED));
            return;
        }

        if (getConfig().getBoolean("super-mace.consume-ingredients", true)) {
            ItemStack[] matrix = e.getInventory().getMatrix();
            for (int i = 0; i < matrix.length; i++) {
                ItemStack it = matrix[i];
                if (it == null) continue;
                if (it.getAmount() <= 1) {
                    matrix[i] = null;
                } else {
                    it.setAmount(it.getAmount() - 1);
                }
            }
            e.getInventory().setMatrix(matrix);
        }
        e.getInventory().setResult(null);

        if (getConfig().getBoolean("super-mace.animation.enabled", true)) {
            MaceAnimation anim = new MaceAnimation(this, p, sup);
            animations.put(p.getUniqueId(), anim);
            getServer().getScheduler().runTask(this, anim::start);
        } else {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
            grantSuper(p, sup);
        }
        getServer().getScheduler().runTask(this, () -> p.updateInventory());
    }

    /** Actually gives the player the super tier (replaces their old mace) and announces it. */
    public void grantSuper(Player p, Tier sup) {
        setTier(p, sup);
        selectMace(p);
        p.sendMessage(Component.text("You forged the ", NamedTextColor.GOLD)
                .append(Component.text(sup.display + " Mace", sup.textColor(), TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GOLD)));
        if (getConfig().getBoolean("super-mace.broadcast-craft", true)) {
            getServer().broadcast(Component.text(p.getName() + " forged the ", NamedTextColor.GOLD)
                    .append(Component.text(sup.display + " Mace", sup.textColor(), TextDecoration.BOLD))
                    .append(Component.text("!", NamedTextColor.GOLD)));
        }
    }

    /** Puts the mace in the player's hand if it is on the hotbar. */
    private void selectMace(Player p) {
        for (int i = 0; i < 9; i++) {
            if (isMace(p.getInventory().getItem(i))) {
                p.getInventory().setHeldItemSlot(i);
                return;
            }
        }
    }

    public boolean isAnimating(Player p) {
        return animations.containsKey(p.getUniqueId());
    }

    public void endAnimation(Player p, boolean grant) {
        MaceAnimation a = animations.remove(p.getUniqueId());
        if (a != null) a.finish(grant);
    }

    private boolean busy(Player p) {
        return animations.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Death bans
    // ------------------------------------------------------------------

    private String fill(String s, String killer, String victim, int kills) {
        return s.replace("%killer%", killer).replace("%victim%", victim)
                .replace("%kills%", String.valueOf(kills));
    }

    /** Returns false if the player is immune (bypass permission) or already being banned. */
    public boolean deathBan(Player victim, String reason) {
        String perm = getConfig().getString("death-ban.bypass-permission", "macetiers.bypass");
        if (perm != null && !perm.isEmpty() && victim.hasPermission(perm)) return false;

        UUID id = victim.getUniqueId();
        if (!pendingBans.add(id)) return false;

        String name = victim.getName();
        String template = getConfig().getString("death-ban.command", "ban %player% %reason%");
        getServer().getScheduler().runTask(this, () -> {
            pendingBans.remove(id);
            String cmd = template.replace("%player%", name).replace("%reason%", reason);
            getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
        });
        return true;
    }

    // ------------------------------------------------------------------
    // Kills and deaths
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        Player killer = victim.getKiller();

        endAnimation(victim, false);
        e.getDrops().removeIf(this::isMace);
        if (disabledWorld(victim.getWorld())) return;

        PlayerData vd = data(victim);
        Tier before = tierOf(vd);
        vd.deaths++;
        vd.streak = 0;
        vd.progress = 0;

        boolean killedByPlayer = killer != null && !killer.equals(victim);
        boolean ultBan = false;

        switch (deathPenalty()) {
            case "NONE" -> {
            }
            case "DOWN_ONE" -> {
                int idx = indexOf(before);
                vd.tier = tiers.get(Math.max(0, idx - 1)).id;
            }
            case "ULT_BAN" -> {
                vd.tier = tiers.get(0).id;
                Tier ult = tierById(getConfig().getString("death-ban.ult-tier", "ultimate"));
                boolean needsPlayer = getConfig().getBoolean("death-ban.ult-requires-player-kill", false);
                if (ult != null && indexOf(before) >= indexOf(ult) && (!needsPlayer || killedByPlayer)) {
                    ultBan = true;
                }
            }
            default -> vd.tier = tiers.get(0).id; // RESET
        }

        if (getConfig().getBoolean("super-mace.reset-counts-on-death", true) && !isSuperTier(tierOf(vd))) {
            vd.hunts.clear();
        }

        victim.sendMessage(Component.text("You died. Your mace is now "
                + tierOf(vd).display + " tier.", NamedTextColor.RED));

        if (ultBan) {
            String reason = fill(getConfig().getString("death-ban.ult-reason",
                            "You died while holding the Ultimate Mace."),
                    killer != null ? killer.getName() : "nobody", victim.getName(), 0);
            deathBan(victim, reason);
        }

        if (killedByPlayer && !disabledWorld(killer.getWorld())) {
            registerKill(killer, victim);
        }
    }

    private void registerKill(Player killer, Player victim) {
        PlayerData d = data(killer);
        d.kills++;
        d.streak++;
        d.bestStreak = Math.max(d.bestStreak, d.streak);

        Tier cur = tierOf(d);

        if (isSuperTier(cur)) {
            superKill(killer, victim, d);
            return;
        }

        Tier next = nextKillTier(cur);
        if (next == null) {
            killer.sendActionBar(Component.text("Your mace is already maxed out! Craft the Super mace "
                    + "with a dragon egg on top of a breeze rod.", NamedTextColor.LIGHT_PURPLE));
            return;
        }

        d.progress++;
        if (d.progress >= cur.killsToNext) {
            Tier up = next;
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
            if (nextKillTier(up) == null && getConfig().getBoolean("settings.broadcast-final-tier", true)) {
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

    /** Killing with the Super Ultimate Mace: the same victim N times = death ban. */
    private void superKill(Player killer, Player victim, PlayerData d) {
        int need = killsToBan();
        int count = d.hunts.merge(victim.getUniqueId(), 1, Integer::sum);

        if (count >= need) {
            d.hunts.remove(victim.getUniqueId());
            String reason = fill(getConfig().getString("super-mace.ban-reason",
                            "Death banned: killed %kills% times by %killer%."),
                    killer.getName(), victim.getName(), need);
            if (deathBan(victim, reason)) {
                getServer().broadcast(Component.text(victim.getName() + " was death banned by "
                        + killer.getName() + "'s " + tierOf(d).display + " Mace!", NamedTextColor.DARK_RED));
            } else {
                killer.sendMessage(Component.text(victim.getName()
                        + " can't be death banned (immune).", NamedTextColor.GRAY));
            }
            return;
        }

        int left = need - count;
        killer.sendMessage(Component.text("You killed " + victim.getName() + " (" + count + "/" + need
                + "). " + left + " more kill" + (left == 1 ? "" : "s") + " to death ban them.",
                NamedTextColor.RED));
        if (getConfig().getBoolean("super-mace.warn-victim", true)) {
            victim.sendMessage(Component.text(killer.getName() + " has killed you " + count + "/" + need
                    + " times with the " + tierOf(d).display + " Mace. " + left + " more and you're death banned!",
                    NamedTextColor.DARK_RED));
        }
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
        endAnimation(e.getPlayer(), true);
        data(e.getPlayer());
        saveData();
    }

    // ------------------------------------------------------------------
    // Protection: unenchantable, undroppable, no containers, no bundles
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
        if (busy(e.getPlayer()) || isMace(e.getItemDrop().getItemStack())) e.setCancelled(true);
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
        if (e.getWhoClicked() instanceof Player cp && busy(cp)) {
            e.setCancelled(true);
            return;
        }
        // Bundles: can't put the mace into a bundle (cursor bundle -> mace slot, or cursor mace -> bundle slot)
        if (getConfig().getBoolean("settings.block-bundles", true)) {
            ItemStack cursor = e.getCursor();
            ItemStack current = e.getCurrentItem();
            if ((isBundle(cursor) && isMace(current)) || (isMace(cursor) && isBundle(current))) {
                e.setCancelled(true);
                return;
            }
        }

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

    // ------------------------------------------------------------------
    // While the Super mace cutscene plays, the player can't do anything
    // ------------------------------------------------------------------

    @EventHandler
    public void onAnimMove(PlayerMoveEvent e) {
        if (!busy(e.getPlayer()) || e.getTo() == null) return;
        if (e.getFrom().getX() != e.getTo().getX()
                || e.getFrom().getY() != e.getTo().getY()
                || e.getFrom().getZ() != e.getTo().getZ()) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onAnimInteract(PlayerInteractEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimInteractEntity(PlayerInteractEntityEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimHeld(PlayerItemHeldEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimSwap(PlayerSwapHandItemsEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimCommand(PlayerCommandPreprocessEvent e) {
        if (busy(e.getPlayer()) && getConfig().getBoolean("super-mace.animation.block-commands", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onAnimBreak(BlockBreakEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimPlace(BlockPlaceEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && busy(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onAnimDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && busy(p)) e.setCancelled(true);
    }

    /** Helper for commands: find a player's stored data by name (online or offline). */
    public PlayerData dataByName(String name) {
        for (PlayerData d : data.values()) {
            if (d.name.equalsIgnoreCase(name)) return d;
        }
        return null;
    }
}
