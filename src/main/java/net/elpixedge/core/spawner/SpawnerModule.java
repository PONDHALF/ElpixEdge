package net.elpixedge.core.spawner;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.combat.CombatModule;
import net.elpixedge.core.utils.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * SpawnerModule — Location-based custom mob spawning system.
 *
 * Reads the "spawners" config section and manages:
 *   - Proximity-based activation (only spawns when a player is nearby)
 *   - Weighted random mob selection from a pool
 *   - Level range randomization
 *   - Wander radius enforcement (mobs walk back if they stray)
 *   - Player hunting within hunt_range for hunt_duration seconds
 *   - Despawn timeout when mob is idle outside wander_radius
 *   - Respawn timer after death or despawn
 *   - BetterModel integration via reflection
 *   - Tier HP/DMG multipliers from mob config
 *
 * All heavy checks run on a 1-second sync tick to maintain 20 TPS.
 * Entity lookup uses Bukkit.getEntity(UUID) for O(1) access.
 */
public class SpawnerModule implements Module, Listener {

    private final ElpixEdge plugin;
    private BukkitRunnable tickTask;
    private final List<SpawnPoint> spawnPoints = new ArrayList<>();
    private long loopTick = 0; // increments every 1 second

    public SpawnerModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        loadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTickTask();
        plugin.getLogger().info("SpawnerModule enabled — " + spawnPoints.size() + " spawn points loaded.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        // Remove all tracked mobs on shutdown
        for (SpawnPoint sp : spawnPoints) {
            if (sp.aliveUUID != null) {
                Entity ent = Bukkit.getEntity(sp.aliveUUID);
                if (ent != null && !ent.isDead()) ent.remove();
            }
        }
        spawnPoints.clear();
    }

    // ==========================================
    // Config Loading
    // ==========================================
    private void loadConfig() {
        spawnPoints.clear();
        ConfigurationSection spawnersSec = plugin.getConfig().getConfigurationSection("spawners");
        if (spawnersSec == null) return;

        for (String spawnerId : spawnersSec.getKeys(false)) {
            ConfigurationSection sec = spawnersSec.getConfigurationSection(spawnerId);
            if (sec == null) continue;

            SpawnerDef def = new SpawnerDef();
            def.id = spawnerId;

            // Parse mob weights
            ConfigurationSection mobsSec = sec.getConfigurationSection("mobs");
            if (mobsSec == null) continue;
            def.mobWeights = new LinkedHashMap<>();
            int totalW = 0;
            for (String mobKey : mobsSec.getKeys(false)) {
                int w = mobsSec.getInt(mobKey, 1);
                def.mobWeights.put(mobKey, w);
                totalW += w;
            }
            def.totalWeight = totalW;
            if (def.totalWeight <= 0) continue;

            // Parse level range "min-max"
            String lvlRange = sec.getString("level_range", "1-1");
            String[] parts = lvlRange.split("-");
            def.minLevel = Integer.parseInt(parts[0].trim());
            def.maxLevel = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : def.minLevel;

            // Parse settings
            def.maxAlivePerLoc = sec.getInt("max_alive_per_location", 1);
            def.activationRange = sec.getInt("activation_range", 40);
            def.wanderRadius = sec.getInt("wander_radius", 5);
            def.huntRange = sec.getInt("hunt_range", 15);
            def.huntDurationSec = sec.getInt("hunt_duration", 30);
            def.despawnTimeoutSec = sec.getInt("despawn_timeout", 300);
            def.respawnTimeSec = sec.getInt("respawn_time", 10);

            // Parse locations and create a SpawnPoint per location
            List<String> locStrings = sec.getStringList("locations");
            for (String locStr : locStrings) {
                String[] lp = locStr.split(",");
                if (lp.length < 4) continue;
                SpawnPoint sp = new SpawnPoint();
                sp.def = def;
                sp.worldName = lp[0].trim();
                sp.x = Double.parseDouble(lp[1].trim());
                sp.y = Double.parseDouble(lp[2].trim());
                sp.z = Double.parseDouble(lp[3].trim());
                sp.respawnAtTick = 0; // can spawn immediately
                spawnPoints.add(sp);
            }
        }
    }

