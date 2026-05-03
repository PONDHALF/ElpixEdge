package net.elpixedge.core.tag;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * TagManager — Core tag CRUD system for the Quest/Dungeon foundation.
 *
 * Design:
 *   - ConcurrentHashMap<UUID, Set<String>> for thread-safe in-memory cache.
 *   - PDC (PersistentDataContainer) on the Player entity for session persistence.
 *   - Async YAML file I/O for cross-session persistence (survives server restarts).
 *   - Main thread reads from cache only (zero disk I/O).
 *   - Dirty flag per player to avoid redundant writes.
 *
 * API:
 *   addTag(Player, String)    — adds a tag (cache + PDC + async save)
 *   removeTag(Player, String) — removes a tag (cache + PDC + async save)
 *   hasTag(Player, String)    — reads from cache only (O(1), no I/O)
 *   getTags(Player)           — returns unmodifiable Set from cache
 */
public class TagManager implements Module, Listener {

    private final ElpixEdge plugin;
    private final ConcurrentHashMap<UUID, Set<String>> tagCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final NamespacedKey pdcTagsKey;
    private BukkitRunnable autoSaveTask;

    // Separator for serializing tags in PDC (comma-delimited)
    private static final String TAG_SEPARATOR = ",";

    public TagManager(ElpixEdge plugin) {
        this.plugin = plugin;
        this.pdcTagsKey = new NamespacedKey(plugin, "player_tags");
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Load tags for all online players (handles /reload scenarios)
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerTags(p);
        }

