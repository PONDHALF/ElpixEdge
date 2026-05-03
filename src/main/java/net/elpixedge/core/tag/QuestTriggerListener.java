package net.elpixedge.core.tag;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.Event;

import java.lang.reflect.Method;
import java.util.List;

/**
 * QuestTriggerListener — Listens for player interactions and evaluates
 * quest trigger conditions using the Tag system.
 *
 * Supports:
 *   - NPC click triggers (PlayerInteractEntityEvent)
 *   - Region enter triggers (PlayerMoveEvent with block-change optimization)
 *
 * For each matching trigger:
 *   1. Check required_tags via TagManager.hasTag()
 *   2. Execute actions: add_tags, remove_tags, teleport, message
 *
 * Performance:
 *   - PlayerMoveEvent only fires logic when the player changes block position
 *   - All tag checks read from ConcurrentHashMap cache (zero I/O on main thread)
 */
public class QuestTriggerListener implements Module, Listener {

    private final ElpixEdge plugin;
    private QuestConfigManager configManager;

    public QuestTriggerListener(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        configManager = new QuestConfigManager(plugin);
        configManager.loadTriggers();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // Register FancyNPCs listener via Reflection
        try {
            Class<? extends Event> interactEventClass = (Class<? extends Event>) Class.forName("de.oliver.fancynpcs.api.events.NpcInteractEvent");
            plugin.getServer().getPluginManager().registerEvent(interactEventClass, this, EventPriority.NORMAL, (listener, event) -> {
                try {
                    Method getNpc = event.getClass().getMethod("getNpc");
                    Object npc = getNpc.invoke(event);
                    Method getData = npc.getClass().getMethod("getData");
                    Object data = getData.invoke(npc);
                    Method getName = data.getClass().getMethod("getName");
                    String npcName = (String) getName.invoke(data);
                    
                    Method getPlayer = event.getClass().getMethod("getPlayer");
                    Player p = (Player) getPlayer.invoke(event);
                    
                    if (p.isOp()) {
                        p.sendMessage(org.bukkit.ChatColor.GRAY + "[Debug] Clicked FancyNPC: " + npcName);
                    }
                    
                    handleNpcClick(p, npcName);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in FancyNPC interact event reflection:");
                    e.printStackTrace();
                }
            }, plugin);
            plugin.getLogger().info("QuestTriggerListener — Hooked into FancyNPCs API successfully.");
        } catch (Exception e) {
            plugin.getLogger().info("QuestTriggerListener — FancyNPCs API not found. Skipping NPC interact hook.");
        }
        
        plugin.getLogger().info("QuestTriggerListener enabled — listening for quest triggers.");
    }

    @Override
    public void onDisable() {
        // Nothing to clean up
    }

    /**
     * Returns the QuestConfigManager for external access (e.g. reload commands).
     */
    public QuestConfigManager getConfigManager() {
        return configManager;
    }

    // ==========================================
    // NPC Click Trigger
    // ==========================================

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        // Get the entity's custom name (color-stripped for matching)
        String customName = clicked.getCustomName();
        if (customName == null || customName.isEmpty()) return;
        
