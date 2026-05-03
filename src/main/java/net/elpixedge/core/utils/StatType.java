package net.elpixedge.core.utils;

import org.bukkit.ChatColor;

public enum StatType {
    HEALTH(ChatColor.RED, "❤ Health", "HP"),
    DEFENSE(ChatColor.GREEN, "❈ Defense", "DEF"),
    DAMAGE(ChatColor.RED, "❁ Damage", "DMG"),
    MAGIC_DAMAGE(ChatColor.LIGHT_PURPLE, "☄ Magic Damage", "M.DMG"),
    CRIT_CHANCE(ChatColor.BLUE, "☣ Crit Chance", "CC", "%"),
    CRIT_DAMAGE(ChatColor.BLUE, "☠ Crit Damage", "CD", "%"),
    MAX_MANA(ChatColor.AQUA, "✎ Max Mana", "MANA"),
    MANA_REGEN(ChatColor.AQUA, "☯ Mana Regen", "M.REGEN", "/s"),
    GCD_REDUC(ChatColor.YELLOW, "⚡ GCD Reduction", "GCD-", "%"),
    DROP_MULT(ChatColor.LIGHT_PURPLE, "✦ Drop Multiplier", "DROP", "x"),
    MINING_SPEED(ChatColor.GOLD, "⛏ Mining Speed", "MINE"),
    GATHERING_SPEED(ChatColor.GREEN, "🌿 Gathering Speed", "GATH"),
    MANA_COST(ChatColor.DARK_AQUA, "☄ Mana Cost", "COST");

    private final ChatColor color;
    private final String fullName;
    private final String shortName;
    private final String suffix;

    StatType(ChatColor color, String fullName, String shortName) {
        this(color, fullName, shortName, "");
    }

    StatType(ChatColor color, String fullName, String shortName, String suffix) {
        this.color = color;
        this.fullName = fullName;
        this.shortName = shortName;
        this.suffix = suffix;
    }

    // Display the full name (e.g. "❤ Health")
    public String getDisplayName() {
        return color + fullName;
    }

    // Format for display (e.g. "❤ Health: 20.0")
    public String formatFull(double value) {
        if (this == GCD_REDUC) value *= 100;
        return color + fullName + ": " + ChatColor.WHITE + String.format("%.1f", value) + suffix;
    }

    // Format for short display in lore e.g. "❁ Damage+5.0"
    public String formatShort(double value) {
        if (this == GCD_REDUC) value *= 100;
        String sign = value >= 0 ? "+" : "";
        return color + fullName + ChatColor.WHITE + sign + String.format("%.1f", value) + suffix;
    }

    // Format for item stat display in lore: no '+' sign, integer when whole (e.g. "❁ Damage 50")
    public String formatItem(double value) {
        if (this == GCD_REDUC) value *= 100;
        String formatted = (value == Math.floor(value) && !Double.isInfinite(value))
                ? String.valueOf((int) value)
                : String.format("%.1f", value);
        return color + fullName + ChatColor.WHITE + " " + formatted + suffix;
    }
}
