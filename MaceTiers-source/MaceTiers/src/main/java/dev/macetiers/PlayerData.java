package dev.macetiers;

public final class PlayerData {
    public String name = "?";
    public String tier;          // tier id
    public int progress;         // kills made at the current tier
    public int kills;
    public int deaths;
    public int streak;
    public int bestStreak;
    public int windLevel;        // 0 = use the tier's default
}
