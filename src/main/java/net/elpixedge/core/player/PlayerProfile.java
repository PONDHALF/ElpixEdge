package net.elpixedge.core.player;

import net.elpixedge.core.utils.StatType;
import org.bukkit.ChatColor;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerProfile {

    private final UUID uuid;
    private double currentMana;
    private final Map<StatType, Double> cachedStats = new EnumMap<>(StatType.class);

    private double comboDamage = 0.0;
    private long lastHitTime = 0;
    private long globalCooldown = 0;
    private boolean hideDamageHolo = false;

    // Priority action bar message (skill cooldown, errors)
    private String actionbarStaticMsg = "";
    private long actionbarAlertTime = 0;

    // EXP gain popups — max 2 distinct labels, same label merges
    public static class ExpPopup {
        public final String color;
        public final String label;
        public int amount;
        public ExpPopup(String color, String label, int amount) {
            this.color = color;
            this.label = label;
            this.amount = amount;
        }
    }
    private final LinkedHashMap<String, ExpPopup> expPopups = new LinkedHashMap<>();
    private long popupExpireTime = 0;

    public PlayerProfile(UUID uuid) {
        this.uuid = uuid;
        this.currentMana = 0.0;
    }

    public UUID getUuid() { return uuid; }

    public double getCurrentMana() { return currentMana; }
    public void setCurrentMana(double currentMana) { this.currentMana = currentMana; }

    public Map<StatType, Double> getStats() { return cachedStats; }
    public void updateStats(Map<StatType, Double> newStats) {
        cachedStats.clear();
        cachedStats.putAll(newStats);
    }
    public double getStat(StatType type) { return cachedStats.getOrDefault(type, 0.0); }

    public double getComboDamage() { return comboDamage; }
    public void addComboDamage(double damage) {
        this.comboDamage += damage;
        this.lastHitTime = System.currentTimeMillis();
    }
    public void resetComboDamage() { this.comboDamage = 0.0; }
    public long getLastHitTime() { return lastHitTime; }

    public long getGlobalCooldown() { return globalCooldown; }
    public void setGlobalCooldown(long v) { this.globalCooldown = v; }

    public boolean isHideDamageHolo() { return hideDamageHolo; }
    public void setHideDamageHolo(boolean v) { this.hideDamageHolo = v; }

    // Priority message (overrides popups for 2 seconds)
    public void setActionBarMessage(String msg, long durationMs) {
        this.actionbarStaticMsg = msg;
        this.actionbarAlertTime = System.currentTimeMillis() + durationMs;
    }
    public String getActiveActionBarMessage() {
        if (System.currentTimeMillis() < actionbarAlertTime) return actionbarStaticMsg;
        return "";
    }

    // EXP popup system — same label accumulates, max 2 distinct labels shown
    public void addExpPopup(String label, String color, int amount) {
        if (expPopups.containsKey(label)) {
            expPopups.get(label).amount += amount;
        } else {
            if (expPopups.size() >= 2) {
                // Remove the oldest entry to make room
                expPopups.remove(expPopups.keySet().iterator().next());
            }
            expPopups.put(label, new ExpPopup(color, label, amount));
        }
        popupExpireTime = System.currentTimeMillis() + 2000;
    }

    // Build the EXP popup display string (e.g. "Combat +100 // HUSK +50")
    public String buildPopupText() {
        if (System.currentTimeMillis() > popupExpireTime) {
            expPopups.clear();
            return "";
        }
        if (expPopups.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ExpPopup ep : expPopups.values()) {
            if (!first) sb.append(ChatColor.DARK_GRAY).append(" // ");
            sb.append(ep.color).append(ep.label).append(" +").append(ep.amount);
            first = false;
        }
        return sb.toString();
    }
}
