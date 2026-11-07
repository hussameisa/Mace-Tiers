package dev.macetiers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerData {
    public String name = "?";
    public String tier;          // tier id
    public int progress;         // kills made at the current tier
    public int kills;
    public int deaths;
    public int streak;
    public int bestStreak;
    public int windLevel;        // 0 = use the tier's default
    /** Super mace: victim UUID -> how many times this player has killed them. */
    public final Map<UUID, Integer> hunts = new HashMap<>();
}
