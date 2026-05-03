package net.elpixedge.core.quest;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.instance.CinematicController;
import net.elpixedge.core.instance.SchematicInstanceManager;
import net.elpixedge.core.tag.TagManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

/**
 * QuestEngine — Executes quest scripts and cross-system Action Sequences.
 *
 * Reads from quests.yml.
 * Supported sequence actions:
 *   - START_CUTSCENE [scene_id]
 *   - MOVE_NPC [npc_id] [world,x,y,z]
 *   - PASTE_INSTANCE [schematic_name]
 *   - CLEANUP_INSTANCE
 *   - SPAWN_MOB [type] [count]
 *   - GIVE_REWARD [item_type] [custom_name]
 *
 * Provides Repeat Dialogue logic and objective completion hooks.
 */
public class QuestEngine implements Module {

    private final ElpixEdge plugin;
    private final Map<String, QuestScript> scripts = new LinkedHashMap<>();

    // Tracks the last dialogue sent to a player for a specific quest to handle Repeat Dialogue
    private final Map<UUID, Map<String, String>> repeatDialogues = new HashMap<>();

    public QuestEngine(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        loadQuests();
        plugin.getLogger().info("QuestEngine enabled — loaded " + scripts.size() + " quest(s).");
    }

    @Override
    public void onDisable() {
        scripts.clear();
        repeatDialogues.clear();
    }

    private void loadQuests() {
        scripts.clear();
        File f = new File(plugin.getDataFolder(), "quests.yml");
        // Always overwrite from jar so server stays in sync with build
        plugin.saveResource("quests.yml", true);
        if (!f.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = cfg.getConfigurationSection("quests");
        if (sec == null) {
            plugin.getLogger().warning("[QuestEngine] quests.yml has no 'quests' section!");
            return;
        }

        for (String id : sec.getKeys(false)) {
            ConfigurationSection qSec = sec.getConfigurationSection(id);
            if (qSec == null) continue;

            QuestScript qs = new QuestScript();
            qs.id = id;
            qs.requiredTags = qSec.getStringList("required_tags");
            qs.repeatDialogue = qSec.getString("repeat_dialogue", "");
            qs.sequence = qSec.getStringList("sequence");
            plugin.getLogger().info("[QuestEngine] Loaded quest: '" + id + "' tags=" + qs.requiredTags + " steps=" + qs.sequence.size());
            scripts.put(id, qs);
        }
    }

    public void reloadConfig() {
        loadQuests();
        plugin.getLogger().info("QuestEngine — Reloaded " + scripts.size() + " quest(s).");
    }

    /**
     * Executes a quest script sequence for a player.
     * Checks requirements and handles repeat dialogue.
     *
     * @param player  The target player
     * @param questId The ID of the quest in quests.yml
     */
    public void executeQuest(Player player, String questId) {
        if (player == null || questId == null) return;
        plugin.getLogger().info("[QuestEngine] executeQuest called: player=" + player.getName() + " quest=" + questId);
        QuestScript qs = scripts.get(questId);
        if (qs == null) {
            plugin.getLogger().warning("[QuestEngine] Unknown quest: '" + questId + "' — loaded quests: " + scripts.keySet());
            return;
        }

        TagManager tagMgr = plugin.getModule(TagManager.class);
        if (tagMgr != null && !qs.requiredTags.isEmpty()) {
            plugin.getLogger().info("[QuestEngine] Checking " + qs.requiredTags.size() + " required tags for " + questId);
            for (String tag : qs.requiredTags) {
                if (tag.startsWith("!")) {
                    boolean has = tagMgr.hasTag(player, tag.substring(1));
                    plugin.getLogger().info("[QuestEngine]   !" + tag.substring(1) + " → player has it: " + has + " (must NOT have)");
                    if (has) {
                        sendRepeatDialogue(player, questId, qs.repeatDialogue);
                        return;
                    }
                } else {
                    boolean has = tagMgr.hasTag(player, tag);
                    plugin.getLogger().info("[QuestEngine]   " + tag + " → player has it: " + has + " (must have)");
                    if (!has) {
                        sendRepeatDialogue(player, questId, qs.repeatDialogue);
                        return;
                    }
                }
            }
        } else {
            plugin.getLogger().info("[QuestEngine] No required tags for " + questId + " — running sequence of " + qs.sequence.size() + " step(s)");
        }

        executeSequence(player, qs.sequence);
    }

    private void sendRepeatDialogue(Player player, String questId, String dialogue) {
        if (dialogue == null || dialogue.isEmpty()) return;
        
        // Cache the repeat dialogue text so we know what they're stuck on
        repeatDialogues.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>()).put(questId, dialogue);
        
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', dialogue));
    }

    /**
     * Executes a list of action strings sequentially with proper delays.
     * Actions are processed one at a time; WAIT [ticks] inserts a delay.
     * PASTE_INSTANCE spawns mobs/next actions only after paste completes.
     */
    public void executeSequence(Player player, List<String> sequence) {
        if (sequence == null || sequence.isEmpty()) {
            plugin.getLogger().warning("[QuestEngine] executeSequence called with empty/null sequence!");
            return;
        }
        plugin.getLogger().info("[QuestEngine] Starting sequence with " + sequence.size() + " steps for " + player.getName());
        executeStep(player, sequence, 0, 0L);
    }

