package net.elpixedge.core.instance;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.tag.TagManager;
import net.elpixedge.core.utils.Keys;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SchematicInstanceManager implements Module, Listener {

    private final ElpixEdge plugin;
    private final ConcurrentHashMap<UUID, InstanceSession> activeSessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    public SchematicInstanceManager(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        File schemDir = new File(plugin.getDataFolder(), "schematics");
        if (!schemDir.exists()) schemDir.mkdirs();
        loadInstances();
        plugin.getLogger().info("SchematicInstanceManager enabled.");
    }

    @Override
    public void onDisable() {
        // Force cleanup all active instances on shutdown
        for (UUID uuid : new HashSet<>(activeSessions.keySet())) {
            cleanupInstance(uuid);
        }
        activeSessions.clear();
    }

    public void pasteTemplate(String instanceId, Player player) {
        pasteTemplate(instanceId, player, null);
    }

    public void pasteTemplate(String instanceId, Player player, Runnable onComplete) {
        if (player == null || instanceId == null) return;
        
        InstanceDef def = instances.get(instanceId);
        String schematicName = def != null ? def.schematicName : instanceId;
        int[] spawnOffset = def != null ? def.spawnOffset : null;

        if (activeSessions.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already in an instance!");
            return;
        }

        File file = new File(plugin.getDataFolder(), "schematics/" + schematicName);
        if (!file.exists()) {
            file = new File(plugin.getDataFolder(), "schematics/" + schematicName + ".schem");
            if (!file.exists()) {
                file = new File(plugin.getDataFolder(), "schematics/" + schematicName + ".schm");
                if (!file.exists()) {
                    // Fallback to FastAsyncWorldEdit schematics folder
                    file = new File(plugin.getServer().getWorldContainer(), "plugins/FastAsyncWorldEdit/schematics/" + schematicName);
                    if (!file.exists()) {
                        file = new File(plugin.getServer().getWorldContainer(), "plugins/FastAsyncWorldEdit/schematics/" + schematicName + ".schem");
                        if (!file.exists()) {
                            file = new File(plugin.getServer().getWorldContainer(), "plugins/FastAsyncWorldEdit/schematics/" + schematicName + ".schm");
                            if (!file.exists()) {
                                player.sendMessage(ChatColor.RED + "Schematic not found: " + schematicName);
                                return;
                            }
                        }
                    }
                }
            }
        }

        final File targetFile = file;
        final int sessionId = sessionCounter.incrementAndGet();
        final int pasteX = 10000 + (sessionId * 500);
        final int pasteY = 100;
        final int pasteZ = 10000;
        final World world = player.getWorld();

        InstanceSession session = new InstanceSession();
        session.returnLocation = player.getLocation().clone();
        session.worldName = world.getName();
        activeSessions.put(player.getUniqueId(), session);

        player.sendMessage(ChatColor.YELLOW + "Preparing instance...");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    ClipboardFormat format = ClipboardFormats.findByFile(targetFile);
                    if (format == null) {
                        player.sendMessage(ChatColor.RED + "Invalid schematic format.");
                        activeSessions.remove(player.getUniqueId());
                        return;
                    }

                    Clipboard clipboard;
                    try (var reader = format.getReader(new FileInputStream(targetFile))) {
                        clipboard = reader.read();
                    }

                    BlockVector3 to = BlockVector3.at(pasteX, pasteY, pasteZ);

                    try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                            .world(BukkitAdapter.adapt(world))
                            .fastMode(true)
                            .build()) {

                        Operation operation = new ClipboardHolder(clipboard)
                                .createPaste(editSession)
                                .to(to)
                                .ignoreAirBlocks(false)
                                .build();

                        Operations.complete(operation);

                        // Calculate bounds for cleanup
                        BlockVector3 origin = clipboard.getOrigin();
                        BlockVector3 offset = to.subtract(origin);
                        BlockVector3 min = clipboard.getMinimumPoint().add(offset);
                        BlockVector3 max = clipboard.getMaximumPoint().add(offset);
                        session.region = new CuboidRegion(min, max);

                        // Calculate spawn point
                        int cx, cy, cz;
                        if (spawnOffset != null && spawnOffset.length >= 3) {
                            // Use explicit offset from schematic origin (paste point)
                            cx = pasteX + spawnOffset[0];
                            cy = pasteY + spawnOffset[1];
                            cz = pasteZ + spawnOffset[2];
                        } else {
                            // Default: center of schematic footprint, 1 above floor
                            BlockVector3 dims = clipboard.getDimensions();
                            cx = pasteX + (dims.x() / 2);
                            cy = pasteY + 1;
                            cz = pasteZ + (dims.z() / 2);
                        }
                        session.spawnX = cx;
                        session.spawnY = cy;
                        session.spawnZ = cz;
                    }

                    // Teleport on main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!player.isOnline()) {
                                cleanupInstance(player.getUniqueId());
                                return;
                            }
                            Location spawnLoc = new Location(world, session.spawnX + 0.5, session.spawnY, session.spawnZ + 0.5);
                            player.teleport(spawnLoc);
                            player.sendMessage(ChatColor.GREEN + "Instance loaded!");
                            
                            // Spawn mobs from instance config
                            if (def != null && !def.mobs.isEmpty()) {
                                spawnInstanceMobs(def, session, player, world, pasteX, pasteY, pasteZ);
                            }

                            if (onComplete != null) onComplete.run();
                        }
                    }.runTask(plugin);

                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to paste schematic: " + e.getMessage());
                    e.printStackTrace();
                    activeSessions.remove(player.getUniqueId());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Spawns mobs for an instance. Supports both custom mob IDs (from config mobs section)
     * and vanilla EntityType names.
     *
     * Custom mobs get: PDC tags, BetterModel, equipment, abilities, name display.
     * Vanilla mobs get: quest_mob/quest_owner metadata and custom name.
     */
    private void spawnInstanceMobs(InstanceDef def, InstanceSession session, Player player,
                                   World world, int pasteX, int pasteY, int pasteZ) {
        net.elpixedge.core.combat.CombatModule combat = plugin.getModule(net.elpixedge.core.combat.CombatModule.class);
        net.elpixedge.core.combat.CustomMobAbilityModule abilityMod = plugin.getModule(net.elpixedge.core.combat.CustomMobAbilityModule.class);
        net.elpixedge.core.player.PlayerModule playerMod = plugin.getModule(net.elpixedge.core.player.PlayerModule.class);

        for (MobDef mDef : def.mobs) {
            int mx = pasteX + (mDef.offset != null && mDef.offset.length >= 3 ? mDef.offset[0] : (session.spawnX - pasteX));
            int my = pasteY + (mDef.offset != null && mDef.offset.length >= 3 ? mDef.offset[1] : 1);
            int mz = pasteZ + (mDef.offset != null && mDef.offset.length >= 3 ? mDef.offset[2] : (session.spawnZ - pasteZ));
            Location mobLoc = new Location(world, mx + 0.5, my, mz + 0.5);

            // Check if this is a custom mob ID (defined in config mobs section)
            boolean isCustomMob = plugin.getConfig().contains("mobs." + mDef.type);

            if (isCustomMob) {
                spawnCustomMob(mDef, mobLoc, player, session, combat, abilityMod, playerMod);
            } else {
                spawnVanillaMob(mDef, mobLoc, player, session);
            }
        }
    }

    /**
     * Spawns a custom mob (defined in config mobs section) with full RPG integration:
     * PDC tags, BetterModel, equipment, abilities, HP scaling, name display.
     */
    private void spawnCustomMob(MobDef mDef, Location mobLoc, Player player, InstanceSession session,
                                net.elpixedge.core.combat.CombatModule combat,
                                net.elpixedge.core.combat.CustomMobAbilityModule abilityMod,
                                net.elpixedge.core.player.PlayerModule playerMod) {
        String mobId = mDef.type.toLowerCase();
        String baseEntStr = plugin.getConfig().getString("mobs." + mobId + ".base_entity", mobId);

        EntityType eType;
        try {
            eType = EntityType.valueOf(baseEntStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            eType = EntityType.ZOMBIE;
        }

        int level = mDef.level > 0 ? mDef.level : 10;
        double tierHpMult = plugin.getConfig().getDouble("mobs." + mobId + ".tier_hp_mult", 10.0);
        double tierDmgMult = plugin.getConfig().getDouble("mobs." + mobId + ".tier_dmg_mult", 1.0);
        double maxHp = Math.min(1024.0, 15.0 + (level * tierHpMult));

        for (int i = 0; i < mDef.amount; i++) {
            try {
                LivingEntity mob = (LivingEntity) mobLoc.getWorld().spawnEntity(mobLoc, eType);

                // Apply PDC tags for custom mob system
                mob.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, mobId);
                mob.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, level);
                mob.getPersistentDataContainer().set(Keys.tierDmgMult, PersistentDataType.DOUBLE, tierDmgMult);

                // Set HP
                Attribute maxHpAttr = playerMod != null ? playerMod.getHealthAttribute() : Attribute.MAX_HEALTH;
                if (mob.getAttribute(maxHpAttr) != null) {
                    mob.getAttribute(maxHpAttr).setBaseValue(maxHp);
                }
                mob.setHealth(maxHp);
                mob.setRemoveWhenFarAway(false);

                // Quest mob metadata for tracking
                mob.setMetadata("quest_mob", new org.bukkit.metadata.FixedMetadataValue(plugin, "true"));
                mob.setMetadata("quest_owner", new org.bukkit.metadata.FixedMetadataValue(plugin, player.getUniqueId().toString()));

                // Apply name display, equipment, BetterModel, and abilities
                if (combat != null) {
                    combat.updateMobName(mob);
                    combat.equipMob(mob, mobId);
                    combat.applyBetterModel(mob, mobId);
                }

                if (abilityMod != null) {
                    abilityMod.registerMob(mob);
                }

                session.spawnedMobs.add(mob);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to spawn custom instance mob '" + mobId + "': " + ex.getMessage());
            }
        }
    }

    /**
     * Spawns a vanilla mob (EntityType name) with basic quest metadata and optional custom name.
     */
    private void spawnVanillaMob(MobDef mDef, Location mobLoc, Player player, InstanceSession session) {
        try {
            EntityType type = EntityType.valueOf(mDef.type.toUpperCase());
            for (int i = 0; i < mDef.amount; i++) {
                Entity entity = mobLoc.getWorld().spawnEntity(mobLoc, type);
                entity.setMetadata("quest_mob", new org.bukkit.metadata.FixedMetadataValue(plugin, "true"));
                entity.setMetadata("quest_owner", new org.bukkit.metadata.FixedMetadataValue(plugin, player.getUniqueId().toString()));
                if (mDef.customName != null && !mDef.customName.isEmpty()) {
                    entity.setCustomName(ChatColor.translateAlternateColorCodes('&', mDef.customName));
                    entity.setCustomNameVisible(true);
                } else {
                    entity.setCustomName(ChatColor.RED + "Quest Mob");
                    entity.setCustomNameVisible(true);
                }
                session.spawnedMobs.add(entity);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to spawn vanilla instance mob '" + mDef.type + "': " + ex.getMessage());
        }
    }

    /**
     * Cleans up the instance area (set to AIR) using FAWE and returns player.
     */
    public void cleanupInstance(UUID playerUuid) {
        InstanceSession session = activeSessions.remove(playerUuid);
        if (session == null || session.region == null || session.worldName == null) return;

        World world = Bukkit.getWorld(session.worldName);
        if (world == null) return;

        // Teleport player back if online
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && player.isOnline() && session.returnLocation != null) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.teleport(session.returnLocation);
                    player.sendMessage(ChatColor.YELLOW + "Instance cleaned up.");
                }
            }.runTask(plugin);
        }

        // Remove spawned mobs
        for (Entity ent : session.spawnedMobs) {
            if (ent != null && !ent.isDead() && ent.isValid()) {
                ent.remove();
            }
        }
        session.spawnedMobs.clear();

        // Run FAWE clear asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                        .world(BukkitAdapter.adapt(world))
                        .fastMode(true)
                        .build()) {
                    
                    editSession.setBlocks((com.sk89q.worldedit.regions.Region) session.region, com.sk89q.worldedit.world.block.BlockTypes.AIR);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to clear instance area: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (activeSessions.containsKey(event.getPlayer().getUniqueId())) {
            // Schedule cleanup to run even after player quit
            cleanupInstance(event.getPlayer().getUniqueId());
        }
    }

    public boolean isInInstance(Player player) {
        return player != null && activeSessions.containsKey(player.getUniqueId());
    }

    private static class InstanceSession {
        Location returnLocation;
        String worldName;
        CuboidRegion region;
        int spawnX, spawnY, spawnZ;
        List<Entity> spawnedMobs = new ArrayList<>();
    }

    private final Map<String, InstanceDef> instances = new HashMap<>();

    public void loadInstances() {
        instances.clear();
        File f = new File(plugin.getDataFolder(), "instances.yml");
        if (!f.exists()) {
            plugin.saveResource("instances.yml", false);
        }
        if (!f.exists()) return;

        org.bukkit.configuration.file.YamlConfiguration cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection("instances");
        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection iSec = sec.getConfigurationSection(key);
            if (iSec == null) continue;

            InstanceDef def = new InstanceDef();
            def.id = key;
            def.schematicName = iSec.getString("schematic", key);
            
            String spawnStr = iSec.getString("spawn_offset");
            if (spawnStr != null) {
                String[] parts = spawnStr.split(",");
                if (parts.length >= 3) {
                    try {
                        def.spawnOffset = new int[]{
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim())
                        };
                    } catch (Exception ignored) {}
                }
            }

            List<?> mobList = iSec.getList("mobs");
            if (mobList != null) {
                for (Object obj : mobList) {
                    if (obj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) obj;
                        MobDef mDef = new MobDef();
                        mDef.type = map.containsKey("type") ? map.get("type").toString() : "ZOMBIE";
                        mDef.amount = map.containsKey("amount") ? ((Number) map.get("amount")).intValue() : 1;
                        mDef.level = map.containsKey("level") ? ((Number) map.get("level")).intValue() : 0;
                        if (map.containsKey("name")) mDef.customName = map.get("name").toString();
                        if (map.containsKey("offset")) {
                            String[] parts = map.get("offset").toString().split(",");
                            if (parts.length >= 3) {
                                try {
                                    mDef.offset = new int[]{
                                        Integer.parseInt(parts[0].trim()),
                                        Integer.parseInt(parts[1].trim()),
                                        Integer.parseInt(parts[2].trim())
                                    };
                                } catch (Exception ignored) {}
                            }
                        }
                        def.mobs.add(mDef);
                    }
                }
            }
            instances.put(key, def);
        }
        plugin.getLogger().info("Loaded " + instances.size() + " instance configurations.");
    }

    private static class InstanceDef {
        String id;
        String schematicName;
        int[] spawnOffset;
        List<MobDef> mobs = new ArrayList<>();
    }

    private static class MobDef {
        String type;       // Custom mob ID (e.g. "ruined_skeleton") OR vanilla EntityType (e.g. "ZOMBIE")
        int amount;
        int level;         // Level for custom mobs (0 = use default 10)
        String customName; // Optional custom display name (for vanilla mobs)
        int[] offset;
    }
}