        handleNpcClick(player, customName);
    }
    
    private void handleNpcClick(Player player, String npcName) {
        String strippedName = ChatColor.stripColor(npcName).trim();

        // Look up triggers for this NPC name
        List<QuestConfigManager.QuestTrigger> triggers = configManager.getTriggersForNpc(strippedName);
        if (triggers.isEmpty()) return;

        TagManager tagManager = plugin.getModule(TagManager.class);
        if (tagManager == null) return;

        for (QuestConfigManager.QuestTrigger trigger : triggers) {
            if (evaluateAndExecute(player, trigger, tagManager)) {
                // Only execute the first matching trigger per interaction
                break;
            }
        }
    }

    // ==========================================
    // Region Enter Trigger (Block-change optimization)
    // ==========================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Block-change optimization: only process when the player moves to a different block
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        // Get all enter_region triggers
        List<QuestConfigManager.QuestTrigger> regionTriggers = configManager.getTriggersByType("enter_region");
        if (regionTriggers.isEmpty()) return;

        TagManager tagManager = plugin.getModule(TagManager.class);
        if (tagManager == null) return;

        Player player = event.getPlayer();

        for (QuestConfigManager.QuestTrigger trigger : regionTriggers) {
            // Region triggers use the teleport location as the center point
            // (In a future expansion, this would be a region definition)
            if (trigger.teleportLocation != null) {
                // Basic proximity check — within 3 blocks of the trigger point
                Location triggerLoc = trigger.teleportLocation;
                if (player.getWorld().getName().equals(triggerLoc.getWorld().getName())) {
                    double distSq = player.getLocation().distanceSquared(triggerLoc);
                    if (distSq <= 9.0) { // 3 block radius
                        evaluateAndExecute(player, trigger, tagManager);
                    }
                }
            }
        }
    }
    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity.hasMetadata("quest_mob") && entity.hasMetadata("quest_owner")) {
            String ownerUuidStr = entity.getMetadata("quest_owner").get(0).asString();
            Player player = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(ownerUuidStr));
            
            if (player != null && player.isOnline()) {
                // Check if there are any other quest mobs for this player
                boolean mobsRemaining = false;
                for (Entity e : player.getWorld().getEntities()) {
                    if (e != entity && !e.isDead() && e.isValid()) {
                        if (e.hasMetadata("quest_owner") && e.getMetadata("quest_owner").get(0).asString().equals(ownerUuidStr)) {
                            mobsRemaining = true;
                            break;
                        }
                    }
                }
                
                if (!mobsRemaining) {
                    // Trigger the mob_cleared action sequence for this player
                    List<QuestConfigManager.QuestTrigger> triggers = configManager.getTriggersByType("mob_cleared");
                    TagManager tagManager = plugin.getModule(TagManager.class);
                    if (tagManager != null) {
                        for (QuestConfigManager.QuestTrigger trigger : triggers) {
                            if (evaluateAndExecute(player, trigger, tagManager)) {
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
    // ==========================================
    // Trigger Evaluation & Execution
    // ==========================================

    /**
     * Evaluates a trigger's required tags and executes its actions if all conditions are met.
     *
     * @param player     the player to evaluate
     * @param trigger    the quest trigger definition
     * @param tagManager the tag manager instance
     * @return true if the trigger was executed, false if conditions were not met
     */
    private boolean evaluateAndExecute(Player player, QuestConfigManager.QuestTrigger trigger, TagManager tagManager) {
        // Check all required tags (supports "!tag" = player must NOT have this tag)
        for (String requiredTag : trigger.requiredTags) {
            if (requiredTag.startsWith("!")) {
                // Negated: player must NOT have this tag
                String actualTag = requiredTag.substring(1);
                if (tagManager.hasTag(player, actualTag)) {
                    return false; // Player has a tag they shouldn't
                }
            } else {
                // Positive: player must have this tag
                if (!tagManager.hasTag(player, requiredTag)) {
                    return false; // Player is missing a required tag
                }
            }
        }

        // All conditions met — execute actions

        // 1. Add tags
        for (String tag : trigger.addTags) {
            tagManager.addTag(player, tag);
        }

        // 2. Remove tags
        for (String tag : trigger.removeTags) {
            tagManager.removeTag(player, tag);
        }

        // 3. Send message
        if (trigger.message != null && !trigger.message.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', trigger.message));
        }

        // 4. Teleport
        if (trigger.teleportLocation != null && trigger.type.equals("npc_click")) {
            // Re-parse in case world wasn't loaded at config time
            Location dest = trigger.teleportLocation;
            if (dest.getWorld() == null) {
                dest = reParseTeleport(trigger.teleportStr);
            }
            if (dest != null && dest.getWorld() != null) {
                player.teleport(dest);
                player.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
            }
        }

        // 5. Play feedback sound
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

        // 6. Execute Quest Engine Sequence
        net.elpixedge.core.quest.QuestEngine engine = plugin.getModule(net.elpixedge.core.quest.QuestEngine.class);
        if (engine != null) {
            engine.executeQuest(player, trigger.id);
        }

        plugin.getLogger().info("[Quest] Player " + player.getName() + " triggered: " + trigger.id);
        return true;
    }

    /**
     * Re-parses a teleport string. Used when the world wasn't loaded at config read time.
     */
    private Location reParseTeleport(String teleportStr) {
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

            org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) return null;

            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