    // ==========================================
    // Main Tick Loop (runs every 20 server ticks = 1 second)
    // ==========================================
    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                loopTick++;
                if (spawnPoints.isEmpty()) return;

                // Cache player locations once per tick for efficiency
                List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
                if (players.isEmpty()) return;

                for (SpawnPoint sp : spawnPoints) {
                    tickSpawnPoint(sp, players);
                }
            }
        };
        tickTask.runTaskTimer(plugin, 40L, 20L); // start after 2s, repeat every 1s
    }

    private void tickSpawnPoint(SpawnPoint sp, List<Player> players) {
        Location spawnLoc = sp.getLocation();
        if (spawnLoc == null) return; // world not loaded

        // --- Mob is alive ---
        if (sp.aliveUUID != null) {
            Entity ent = Bukkit.getEntity(sp.aliveUUID);
            if (ent == null || ent.isDead() || !ent.isValid()) {
                // Entity gone — could be death (handled by event) or chunk unload
                // If death event already cleared it, this won't run.
                // For chunk unload: check if spawn loc chunk is loaded
                if (spawnLoc.isChunkLoaded()) {
                    // Chunk loaded but entity gone → confirmed dead/removed
                    onMobRemoved(sp);
                }
                // If chunk not loaded, skip — mob may still exist
                return;
            }

            if (!(ent instanceof LivingEntity)) return;
            handleMobAI(sp, (LivingEntity) ent, spawnLoc, players);
            return;
        }

        // --- No mob alive — check respawn ---
        if (sp.respawnAtTick > 0 && loopTick < sp.respawnAtTick) return;

        // Check player proximity for activation
        double actRangeSq = sp.def.activationRange * sp.def.activationRange;
        boolean playerNearby = false;
        for (Player p : players) {
            if (!p.getWorld().getName().equals(sp.worldName)) continue;
            if (p.getLocation().distanceSquared(spawnLoc) <= actRangeSq) {
                playerNearby = true;
                break;
            }
        }

        if (playerNearby) {
            spawnMob(sp, spawnLoc);
        }
    }

    // ==========================================
    // Mob AI: Wander / Hunt / Despawn
    // ==========================================
    private void handleMobAI(SpawnPoint sp, LivingEntity mob, Location spawnLoc, List<Player> players) {
        // Find nearest player within hunt range
        double huntRangeSq = sp.def.huntRange * sp.def.huntRange;
        Player nearestInRange = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (Player p : players) {
            if (!p.getWorld().equals(mob.getWorld())) continue;
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double dSq = p.getLocation().distanceSquared(mob.getLocation());
            if (dSq <= huntRangeSq && dSq < nearestDistSq) {
                nearestDistSq = dSq;
                nearestInRange = p;
            }
        }

        // Hunt logic
        if (nearestInRange != null) {
            sp.isHunting = true;
            sp.huntExpireTick = loopTick + sp.def.huntDurationSec;
            sp.despawnAtTick = -1; // cancel despawn while hunting
            if (mob instanceof Mob) {
                Mob m = (Mob) mob;
                if (m.getTarget() == null || !m.getTarget().equals(nearestInRange)) {
                    m.setTarget(nearestInRange);
                }
            }
        } else if (sp.isHunting) {
            // Player left hunt range — continue until timer expires
            if (loopTick >= sp.huntExpireTick) {
                sp.isHunting = false;
                if (mob instanceof Mob) ((Mob) mob).setTarget(null);
            }
        }

        // Wander radius check
        double distToSpawnSq = mob.getLocation().distanceSquared(spawnLoc);
        double wanderRadiusSq = sp.def.wanderRadius * sp.def.wanderRadius;

        if (!sp.isHunting) {
            if (distToSpawnSq > wanderRadiusSq) {
                // Outside wander radius and not hunting
                if (sp.despawnAtTick < 0) {
                    sp.despawnAtTick = loopTick + sp.def.despawnTimeoutSec;
                } else if (loopTick >= sp.despawnAtTick) {
                    // Despawn
                    mob.remove();
                    onMobRemoved(sp);
                    return;
                }
                // Walk back to spawn point
                if (mob instanceof Mob && loopTick - sp.lastWanderTick >= 3) {
                    ((Mob) mob).getPathfinder().moveTo(spawnLoc, 1.0);
                    sp.lastWanderTick = loopTick;
                }
            } else {
                sp.despawnAtTick = -1; // back in radius — cancel despawn
                // Random wander every ~5 seconds
                if (mob instanceof Mob && loopTick - sp.lastWanderTick >= 5) {
                    Random rand = new Random();
                    double ox = (rand.nextDouble() - 0.5) * 2.0 * sp.def.wanderRadius;
                    double oz = (rand.nextDouble() - 0.5) * 2.0 * sp.def.wanderRadius;
                    Location wanderTarget = spawnLoc.clone().add(ox, 0, oz);
                    wanderTarget.setY(mob.getWorld().getHighestBlockYAt(wanderTarget));
                    ((Mob) mob).getPathfinder().moveTo(wanderTarget, 0.6);
                    sp.lastWanderTick = loopTick;
                }
            }
        } else {
            sp.despawnAtTick = -1; // hunting — no despawn
        }
    }

    // ==========================================
    // Mob Spawning
    // ==========================================
    private void spawnMob(SpawnPoint sp, Location spawnLoc) {
        String selectedMob = selectWeightedMob(sp.def);
        if (selectedMob == null) return;

        int level = sp.def.minLevel;
        if (sp.def.maxLevel > sp.def.minLevel) {
            level += new Random().nextInt(sp.def.maxLevel - sp.def.minLevel + 1);
        }

        // Resolve entity type
        String baseEntity = plugin.getConfig().getString("mobs." + selectedMob + ".base_entity", selectedMob);
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(baseEntity.toUpperCase());
        } catch (IllegalArgumentException e) {
            entityType = EntityType.ZOMBIE;
        }
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) return;

        // Read tier multipliers
        double tierHpMult = plugin.getConfig().getDouble("mobs." + selectedMob + ".tier_hp_mult", 10.0);
        double tierDmgMult = plugin.getConfig().getDouble("mobs." + selectedMob + ".tier_dmg_mult", 1.0);
        double maxHp = 15.0 + (level * tierHpMult);

        final String fMobId = selectedMob;
        final int fLevel = level;
        final double fMaxHp = Math.min(1024.0, maxHp);
        final double fTierDmg = tierDmgMult;

        LivingEntity mob = (LivingEntity) spawnLoc.getWorld().spawn(spawnLoc, entityClass, entity -> {
            entity.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, fLevel);
            entity.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, fMobId);
            entity.getPersistentDataContainer().set(Keys.tierDmgMult, PersistentDataType.DOUBLE, fTierDmg);
            if (entity instanceof LivingEntity le) {
                if (le.getAttribute(Attribute.MAX_HEALTH) != null) {
                    le.getAttribute(Attribute.MAX_HEALTH).setBaseValue(fMaxHp);
                }
                le.setHealth(fMaxHp);
                le.setRemoveWhenFarAway(false); // prevent vanilla despawn
            }
        });

        // Update name display
        CombatModule combat = plugin.getModule(CombatModule.class);
        if (combat != null) {
            combat.updateMobName(mob);
            combat.equipMob(mob, fMobId);
        }

        // BetterModel integration
        applyBetterModel(mob, fMobId);
        
        net.elpixedge.core.combat.CustomMobAbilityModule abilityMod = plugin.getModule(net.elpixedge.core.combat.CustomMobAbilityModule.class);
        if (abilityMod != null) abilityMod.registerMob(mob);

        sp.aliveUUID = mob.getUniqueId();
        sp.respawnAtTick = -1;
        sp.despawnAtTick = -1;
        sp.isHunting = false;
        sp.lastWanderTick = loopTick;
    }

    private String selectWeightedMob(SpawnerDef def) {
        int roll = new Random().nextInt(def.totalWeight);
        int cumulative = 0;
        for (Map.Entry<String, Integer> entry : def.mobWeights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) return entry.getKey();
        }
        return def.mobWeights.keySet().iterator().next();
    }

    private void onMobRemoved(SpawnPoint sp) {
        sp.aliveUUID = null;
        sp.respawnAtTick = loopTick + sp.def.respawnTimeSec;
        sp.isHunting = false;
        sp.despawnAtTick = -1;
    }

    // ==========================================
    // Death Event Listener
    // ==========================================
    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        // Suppress vanilla drops for custom mobs (e.g. Ravager saddle on blighted_wyvern)
        // Custom rare/unique drops are handled separately by the progression system
        if (e.getEntity().getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }

        UUID deadUUID = e.getEntity().getUniqueId();
        for (SpawnPoint sp : spawnPoints) {
            if (deadUUID.equals(sp.aliveUUID)) {
                onMobRemoved(sp);
                break;
            }
        }
    }

    // ==========================================
    // BetterModel Integration (reflection, no compile dependency)
    // ==========================================
    private void applyBetterModel(LivingEntity entity, String mobId) {
        if (mobId == null) return;
        String modelId = plugin.getConfig().getString("mobs." + mobId + ".better_model.model_id", null);
        if (modelId == null || modelId.isEmpty()) return;

        entity.getPersistentDataContainer().set(Keys.betterModelId, PersistentDataType.STRING, modelId);

        org.bukkit.plugin.Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null || !betterModel.isEnabled()) return;

        try {
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            java.lang.reflect.Method adaptMethod = adapterClass.getMethod("adapt", org.bukkit.entity.Entity.class);
            Object platformEntity = adaptMethod.invoke(null, entity);

            if (platformEntity == null) return;

            Class<?> apiClass = Class.forName("kr.toxicity.model.api.BetterModel");
            java.lang.reflect.Method modelMethod = apiClass.getMethod("model", String.class);
            Object optionalBlueprint = modelMethod.invoke(null, modelId);

            if (optionalBlueprint instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) optionalBlueprint;
                if (opt.isPresent()) {
                    Object blueprint = opt.get();
                    Class<?> platformEntityClass = Class.forName("kr.toxicity.model.api.platform.PlatformEntity");
                    Class<?> modelRendererClass = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer");
                    java.lang.reflect.Method getOrCreateMethod = modelRendererClass.getMethod("getOrCreate", platformEntityClass);
                    getOrCreateMethod.invoke(blueprint, platformEntity);
                }
            }
        } catch (Exception e) {
            // Silent fail — BetterModel not installed or API changed
        }
    }

    // ==========================================
    // Data Classes
    // ==========================================
    private static class SpawnerDef {
        String id;
        Map<String, Integer> mobWeights;
        int totalWeight;
        int minLevel, maxLevel;
        int maxAlivePerLoc;
        int activationRange;
        int wanderRadius;
        int huntRange;
        int huntDurationSec;
        int despawnTimeoutSec;
        int respawnTimeSec;
    }

    private static class SpawnPoint {
        SpawnerDef def;
        String worldName;
        double x, y, z;

        // Runtime state
        UUID aliveUUID;
        long respawnAtTick;  // loopTick when respawn is allowed
        boolean isHunting;
        long huntExpireTick;
        long despawnAtTick;  // loopTick when mob despawns (-1 = not counting)
        long lastWanderTick;

        Location getLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new Location(world, x, y, z);
        }
    }
}