        // Start periodic auto-save task
        int saveIntervalSec = plugin.getConfig().getInt("tag_system.save_interval", 300);
        long saveIntervalTicks = saveIntervalSec * 20L;
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                flushDirtyPlayersAsync();
            }
        };
        autoSaveTask.runTaskTimer(plugin, saveIntervalTicks, saveIntervalTicks);

        plugin.getLogger().info("TagManager enabled — tag cache ready.");
    }



    @Override
    public void onDisable() {
        // Flush all dirty tags synchronously on shutdown to prevent data loss
        flushAllPlayersSync();
        tagCache.clear();
        dirtyPlayers.clear();
        plugin.getLogger().info("TagManager disabled — all tags saved.");
    }

    // ==========================================
    // Public API — Called from main thread
    // ==========================================

    /**
     * Adds a tag to the player. Updates cache, PDC, and schedules async file save.
     *
     * @param player the target player
     * @param tag    the tag string (e.g. "quest_step_1", "dungeon_cleared")
     */
    public void addTag(Player player, String tag) {
        if (player == null || tag == null || tag.isEmpty()) return;
        String normalizedTag = normalizeTag(tag);
        if (normalizedTag.isEmpty()) return;

        runTagMutation(player, () -> {
            Set<String> tags = tagCache.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            if (tags.add(normalizedTag)) {
                syncToPDC(player);
                markDirty(player.getUniqueId());
                notifyNpcVisibility(player);
            }
        });
    }

    /**
     * Removes a tag from the player. Updates cache, PDC, and schedules async file save.
     *
     * @param player the target player
     * @param tag    the tag string
     */
    public void removeTag(Player player, String tag) {
        if (player == null || tag == null || tag.isEmpty()) return;
        String normalizedTag = normalizeTag(tag);
        if (normalizedTag.isEmpty()) return;

        runTagMutation(player, () -> {
            Set<String> tags = tagCache.get(player.getUniqueId());
            if (tags != null && tags.remove(normalizedTag)) {
                syncToPDC(player);
                markDirty(player.getUniqueId());
                notifyNpcVisibility(player);
            }
        });
    }

    /**
     * Checks if the player has a specific tag. Reads from cache only — no I/O.
     *
     * @param player the target player
     * @param tag    the tag to check
     * @return true if the player has the tag
     */
    public boolean hasTag(Player player, String tag) {
        if (player == null || tag == null || tag.isEmpty()) return false;
        String normalizedTag = normalizeTag(tag);
        if (normalizedTag.isEmpty()) return false;

        Set<String> tags = tagCache.get(player.getUniqueId());
        return tags != null && tags.contains(normalizedTag);
    }

    /**
     * Returns all tags for the player as an unmodifiable Set.
     *
     * @param player the target player
     * @return unmodifiable set of tags (empty set if none)
     */
    public Set<String> getTags(Player player) {
        if (player == null) return Collections.emptySet();
        Set<String> tags = tagCache.get(player.getUniqueId());
        return tags != null ? Collections.unmodifiableSet(tags) : Collections.emptySet();
    }

    private String normalizeTag(String tag) {
        return tag.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Player PDC and FancyNPC visibility updates must run on the main server thread.
     */
    private void runTagMutation(Player player, Runnable mutation) {
        if (Bukkit.isPrimaryThread()) {
            mutation.run();
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                mutation.run();
            }
        });
    }

    /**
     * Immediately refresh NPC visibility for the given player.
     * This runs on the main thread (or schedules a task if called off‑thread).
     */
    private void notifyNpcVisibility(Player player) {
        if (player == null) return;
        net.elpixedge.core.instance.NpcVisibilityManager npcMgr =
                plugin.getModule(net.elpixedge.core.instance.NpcVisibilityManager.class);
        if (npcMgr == null) return;
        if (Bukkit.isPrimaryThread()) {
            npcMgr.refreshVisibilityNow(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> npcMgr.refreshVisibilityNow(player));
        }
    }

    // ==========================================
    // Player Join / Quit Listeners
    // ==========================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadPlayerTags(event.getPlayer());
        // Tags are now loaded — safely evaluate NPC visibility
        notifyNpcVisibility(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (dirtyPlayers.contains(uuid)) {
            // Save synchronously on quit to prevent data loss for this player
            savePlayerSync(uuid);
            dirtyPlayers.remove(uuid);
        }
        tagCache.remove(uuid);
    }

    // ==========================================
    // Tag Loading (Main Thread — PDC is safe here)
    // ==========================================

    private void loadPlayerTags(Player player) {
        UUID uuid = player.getUniqueId();
        Set<String> tags = ConcurrentHashMap.newKeySet();

        // Try loading from PDC first (session data)
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String pdcData = pdc.getOrDefault(pdcTagsKey, PersistentDataType.STRING, "");
        if (!pdcData.isEmpty()) {
            for (String tag : pdcData.split(TAG_SEPARATOR)) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }
        }

        // Then try loading from file (cross-session persistence)
        File tagFile = getTagFile(uuid);
        if (tagFile.exists()) {
            try {
                org.bukkit.configuration.file.YamlConfiguration yaml =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(tagFile);
                List<String> fileTags = yaml.getStringList("tags");
                for (String tag : fileTags) {
                    String trimmed = tag.trim().toLowerCase();
                    if (!trimmed.isEmpty()) tags.add(trimmed);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load tags for " + uuid, e);
            }
        }

        tagCache.put(uuid, tags);

        // Sync loaded file data back to PDC (so PDC is always up-to-date)
        syncToPDC(player);
    }

    // ==========================================
    // PDC Sync (Main Thread)
    // ==========================================

    private void syncToPDC(Player player) {
        Runnable task = () -> {
            if (!player.isOnline()) return;
            Set<String> tags = tagCache.get(player.getUniqueId());
            if (tags == null || tags.isEmpty()) {
                player.getPersistentDataContainer().set(pdcTagsKey, PersistentDataType.STRING, "");
                return;
            }
            String serialized = String.join(TAG_SEPARATOR, tags);
            player.getPersistentDataContainer().set(pdcTagsKey, PersistentDataType.STRING, serialized);
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    // ==========================================
    // Async File Persistence
    // ==========================================

    private void markDirty(UUID uuid) {
        dirtyPlayers.add(uuid);
    }

    /**
     * Saves all dirty player tags asynchronously.
     * Called periodically by the auto-save task.
     */
    private void flushDirtyPlayersAsync() {
        if (dirtyPlayers.isEmpty()) return;

        // Snapshot dirty UUIDs and their tag data
        Map<UUID, Set<String>> snapshot = new HashMap<>();
        Iterator<UUID> it = dirtyPlayers.iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Set<String> tags = tagCache.get(uuid);
            if (tags != null) {
                snapshot.put(uuid, new HashSet<>(tags));
            }
            it.remove();
        }

        // Write to disk asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<UUID, Set<String>> entry : snapshot.entrySet()) {
                    writeTagFile(entry.getKey(), entry.getValue());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Saves a single player's tags synchronously (used on quit and shutdown).
     */
    private void savePlayerSync(UUID uuid) {
        Set<String> tags = tagCache.get(uuid);
        if (tags == null) return;
        writeTagFile(uuid, new HashSet<>(tags));
    }

    /**
     * Flushes all cached player tags synchronously (used on server shutdown).
     */
    private void flushAllPlayersSync() {
        for (Map.Entry<UUID, Set<String>> entry : tagCache.entrySet()) {
            writeTagFile(entry.getKey(), new HashSet<>(entry.getValue()));
        }
    }

    /**
     * Writes tags to a per-player YAML file. Thread-safe (operates on copies).
     */
    private void writeTagFile(UUID uuid, Set<String> tags) {
        File tagFile = getTagFile(uuid);
        try {
            tagFile.getParentFile().mkdirs();
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("uuid", uuid.toString());
            yaml.set("tags", new ArrayList<>(tags));
            yaml.save(tagFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save tags for " + uuid, e);
        }
    }

    private File getTagFile(UUID uuid) {
        return new File(plugin.getDataFolder(), "tags" + File.separator + uuid.toString() + ".yml");
    }
}
