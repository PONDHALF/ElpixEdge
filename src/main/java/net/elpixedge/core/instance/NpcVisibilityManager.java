package net.elpixedge.core.instance;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.data.property.NpcVisibility;
import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.tag.TagManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per‑player FancyNPC visibility based on {@code npc_visibility.yml}.
 *
 * Rules:
 *   - {@code hide_if_has_tags}: hide the NPC when the player possesses ANY of the listed tags.
 *   - {@code show_only_if_has_tags}: show the NPC only when the player possesses ANY of the listed tags.
 *   - If both rules exist for an NPC, {@code hide_if_has_tags} wins.
 */
public class NpcVisibilityManager implements Module, Listener {

    private final ElpixEdge plugin;
    // NPC id → rule configuration
    private final Map<String, NpcVisibilityRule> rules = new HashMap<>();
    // Cache of FancyNPC objects (id → Npc). Updated lazily; missing entries are retried later.
    private final Map<String, Npc> npcCache = new ConcurrentHashMap<>();
    // Player UUID → (NPC id → last known visibility state)
    private final Map<UUID, Map<String, Boolean>> lastKnownVisibility = new ConcurrentHashMap<>();
    // Periodic task that reevaluates visibility for all online players (fallback safety).
    private BukkitRunnable reevalTask;

