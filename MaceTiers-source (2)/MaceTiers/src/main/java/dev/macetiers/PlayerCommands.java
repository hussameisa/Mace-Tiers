package dev.macetiers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** /mace, /macestats, /macetop, /windburst */
public final class PlayerCommands implements TabExecutor {

    private final MaceTiersPlugin plugin;

    public PlayerCommands(MaceTiersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "mace" -> mace(sender);
            case "macestats" -> stats(sender, args);
            case "macetop" -> top(sender);
            case "windburst" -> windburst(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void mace(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        PlayerData d = plugin.data(p);
        Tier t = plugin.tierOf(d);
        int idx = plugin.indexOf(t);
        Tier next = plugin.nextKillTier(t);

        p.sendMessage(Component.text("Your mace: ", NamedTextColor.GRAY)
                .append(Component.text(t.display, t.textColor(), TextDecoration.BOLD))
                .append(Component.text("  (tier " + (idx + 1) + "/" + plugin.tiers().size() + ")",
                        NamedTextColor.GRAY)));
        p.sendMessage(Component.text("Enchants: " + describe(t, d), NamedTextColor.GRAY));

        if (plugin.isSuperTier(t)) {
            int need = plugin.killsToBan();
            p.sendMessage(Component.text("Kill the same player " + need + " times to death ban them.",
                    NamedTextColor.RED));
            for (Map.Entry<java.util.UUID, Integer> h : d.hunts.entrySet()) {
                PlayerData target = plugin.allData().get(h.getKey());
                String name = target != null ? target.name : h.getKey().toString();
                p.sendMessage(Component.text(" - " + name + ": " + h.getValue() + "/" + need,
                        NamedTextColor.DARK_RED));
            }
        } else if (next == null) {
            p.sendMessage(Component.text("You are at the highest kill tier! Craft the Super mace: "
                    + "dragon egg on top of a breeze rod.", NamedTextColor.LIGHT_PURPLE));
        } else {
            p.sendMessage(Component.text("Next: " + next.display + " ("
                    + d.progress + "/" + t.killsToNext + " kills)", NamedTextColor.YELLOW));
        }
    }

    private String describe(Tier t, PlayerData d) {
        if (t.enchants.isEmpty()) return "none";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : t.enchants.entrySet()) {
            int lvl = e.getValue();
            if (e.getKey().equals("wind_burst") && d.windLevel > 0) {
                lvl = Math.min(d.windLevel, plugin.windMax());
            }
            parts.add(e.getKey() + " " + lvl);
        }
        return String.join(", ", parts);
    }

    private void stats(CommandSender sender, String[] args) {
        PlayerData d;
        if (args.length >= 1) {
            d = plugin.dataByName(args[0]);
            if (d == null) {
                sender.sendMessage(Component.text("No data for that player.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player p) {
            d = plugin.data(p);
        } else {
            sender.sendMessage("Usage: /macestats <player>");
            return;
        }
        Tier t = plugin.tierOf(d);
        double kd = d.deaths == 0 ? d.kills : (double) d.kills / d.deaths;
        sender.sendMessage(Component.text("--- " + d.name + " ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Tier: ", NamedTextColor.GRAY)
                .append(Component.text(t.display, t.textColor())));
        sender.sendMessage(Component.text("Kills: " + d.kills + "  Deaths: " + d.deaths
                + "  K/D: " + String.format("%.2f", kd), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Streak: " + d.streak + "  Best streak: " + d.bestStreak,
                NamedTextColor.GRAY));
    }

    private void top(CommandSender sender) {
        List<PlayerData> list = new ArrayList<>(plugin.allData().values());
        list.sort(Comparator
                .<PlayerData>comparingInt(d -> plugin.indexOf(plugin.tierOf(d))).reversed()
                .thenComparing(Comparator.<PlayerData>comparingInt(d -> d.progress).reversed())
                .thenComparing(Comparator.<PlayerData>comparingInt(d -> d.kills).reversed()));

        sender.sendMessage(Component.text("--- Mace Leaderboard ---", NamedTextColor.GOLD));
        int n = Math.min(10, list.size());
        for (int i = 0; i < n; i++) {
            PlayerData d = list.get(i);
            Tier t = plugin.tierOf(d);
            sender.sendMessage(Component.text((i + 1) + ". " + d.name + " - ", NamedTextColor.GRAY)
                    .append(Component.text(t.display, t.textColor()))
                    .append(Component.text(" (" + d.kills + " kills)", NamedTextColor.DARK_GRAY)));
        }
        if (n == 0) sender.sendMessage(Component.text("Nobody yet.", NamedTextColor.GRAY));
    }

    private void windburst(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        PlayerData d = plugin.data(p);
        Tier t = plugin.tierOf(d);
        if (!plugin.hasWind(t)) {
            p.sendMessage(Component.text("Your current tier doesn't have Wind Burst yet.", NamedTextColor.RED));
            return;
        }
        if (args.length != 1) {
            p.sendMessage(Component.text("Usage: /windburst <1-" + plugin.windMax() + "|reset>",
                    NamedTextColor.RED));
            return;
        }
        if (args[0].equalsIgnoreCase("reset")) {
            d.windLevel = 0;
            plugin.refresh(p);
            p.sendMessage(Component.text("Wind Burst reset to the tier default.", NamedTextColor.AQUA));
            return;
        }
        int level;
        try {
            level = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            level = 0;
        }
        if (level < 1 || level > plugin.windMax()) {
            p.sendMessage(Component.text("Wind Burst level must be between 1 and "
                    + plugin.windMax() + ".", NamedTextColor.RED));
            return;
        }
        d.windLevel = level;
        plugin.refresh(p);
        p.sendMessage(Component.text("Wind Burst set to " + level + ".", NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("windburst") && args.length == 1) {
            List<String> l = new ArrayList<>();
            for (int i = 1; i <= plugin.windMax(); i++) l.add(String.valueOf(i));
            l.add("reset");
            return l;
        }
        if (cmd.getName().equalsIgnoreCase("macestats") && args.length == 1) {
            List<String> l = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(pl -> l.add(pl.getName()));
            return l;
        }
        return List.of();
    }
}
