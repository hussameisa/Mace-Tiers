package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /macetiers (alias /mt) - everything an admin can change. */
public final class AdminCommand implements TabExecutor {

    private final MaceTiersPlugin plugin;

    public AdminCommand(MaceTiersPlugin plugin) {
        this.plugin = plugin;
    }

    private void ok(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.GREEN));
    }

    private void err(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.RED));
    }

    private void info(CommandSender s, String m) {
        s.sendMessage(Component.text(m, NamedTextColor.GRAY));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                ok(sender, "MaceTiers reloaded.");
            }
            case "player" -> playerCmd(sender, args);
            case "tier" -> tierCmd(sender, args);
            case "setting" -> settingCmd(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(Component.text("--- MaceTiers admin ---", NamedTextColor.GOLD));
        String[] lines = {
                "/mt reload",
                "/mt player set <player> <tier>",
                "/mt player up|down|reset|refresh|resetstats <player>",
                "/mt tier list",
                "/mt tier info <tier>",
                "/mt tier add <id> [position]   |   /mt tier remove <id>",
                "/mt tier enchant <tier> <enchant> <level|remove>",
                "/mt tier clearenchants <tier>",
                "/mt tier display <tier> <name...>",
                "/mt tier color <tier> <#RRGGBB|colorname>",
                "/mt tier kills <tier> <kills needed to reach next tier>",
                "/mt tier reward add <tier> <command...>  (%player%)",
                "/mt tier reward list|clear <tier>",
                "/mt setting <name> <value>   (death-penalty, windburst-max, broadcast-final-tier,",
                "     upgrade-sound, unbreakable, block-bundles, super-mace, kills-to-ban,",
                "     require-final-tier, consume-ingredients, reset-counts-on-death, warn-victim, ult-ban-tier, animation,",
                "     animation-height, animation-ticks)",
        };
        for (String l : lines) info(s, l);
    }

    // ------------------------------------------------------------------
    // /mt player ...
    // ------------------------------------------------------------------

    private void playerCmd(CommandSender s, String[] a) {
        if (a.length < 3) {
            err(s, "Usage: /mt player <set|up|down|reset|refresh|resetstats> <player> [tier]");
            return;
        }
        Player target = plugin.getServer().getPlayerExact(a[2]);
        if (target == null) {
            err(s, "That player must be online.");
            return;
        }
        PlayerData d = plugin.data(target);
        List<Tier> tiers = plugin.tiers();
        int idx = plugin.indexOf(plugin.tierOf(d));

        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "set" -> {
                Tier t = a.length >= 4 ? plugin.tierById(a[3]) : null;
                if (t == null) {
                    err(s, "Unknown tier. Use /mt tier list");
                    return;
                }
                plugin.setTier(target, t);
                ok(s, target.getName() + " is now " + t.display + ".");
            }
            case "up" -> {
                plugin.setTier(target, tiers.get(Math.min(tiers.size() - 1, idx + 1)));
                ok(s, target.getName() + " moved up to " + plugin.tierOf(d).display + ".");
            }
            case "down" -> {
                plugin.setTier(target, tiers.get(Math.max(0, idx - 1)));
                ok(s, target.getName() + " moved down to " + plugin.tierOf(d).display + ".");
            }
            case "reset" -> {
                plugin.setTier(target, tiers.get(0));
                ok(s, target.getName() + " reset to " + tiers.get(0).display + ".");
            }
            case "refresh" -> {
                plugin.refresh(target);
                ok(s, "Gave/refreshed " + target.getName() + "'s mace.");
            }
            case "resetstats" -> {
                d.kills = 0;
                d.deaths = 0;
                d.streak = 0;
                d.bestStreak = 0;
                ok(s, "Stats reset for " + target.getName() + ".");
            }
            default -> err(s, "Unknown player action.");
        }
    }

    // ------------------------------------------------------------------
    // /mt tier ...
    // ------------------------------------------------------------------

    private void tierCmd(CommandSender s, String[] a) {
        if (a.length < 2) {
            err(s, "Usage: /mt tier <list|info|add|remove|enchant|clearenchants|display|color|kills|reward>");
            return;
        }
        String action = a[1].toLowerCase(Locale.ROOT);

        if (action.equals("list")) {
            int i = 1;
            for (Tier t : plugin.tiers()) {
                s.sendMessage(Component.text(i++ + ". ", NamedTextColor.GRAY)
                        .append(Component.text(t.display, t.textColor()))
                        .append(Component.text(" (" + t.id + ") " + enchList(t), NamedTextColor.DARK_GRAY)));
            }
            return;
        }
        if (action.equals("add")) {
            addTier(s, a);
            return;
        }
        if (action.equals("reward")) {
            rewardCmd(s, a);
            return;
        }

        if (a.length < 3) {
            err(s, "Missing tier id.");
            return;
        }
        Tier t = plugin.tierById(a[2]);
        if (t == null) {
            err(s, "Unknown tier '" + a[2] + "'. Use /mt tier list");
            return;
        }

        switch (action) {
            case "info" -> {
                s.sendMessage(Component.text("--- ", NamedTextColor.GOLD)
                        .append(Component.text(t.display, t.textColor()))
                        .append(Component.text(" ---", NamedTextColor.GOLD)));
                info(s, "id: " + t.id + "  color: " + t.color);
                info(s, "kills to reach next tier: " + t.killsToNext);
                info(s, "enchants: " + enchList(t));
                info(s, "reward commands: " + (t.rewardCommands.isEmpty() ? "none" : t.rewardCommands.toString()));
            }
            case "remove" -> {
                if (plugin.tiers().size() <= 1) {
                    err(s, "You need at least one tier.");
                    return;
                }
                plugin.tiers().remove(t);
                plugin.tiersChanged();
                ok(s, "Removed tier " + t.id + ". Players on it fall back to the first tier.");
            }
            case "enchant" -> {
                if (a.length < 5) {
                    err(s, "Usage: /mt tier enchant <tier> <enchant> <level|remove>");
                    return;
                }
                if (plugin.findEnchant(a[3]) == null) {
                    err(s, "Unknown enchantment '" + a[3] + "'. Try density, wind_burst, breach, sharpness...");
                    return;
                }
                String key = a[3].toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (a[4].equalsIgnoreCase("remove") || a[4].equals("0")) {
                    t.enchants.remove(key);
                    plugin.tiersChanged();
                    ok(s, "Removed " + key + " from " + t.display + ".");
                    return;
                }
                int level;
                try {
                    level = Integer.parseInt(a[4]);
                } catch (NumberFormatException ex) {
                    err(s, "Level must be a number (1-255) or 'remove'.");
                    return;
                }
                if (level < 1 || level > 255) {
                    err(s, "Level must be between 1 and 255.");
                    return;
                }
                t.enchants.put(key, level);
                plugin.tiersChanged();
                ok(s, t.display + " now has " + key + " " + level + ".");
            }
            case "clearenchants" -> {
                t.enchants.clear();
                plugin.tiersChanged();
                ok(s, "Cleared all enchants from " + t.display + ".");
            }
            case "display" -> {
                if (a.length < 4) {
                    err(s, "Usage: /mt tier display <tier> <name...>");
                    return;
                }
                t.display = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                plugin.tiersChanged();
                ok(s, "Display name set to " + t.display + ".");
            }
            case "color" -> {
                if (a.length < 4) {
                    err(s, "Usage: /mt tier color <tier> <#RRGGBB|colorname>");
                    return;
                }
                TextColor c = TextColor.fromHexString(a[3]);
                if (c == null) c = NamedTextColor.NAMES.value(a[3].toLowerCase(Locale.ROOT));
                if (c == null) {
                    err(s, "Use a hex color like #FF8800 or a name like gold, aqua, red.");
                    return;
                }
                t.color = c.asHexString();
                plugin.tiersChanged();
                ok(s, "Color set to " + t.color + ".");
            }
            case "kills" -> {
                if (a.length < 4) {
                    err(s, "Usage: /mt tier kills <tier> <number>");
                    return;
                }
                try {
                    int n = Integer.parseInt(a[3]);
                    if (n < 1) throw new NumberFormatException();
                    t.killsToNext = n;
                } catch (NumberFormatException ex) {
                    err(s, "Kills must be a whole number of at least 1.");
                    return;
                }
                plugin.tiersChanged();
                ok(s, t.display + " now needs " + t.killsToNext + " kill(s) to reach the next tier.");
            }
            default -> err(s, "Unknown tier action.");
        }
    }

    private void addTier(CommandSender s, String[] a) {
        if (a.length < 3) {
            err(s, "Usage: /mt tier add <id> [position]");
            return;
        }
        String id = a[2].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]+")) {
            err(s, "Tier id may only contain letters, numbers, _ and -.");
            return;
        }
        if (plugin.tierById(id) != null) {
            err(s, "That tier already exists.");
            return;
        }
        Tier t = new Tier(id);
        int pos = plugin.tiers().size();
        if (a.length >= 4) {
            try {
                pos = Math.max(0, Math.min(plugin.tiers().size(), Integer.parseInt(a[3]) - 1));
            } catch (NumberFormatException ex) {
                err(s, "Position must be a number.");
                return;
            }
        }
        plugin.tiers().add(pos, t);
        plugin.tiersChanged();
        ok(s, "Added tier '" + id + "' at position " + (pos + 1) + ". Set it up with /mt tier enchant|display|color.");
    }

    private void rewardCmd(CommandSender s, String[] a) {
        if (a.length < 4) {
            err(s, "Usage: /mt tier reward <add|list|clear> <tier> [command...]");
            return;
        }
        Tier t = plugin.tierById(a[3]);
        if (t == null) {
            err(s, "Unknown tier.");
            return;
        }
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (a.length < 5) {
                    err(s, "Usage: /mt tier reward add <tier> <command...>");
                    return;
                }
                String command = String.join(" ", Arrays.copyOfRange(a, 4, a.length));
                if (command.startsWith("/")) command = command.substring(1);
                t.rewardCommands.add(command);
                plugin.tiersChanged();
                ok(s, "Added reward command to " + t.display + ".");
            }
            case "list" -> info(s, t.rewardCommands.isEmpty() ? "No reward commands." : t.rewardCommands.toString());
            case "clear" -> {
                t.rewardCommands.clear();
                plugin.tiersChanged();
                ok(s, "Cleared reward commands for " + t.display + ".");
            }
            default -> err(s, "Use add, list or clear.");
        }
    }

    private String enchList(Tier t) {
        if (t.enchants.isEmpty()) return "no enchants";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : t.enchants.entrySet()) parts.add(e.getKey() + " " + e.getValue());
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------
    // /mt setting ...
    // ------------------------------------------------------------------

    private void settingCmd(CommandSender s, String[] a) {
        if (a.length < 3) {
            err(s, "Usage: /mt setting <name> <value>");
            return;
        }
        String key = a[1].toLowerCase(Locale.ROOT);
        String v = a[2];

        switch (key) {
            case "death-penalty" -> {
                String up = v.toUpperCase(Locale.ROOT);
                if (!List.of("RESET", "DOWN_ONE", "NONE", "ULT_BAN").contains(up)) {
                    err(s, "Use RESET, DOWN_ONE, NONE or ULT_BAN.");
                    return;
                }
                plugin.getConfig().set("settings.death-penalty", up);
            }
            case "windburst-max" -> {
                try {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > 10) throw new NumberFormatException();
                    plugin.getConfig().set("settings.windburst-max", n);
                } catch (NumberFormatException ex) {
                    err(s, "Use a number from 1 to 10.");
                    return;
                }
            }
            case "kills-to-ban" -> {
                try {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > 100) throw new NumberFormatException();
                    plugin.getConfig().set("super-mace.kills-to-ban", n);
                } catch (NumberFormatException ex) {
                    err(s, "Use a number from 1 to 100.");
                    return;
                }
            }
            case "animation-height" -> {
                try {
                    double n = Double.parseDouble(v);
                    if (n < 0 || n > 20) throw new NumberFormatException();
                    plugin.getConfig().set("super-mace.animation.float-height", n);
                } catch (NumberFormatException ex) {
                    err(s, "Use a number of blocks from 0 to 20, like 4 or 6.5.");
                    return;
                }
            }
            case "animation-ticks" -> {
                try {
                    int n = Integer.parseInt(v);
                    if (n < 40 || n > 600) throw new NumberFormatException();
                    plugin.getConfig().set("super-mace.animation.duration-ticks", n);
                } catch (NumberFormatException ex) {
                    err(s, "Use a number of ticks from 40 to 600 (20 ticks = 1 second).");
                    return;
                }
            }
            case "ult-ban-tier" -> {
                if (plugin.tierById(v) == null) {
                    err(s, "Unknown tier. Use /mt tier list");
                    return;
                }
                plugin.getConfig().set("death-ban.ult-tier", v.toLowerCase(Locale.ROOT));
            }
            case "broadcast-final-tier", "upgrade-sound", "unbreakable", "block-bundles",
                 "super-mace", "require-final-tier", "consume-ingredients",
                 "reset-counts-on-death", "warn-victim", "animation" -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    err(s, "Use true or false.");
                    return;
                }
                String path = switch (key) {
                    case "super-mace" -> "super-mace.enabled";
                    case "animation" -> "super-mace.animation.enabled";
                    case "require-final-tier", "consume-ingredients",
                         "reset-counts-on-death", "warn-victim" -> "super-mace." + key;
                    default -> "settings." + key;
                };
                plugin.getConfig().set(path, Boolean.parseBoolean(v));
            }
            default -> {
                err(s, "Unknown setting. Use /mt help to see the list.");
                return;
            }
        }
        plugin.saveConfig();
        plugin.registerRecipe();
        plugin.refreshAllOnline();
        ok(s, key + " = " + v);
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    private List<String> tierIds() {
        List<String> l = new ArrayList<>();
        for (Tier t : plugin.tiers()) l.add(t.id);
        return l;
    }

    private List<String> onlineNames() {
        List<String> l = new ArrayList<>();
        plugin.getServer().getOnlinePlayers().forEach(p -> l.add(p.getName()));
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 1) {
            return filter(List.of("help", "reload", "player", "tier", "setting"), a[0]);
        }
        String first = a[0].toLowerCase(Locale.ROOT);

        if (first.equals("player")) {
            if (a.length == 2) return filter(List.of("set", "up", "down", "reset", "refresh", "resetstats"), a[1]);
            if (a.length == 3) return filter(onlineNames(), a[2]);
            if (a.length == 4 && a[1].equalsIgnoreCase("set")) return filter(tierIds(), a[3]);
        }

        if (first.equals("tier")) {
            if (a.length == 2) {
                return filter(List.of("list", "info", "add", "remove", "enchant", "clearenchants",
                        "display", "color", "kills", "reward"), a[1]);
            }
            String action = a[1].toLowerCase(Locale.ROOT);
            if (action.equals("reward")) {
                if (a.length == 3) return filter(List.of("add", "list", "clear"), a[2]);
                if (a.length == 4) return filter(tierIds(), a[3]);
                return List.of();
            }
            if (a.length == 3 && !action.equals("add") && !action.equals("list")) return filter(tierIds(), a[2]);
            if (action.equals("enchant")) {
                if (a.length == 4) return filter(plugin.enchantNames(), a[3]);
                if (a.length == 5) return filter(List.of("1", "2", "3", "4", "5", "remove"), a[4]);
            }
            if (action.equals("color") && a.length == 4) {
                return filter(new ArrayList<>(NamedTextColor.NAMES.keys()), a[3]);
            }
        }

        if (first.equals("setting")) {
            if (a.length == 2) {
                return filter(List.of("death-penalty", "windburst-max", "broadcast-final-tier",
                        "upgrade-sound", "unbreakable", "block-bundles", "super-mace", "kills-to-ban",
                        "require-final-tier", "consume-ingredients", "reset-counts-on-death",
                        "warn-victim", "ult-ban-tier", "animation", "animation-height", "animation-ticks"), a[1]);
            }
            if (a.length == 3) {
                return switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "death-penalty" -> filter(List.of("RESET", "DOWN_ONE", "NONE", "ULT_BAN"), a[2]);
                    case "windburst-max" -> filter(List.of("1", "2", "3", "4", "5"), a[2]);
                    case "kills-to-ban" -> filter(List.of("1", "2", "3", "5", "10"), a[2]);
                    case "ult-ban-tier" -> filter(tierIds(), a[2]);
                    case "animation-height" -> filter(List.of("1.5", "3", "4", "6", "8"), a[2]);
                    case "animation-ticks" -> filter(List.of("60", "100", "140", "200"), a[2]);
                    default -> filter(List.of("true", "false"), a[2]);
                };
            }
        }
        return List.of();
    }
}
