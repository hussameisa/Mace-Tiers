package dev.macetiers;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Tier {
    public final String id;
    public String display;
    public String color = "#FFFFFF";
    public int killsToNext = 1;
    /** enchant id (e.g. "density") -> level */
    public final Map<String, Integer> enchants = new LinkedHashMap<>();
    public final List<String> rewardCommands = new ArrayList<>();

    public Tier(String id) {
        this.id = id;
        this.display = id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    public TextColor textColor() {
        TextColor c = TextColor.fromHexString(color);
        return c == null ? NamedTextColor.WHITE : c;
    }
}