    public NpcVisibilityManager(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Periodic reevaluation – runs every 2 seconds as a safety net.
        reevalTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (rules.isEmpty()) return;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    evaluateVisibility(player);
                }
            }
        };
        reevalTask.runTaskTimer(plugin, 40L, 40L);

        plugin.getLogger().info("NpcVisibilityManager enabled - " + rules.size() + " rule(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (reevalTask != null) reevalTask.cancel();
        rules.clear();
        npcCache.clear();
        lastKnownVisibility.clear();
    }

    /** Reload configuration and immediately refresh visibility for all online players. */
    public void reloadConfig() {
        loadConfig();
        npcCache.clear(); // clear stale references – NPCs may have been recreated.
        for (Player player : Bukkit.getOnlinePlayers()) {
            evaluateVisibility(player);
        }
        plugin.getLogger().info("NpcVisibilityManager reloaded - " + rules.size() + " rule(s) active.");
    }

    public Collection<NpcVisibilityRule> getAllRules() {
        return Collections.unmodifiableCollection(rules.values());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Slight delay ensures the player's data (tags) are loaded before we evaluate.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = event.getPlayer();
            if (p.isOnline()) evaluateVisibility(p);
        }, 20L);
    }

    /** Load {@code npc_visibility.yml} into {@link #rules}. */
    private void loadConfig() {
        rules.clear();
        File file = new File(plugin.getDataFolder(), "npc_visibility.yml");
        if (!file.exists()) plugin.saveResource("npc_visibility.yml", false);
        if (!file.exists()) {
            plugin.getLogger().warning("NpcVisibilityManager: npc_visibility.yml not found at " + file.getAbsolutePath());
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("npc_visibility");
        if (root == null) {
            plugin.getLogger().warning("NpcVisibilityManager: Missing 'npc_visibility' section in " + file.getAbsolutePath());
            return;
        }
        for (String npcId : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(npcId);
            if (sec == null) continue;
            NpcVisibilityRule rule = new NpcVisibilityRule();
            rule.hideIfHasTags = readTags(sec, "hide_if_has_tags", "hide_if_has_tag");
            rule.showOnlyIfHasTags = readTags(sec, "show_only_if_has_tags", "show_only_if_has_tag");
            rules.put(npcId, rule);
        }
        plugin.getLogger().info("NpcVisibilityManager: Loaded " + rules.size() + " rule(s) from " + file.getAbsolutePath());
    }

    /** Normalise a tag list – supports a list or a single string. */
    private List<String> readTags(ConfigurationSection section, String listKey, String singleKey) {
        List<String> raw;
        if (section.isList(listKey)) {
            raw = section.getStringList(listKey);
        } else {
            String single = section.getString(listKey, section.getString(singleKey, null));
            raw = single == null ? List.of() : List.of(single);
        }
        List<String> out = new ArrayList<>();
        for (String tag : raw) {
            if (tag == null) continue;
            String cleaned = tag.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty()) out.add(cleaned);
        }
        return out;
    }

    /** Evaluate all NPC rules for a single player and apply any visibility changes. */
    public void evaluateVisibility(Player player) {
        if (player == null || !player.isOnline()) return;
        TagManager tagMgr = plugin.getModule(TagManager.class);
        if (tagMgr == null) return;
        for (Map.Entry<String, NpcVisibilityRule> entry : rules.entrySet()) {
            String npcId = entry.getKey();
            NpcVisibilityRule rule = entry.getValue();
            boolean shouldBeVisible = shouldPlayerSeeNpc(player, tagMgr, rule);
            applyVisibility(player, npcId, shouldBeVisible);
        }
    }

    /** Immediate refresh for a player (used by TagManager). */
    public void refreshVisibilityNow(Player player) {
        if (player == null || !player.isOnline()) return;
        evaluateVisibility(player);
        // One‑tick deferred re‑check to catch any race‑conditions with tag updates.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) evaluateVisibility(player);
        }, 1L);
    }

    /** Determine if a player should see the NPC according to the rule hierarchy. */
    private boolean shouldPlayerSeeNpc(Player player, TagManager tagMgr, NpcVisibilityRule rule) {
        // hide_if_has_tags takes precedence
        if (hasAnyTag(player, tagMgr, rule.hideIfHasTags)) return false;
        // If show_only_if_has_tags is defined, player must have at least one of them
        if (!rule.showOnlyIfHasTags.isEmpty())
            return hasAnyTag(player, tagMgr, rule.showOnlyIfHasTags);
        // No explicit allow list → visible by default
        return true;
    }

    private boolean hasAnyTag(Player player, TagManager tagMgr, List<String> tags) {
        for (String tag : tags) {
            if (tagMgr.hasTag(player, tag)) return true;
        }
        return false;
    }

    /** Core visibility handling. Consolidated into a single, thread‑safe method. */
    private void applyVisibility(Player player, String npcId, boolean visibleByTags) {
        try {
            // ----- Fetch (or cache) the FancyNPC instance -----
            Npc npc = npcCache.computeIfAbsent(npcId, id -> {
                Npc n = FancyNpcsPlugin.get().getNpcManager().getNpc(id);
                return n; // may be null if NPC not yet created
            });
            if (npc == null) {
                scheduleRetry(player, npcId, visibleByTags);
                return;
            }

            NpcData data = npc.getData();
            if (data == null) {
                scheduleRetry(player, npcId, visibleByTags);
                return;
            }

            // Extra readiness check: if the NPC has no location yet, it's not fully initialized.
            Location loc = data.getLocation();
            if (loc == null || loc.getWorld() == null) {
                scheduleRetry(player, npcId, visibleByTags);
                return;
            }

            // Ensure the NPC is in manual mode so we can control per‑player visibility.
            if (data.getVisibility() != NpcVisibility.MANUAL) {
                data.setVisibility(NpcVisibility.MANUAL);
                npc.updateForAll();
            }

            boolean withinDistance = isPlayerWithinDistance(player, data);
            boolean shouldSpawnNow = visibleByTags && withinDistance;
            Boolean previous = rememberVisibility(player.getUniqueId(), npcId, shouldSpawnNow);
            boolean stateChanged = previous == null || previous != shouldSpawnNow;

            // Update FancyNPC's internal viewer list – use the NPC‑ID‑based overload to avoid a null‑name key.
            try {
                String npcIdKey = npc.getData().getId(); // UUID string used as the map key inside FancyNPC
                if (visibleByTags) {
                    NpcVisibility.ManualNpcVisibility.addDistantViewer(npcIdKey, player.getUniqueId());
                } else {
                    NpcVisibility.ManualNpcVisibility.removeDistantViewer(npcIdKey, player.getUniqueId());
                }
            } catch (Exception ex) {
                // If the error is the known "key is null" transient issue, silently retry later.
                if (ex.getMessage() != null && ex.getMessage().contains("key is null")) {
                    scheduleRetry(player, npcId, visibleByTags);
                }
                // For other errors, ignore as well – the periodic task will eventually fix it.
            }

            // Spawn / despawn the NPC for this player only if its visible state actually changed.
            if (stateChanged) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        npc.remove(player);
                        if (shouldSpawnNow) npc.spawn(player);
                    } catch (Exception ex) {
                        // Again, suppress the "key is null" noise.
                        if (ex.getMessage() == null || !ex.getMessage().contains("key is null")) {
                            plugin.getLogger().warning("[NpcVisibility] Failed to update NPC '" + npcId + "' for player '" + player.getName() + "': " + ex.getMessage());
                        }
                    }
                });
            }
        } catch (Exception ex) {
            // Suppress the transient FancyNPC init error (contains "key" and "null").
            String msg = ex.getMessage();
            if (msg == null || !(msg.contains("key") && msg.contains("null"))) {
                plugin.getLogger().warning("[NpcVisibility] Error for NPC '" + npcId + "': " + msg);
            }
        }
    }

    /** Helper to schedule a retry after a short delay. */
    private void scheduleRetry(Player player, String npcId, boolean visibleByTags) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> applyVisibility(player, npcId, visibleByTags), 20L);
    }


    /** Distance check – respects NPC's visibility distance (defaults to 40 blocks). */
    private boolean isPlayerWithinDistance(Player player, NpcData data) {
        Location npcLoc = data.getLocation();
        if (npcLoc == null || npcLoc.getWorld() == null) return false;
        if (!player.getWorld().equals(npcLoc.getWorld())) return false;
        int distance = data.getVisibilityDistance();
        if (distance <= 0) distance = 40;
        return player.getLocation().distanceSquared(npcLoc) <= ((long) distance * distance);
    }

    /** Remember the last known visibility state for a player/NPC pair. */
    private Boolean rememberVisibility(UUID playerId, String npcId, boolean visible) {
        Map<String, Boolean> map = lastKnownVisibility.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        return map.put(npcId, visible);
    }

    /** Simple DTO for rule configuration. */
    public static class NpcVisibilityRule {
        public List<String> hideIfHasTags = List.of();
        public List<String> showOnlyIfHasTags = List.of();
    }
}