    private void executeStep(Player player, List<String> sequence, int index, long delayTicks) {
        if (index >= sequence.size()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            String trimmed = sequence.get(index).trim();
            plugin.getLogger().info("[QuestEngine] Step " + index + ": \"" + trimmed + "\"");
            if (trimmed.isEmpty()) {
                executeStep(player, sequence, index + 1, 0L);
                return;
            }

            long nextDelay = 2L; // default 2 tick gap between actions

            if (trimmed.startsWith("START_CUTSCENE")) {
                String id = trimmed.substring("START_CUTSCENE".length()).trim();
                CinematicController cine = plugin.getModule(CinematicController.class);
                plugin.getLogger().info("[QuestEngine] START_CUTSCENE id=" + id + " cine=" + (cine != null));
                if (cine != null) {
                    cine.playCinematic(player, id);
                    long cutsceneTicks = cine.getCutsceneDuration(id);
                    plugin.getLogger().info("[QuestEngine] Cutscene duration=" + cutsceneTicks + " ticks, waiting before next step");
                    nextDelay = cutsceneTicks + 5L;
                }

            } else if (trimmed.startsWith("WAIT")) {
                try {
                    nextDelay = Long.parseLong(trimmed.split(" ")[1].trim());
                } catch (Exception ignored) { nextDelay = 20L; }


            } else if (trimmed.startsWith("PASTE_INSTANCE")) {
                String id = trimmed.substring("PASTE_INSTANCE".length()).trim();
                SchematicInstanceManager inst = plugin.getModule(SchematicInstanceManager.class);
                if (inst != null) {
                    final int nextIndex = index + 1;
                    inst.pasteTemplate(id, player, () -> {
                        executeStep(player, sequence, nextIndex, 5L);
                    });
                    return;
                }

            } else if (trimmed.startsWith("CLEANUP_INSTANCE")) {
                SchematicInstanceManager inst = plugin.getModule(SchematicInstanceManager.class);
                if (inst != null) inst.cleanupInstance(player.getUniqueId());

            } else if (trimmed.startsWith("SPAWN_MOB")) {
                String[] parts = trimmed.split(" ");
                if (parts.length >= 3) {
                    try {
                        org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(parts[1].toUpperCase());
                        int count = Integer.parseInt(parts[2]);
                        org.bukkit.Location loc = player.getLocation().clone().add(0, 0.5, 0);
                        for (int i = 0; i < count; i++) {
                            org.bukkit.entity.Entity entity = player.getWorld().spawnEntity(loc, type);
                            entity.setMetadata("quest_mob", new org.bukkit.metadata.FixedMetadataValue(plugin, "true"));
                            entity.setMetadata("quest_owner", new org.bukkit.metadata.FixedMetadataValue(plugin, player.getUniqueId().toString()));
                            entity.setCustomName(ChatColor.RED + "Quest Mob");
                            entity.setCustomNameVisible(true);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to spawn quest mob: " + e.getMessage());
                    }
                }

            } else if (trimmed.startsWith("GIVE_REWARD")) {
                // Format: GIVE_REWARD <item_id> [amount]
                // Supports custom item IDs from config (items, offhand, staves, grimoires, scrolls)
                // Falls back to vanilla Material if not a custom item
                String[] parts = trimmed.split(" ");
                if (parts.length >= 2) {
                    String itemId = parts[1].trim().toLowerCase();
                    int amount = 1;
                    if (parts.length >= 3) {
                        try {
                            amount = Integer.parseInt(parts[2].trim());
                        } catch (NumberFormatException ignored) {}
                    }

                    try {
                        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
                        org.bukkit.inventory.ItemStack item = null;

                        // Try custom item first (checks offhand, staff, grimoire, scroll, then items config)
                        if (itemMod != null) {
                            item = itemMod.generateAnyItem(itemId);
                        }

                        // Fallback: try vanilla Material
                        if (item == null) {
                            try {
                                org.bukkit.Material mat = org.bukkit.Material.valueOf(itemId.toUpperCase());
                                item = new org.bukkit.inventory.ItemStack(mat);
                            } catch (IllegalArgumentException ignored) {}
                        }

                        if (item != null) {
                            item.setAmount(amount);
                            player.getInventory().addItem(item);
                            String displayName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                                    ? item.getItemMeta().getDisplayName()
                                    : itemId.replace("_", " ");
                            player.sendMessage(ChatColor.GREEN + "You received: " + displayName
                                    + (amount > 1 ? ChatColor.GRAY + " x" + amount : ""));
                        } else {
                            plugin.getLogger().warning("[QuestEngine] GIVE_REWARD: Unknown item '" + itemId + "'");
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to give reward: " + e.getMessage());
                    }
                }
            }

            // Schedule next step
            executeStep(player, sequence, index + 1, nextDelay);

        }, delayTicks);
    }

    /**
     * Completes a quest objective, giving tags and potentially triggering follow-ups.
     * Can be called asynchronously.
     *
     * @param player      The player completing the objective
     * @param objectiveId The tag or objective identifier
     */
    public void completeObjective(Player player, String objectiveId) {
        if (player == null || objectiveId == null || objectiveId.isEmpty()) return;

        // Offload tag updates to an async task to ensure 20 TPS is maintained
        new BukkitRunnable() {
            @Override
            public void run() {
                TagManager tagMgr = plugin.getModule(TagManager.class);
                if (tagMgr != null) {
                    tagMgr.addTag(player, objectiveId);
                }
                
                // Clear any repeat dialogues since state has advanced
                Map<String, String> pDialogues = repeatDialogues.get(player.getUniqueId());
                if (pDialogues != null) {
                    pDialogues.clear();
                }

                // Fire sync events or messages if needed
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player.isOnline()) {
                            player.sendMessage(ChatColor.GREEN + "Objective Complete: " + ChatColor.WHITE + objectiveId.replace("_", " "));
                            player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private static class QuestScript {
        String id;
        List<String> requiredTags = new ArrayList<>();
        String repeatDialogue;
        List<String> sequence = new ArrayList<>();
    }
}
