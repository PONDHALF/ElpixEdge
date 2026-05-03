package net.elpixedge.core.tag;

import net.elpixedge.core.ElpixEdge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * QuestConfigManager — Loads quest trigger definitions from quest_triggers.yml
 * (falls back to config.yml for backward compatibility).
 *
 * Each trigger entry defines:
 *   - type:          interaction type (e.g. "npc_click", "enter_region")
 *   - npc_name:      NPC display name to match (for npc_click type)
 *   - required_tags: list of tags the player must have for the trigger to fire
 *   - add_tags:      list of tags to add on successful trigger
 *   - remove_tags:   list of tags to remove on successful trigger
 *   - teleport:      optional teleport destination ("world, x, y, z, yaw, pitch")
 *   - message:       optional message to send to the player
 *
 * Triggers are keyed by their config ID and stored in a HashMap for O(1) lookup.
 * This manager is NOT a Module — it's a utility loaded by QuestTriggerListener.
 */
public class QuestConfigManager {

    private final ElpixEdge plugin;
    private final Map<String, QuestTrigger> triggers = new LinkedHashMap<>();

    // Index: NPC name (lowercase) -> list of trigger IDs that respond to that NPC
    private final Map<String, List<String>> npcNameIndex = new HashMap<>();

    public QuestConfigManager(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads all quest triggers from quest_triggers.yml (primary) or config.yml (fallback).
     * Should be called on module enable and on config reload.
     */
    public void loadTriggers() {
        triggers.clear();
        npcNameIndex.clear();

        ConfigurationSection sec = loadTriggerSection();
        if (sec == null) {
            plugin.getLogger().info("QuestConfigManager — No quest_triggers section found.");
            return;
        }

        for (String triggerId : sec.getKeys(false)) {
            ConfigurationSection tSec = sec.getConfigurationSection(triggerId);
            if (tSec == null) continue;

            QuestTrigger trigger = new QuestTrigger();
            trigger.id = triggerId;
            trigger.type = tSec.getString("type", "npc_click").toLowerCase().trim();
            trigger.npcName = tSec.getString("npc_name", "").trim();
            trigger.requiredTags = normalizeTags(tSec.getStringList("required_tags"));
            trigger.addTags = normalizeTags(tSec.getStringList("add_tags"));
            trigger.removeTags = normalizeTags(tSec.getStringList("remove_tags"));
            trigger.message = tSec.getString("message", "");
            trigger.teleportStr = tSec.getString("teleport", "");
            trigger.teleportLocation = parseTeleport(trigger.teleportStr);

            triggers.put(triggerId, trigger);

            // Build NPC name index for fast lookup
            if (trigger.type.equals("npc_click") && !trigger.npcName.isEmpty()) {
                String key = trigger.npcName.toLowerCase();
                npcNameIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(triggerId);
            }
        }

        plugin.getLogger().info("QuestConfigManager — Loaded " + triggers.size() + " quest trigger(s).");
    }

    /**
     * Loads the quest_triggers section from quest_triggers.yml first,
     * falls back to config.yml for backward compatibility.
     */
    private ConfigurationSection loadTriggerSection() {
        // Primary: quest_triggers.yml
        File triggerFile = new File(plugin.getDataFolder(), "quest_triggers.yml");
        if (!triggerFile.exists()) {
            plugin.saveResource("quest_triggers.yml", false);
        }
        if (triggerFile.exists()) {
            YamlConfiguration triggerCfg = YamlConfiguration.loadConfiguration(triggerFile);
            ConfigurationSection sec = triggerCfg.getConfigurationSection("quest_triggers");
            if (sec != null) {
                plugin.getLogger().info("QuestConfigManager — Loading from quest_triggers.yml");
                return sec;
            }
        }

        // Fallback: config.yml (backward compatibility)
        plugin.getLogger().info("QuestConfigManager — Falling back to config.yml quest_triggers section.");
        return plugin.getConfig().getConfigurationSection("quest_triggers");
    }

    /**
     * Returns all triggers that match a given NPC name (case-insensitive).
     *
     * @param npcName the NPC's custom display name (color-stripped)
     * @return list of matching QuestTrigger objects (empty if none)
     */
    public List<QuestTrigger> getTriggersForNpc(String npcName) {
        if (npcName == null || npcName.isEmpty()) return Collections.emptyList();
        List<String> ids = npcNameIndex.get(npcName.toLowerCase().trim());
        if (ids == null) return Collections.emptyList();

        List<QuestTrigger> result = new ArrayList<>();
        for (String id : ids) {
            QuestTrigger t = triggers.get(id);
            if (t != null) result.add(t);
        }
        return result;
    }

    /**
     * Returns all triggers of a specific type.
     *
     * @param type the trigger type (e.g. "enter_region")
     * @return list of matching QuestTrigger objects
     */
    public List<QuestTrigger> getTriggersByType(String type) {
        if (type == null) return Collections.emptyList();
        String normalizedType = type.toLowerCase().trim();
        List<QuestTrigger> result = new ArrayList<>();
        for (QuestTrigger t : triggers.values()) {
            if (t.type.equals(normalizedType)) result.add(t);
        }
        return result;
    }

    /**
     * Returns a specific trigger by its config ID.
     *
     * @param triggerId the trigger ID from config
     * @return the QuestTrigger, or null if not found
     */
    public QuestTrigger getTrigger(String triggerId) {
        return triggers.get(triggerId);
    }

    /**
     * Returns all loaded triggers.
     */
    public Collection<QuestTrigger> getAllTriggers() {
        return Collections.unmodifiableCollection(triggers.values());
    }

    // ==========================================
    // Helpers
    // ==========================================

    private List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String tag : raw) {
            String trimmed = tag.trim().toLowerCase();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * Parses a teleport string of format "world, x, y, z[, yaw, pitch]".
     * Returns null if the string is empty or invalid.
     */
    private Location parseTeleport(String teleportStr) {
        if (teleportStr == null || teleportStr.isEmpty()) return null;
        String[] parts = teleportStr.split(",");
        if (parts.length < 4) return null;

        try {
            String worldName = parts[0].trim();
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length > 4 ? Float.parseFloat(parts[4].trim()) : 0f;
            float pitch = parts.length > 5 ? Float.parseFloat(parts[5].trim()) : 0f;

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("QuestConfigManager — World '" + worldName + "' not found for teleport.");
                return null;
            }

            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("QuestConfigManager — Invalid teleport format: " + teleportStr);
            return null;
        }
    }

    // ==========================================
    // Data Class
    // ==========================================

    /**
     * Represents a single quest trigger definition loaded from config.
     */
    public static class QuestTrigger {
        /** Config key ID (e.g. "example_quest_start") */
        public String id;
        /** Trigger type: "npc_click", "enter_region", etc. */
        public String type;
        /** NPC display name to match (for npc_click type, color-stripped) */
        public String npcName;
        /** Tags the player must have for this trigger to activate */
        public List<String> requiredTags;
        /** Tags to add to the player when the trigger fires */
        public List<String> addTags;
        /** Tags to remove from the player when the trigger fires */
        public List<String> removeTags;
        /** Message to send to the player (supports & color codes) */
        public String message;
        /** Raw teleport string from config */
        public String teleportStr;
        /** Parsed teleport location (null if no teleport) */
        public Location teleportLocation;
    }
}
