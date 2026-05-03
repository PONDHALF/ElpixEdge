package net.elpixedge.core.combat;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.utils.Keys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class CustomMobAbilityModule implements Module, Listener {

    private final ElpixEdge plugin;
    private final Set<LivingEntity> trackedMobs = new HashSet<>();
    private BukkitRunnable tickTask;

    public CustomMobAbilityModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startTickTask();
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
    }

    public void registerMob(LivingEntity mob) {
        if (mob != null && mob.isValid() && !mob.isDead()) {
            trackedMobs.add(mob);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        trackedMobs.remove(e.getEntity());
    }

    @EventHandler
    public void onMobFall(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) {
            if (e.getEntity().getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)) {
                e.setCancelled(true);
            }
        }
    }

    private void startTickTask() {
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                Iterator<LivingEntity> it = trackedMobs.iterator();
                while (it.hasNext()) {
                    LivingEntity mob = it.next();
                    if (mob == null || mob.isDead() || !mob.isValid()) {
                        it.remove();
                        continue;
                    }

                    if (!(mob instanceof Mob)) continue;
                    Mob cMob = (Mob) mob;
                    LivingEntity target = cMob.getTarget();
                    if (!(target instanceof Player)) continue;

                    Player p = (Player) target;
                    if (!p.getWorld().equals(mob.getWorld())) continue;

                    double distSq = mob.getLocation().distanceSquared(p.getLocation());

                    String mobId = mob.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING);
                    if (mobId == null) continue;

                    ConfigurationSection abilities = plugin.getConfig().getConfigurationSection("mobs." + mobId + ".better_model");
                    if (abilities == null) continue;

                    long activeCastUntil = mob.getPersistentDataContainer().getOrDefault(new org.bukkit.NamespacedKey(plugin, "active_cast_until"), PersistentDataType.LONG, 0L);
                    if (currentTime < activeCastUntil) continue;

                    // Check ability1 (usually closer range) first
                    boolean triggered = checkAndTriggerAbility(cMob, p, distSq, mobId, abilities, "ability1", currentTime);
                    if (!triggered) {
                        checkAndTriggerAbility(cMob, p, distSq, mobId, abilities, "ability2", currentTime);
                    }
                }
            }
        };
        tickTask.runTaskTimer(plugin, 20L, 10L); // run every 10 ticks (0.5s)
    }

    private boolean checkAndTriggerAbility(Mob mob, Player target, double distSq, String mobId, ConfigurationSection abilities, String abilityKey, long currentTime) {
        ConfigurationSection abilityConfig = abilities.getConfigurationSection(abilityKey);
        if (abilityConfig == null) return false;

        String abilityName = abilityConfig.getString("ability");
        if (abilityName == null || abilityName.isEmpty()) return false;

        double activationDist = abilityConfig.getDouble("distant", 5.0);
        if (distSq > activationDist * activationDist) return false;

        double cooldownSec = abilityConfig.getDouble("cooldown", 10.0);
        org.bukkit.NamespacedKey cdKey = new org.bukkit.NamespacedKey(plugin, "cd_" + abilityKey);
        long lastUse = mob.getPersistentDataContainer().getOrDefault(cdKey, PersistentDataType.LONG, 0L);
        
        if (currentTime - lastUse < cooldownSec * 1000) return false;

        // Passed checks, trigger ability
        mob.getPersistentDataContainer().set(cdKey, PersistentDataType.LONG, currentTime);

        int level = mob.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
        double multiplier = abilityConfig.getDouble("damagemultiplier", 5.0);
        double damage = level * multiplier;

        String animation = abilityConfig.getString("animation");
        org.bukkit.NamespacedKey activeKey = new org.bukkit.NamespacedKey(plugin, "active_cast_until");

        if ("corrupted_breath".equalsIgnoreCase(abilityName)) {
            mob.getPersistentDataContainer().set(activeKey, PersistentDataType.LONG, currentTime + 4000);
            triggerCorruptedBreath(mob, target, animation, damage, mobId);
            return true;
        } else if ("corrupted_dive".equalsIgnoreCase(abilityName)) {
            mob.getPersistentDataContainer().set(activeKey, PersistentDataType.LONG, currentTime + 3500);
            triggerCorruptedDive(mob, target, animation, damage, mobId);
            return true;
        }
        return false;
    }

    private void triggerCorruptedBreath(Mob mob, Player target, String animation, double damage, String mobId) {
        if (animation != null) {
            playBetterModelAnimation(mob, mobId, animation);
        }

        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.5f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (mob.isDead() || !mob.isValid() || ticks >= 20) {
                    cancel();
                    return;
                }

                Location eyeLoc = mob.getEyeLocation();
                Vector dir = eyeLoc.getDirection().normalize();

                for (int i = 1; i <= 6; i++) {
                    Location particleLoc = eyeLoc.clone().add(dir.clone().multiply(i));
                    mob.getWorld().spawnParticle(Particle.FLAME, particleLoc, 10, 0.15, 0.15, 0.15, 0.0);
                    mob.getWorld().spawnParticle(Particle.SMOKE, particleLoc, 5, 0.2, 0.2, 0.2, 0.0);
                }

                for (org.bukkit.entity.Entity e : mob.getNearbyEntities(6, 6, 6)) {
                    if (e instanceof Player && !e.isDead()) {
                        Player p = (Player) e;
                        Vector toPlayer = p.getLocation().toVector().subtract(eyeLoc.toVector()).normalize();
                        if (dir.angle(toPlayer) < Math.PI / 4) { // within 45 degrees cone
                            p.damage(damage, mob);
                        }
                    }
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 4L); // 20 times every 4 ticks (4 seconds total)
    }

    private void triggerCorruptedDive(Mob mob, Player target, String animation, double damage, String mobId) {
        if (animation != null) {
            playBetterModelAnimation(mob, mobId, animation);
        }
        
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PHANTOM_SWOOP, 1.0f, 0.5f);

        // Wait 10 ticks (0.5s)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (mob.isDead() || !mob.isValid()) return;
            
            // Fly up 20 blocks
            mob.setVelocity(new Vector(0, 3, 0)); // strong upward velocity
            
            // Wait 20 ticks (1s) to hover/calculate path
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (mob.isDead() || !mob.isValid()) return;
                
                Location tLoc = target.getLocation();
                Vector diveVec = tLoc.toVector().subtract(mob.getLocation().toVector()).normalize().multiply(3.5);
                mob.setVelocity(diveVec);
                
                // Impact task
                new BukkitRunnable() {
                    int timeout = 0;
                    @Override
                    public void run() {
                        if (mob.isDead() || !mob.isValid() || timeout >= 40) { // 2s timeout
                            cancel();
                            return;
                        }
                        
                        if (mob.isOnGround() || mob.getLocation().distanceSquared(tLoc) < 4.0) {
                            // Impact
                            mob.getWorld().spawnParticle(Particle.EXPLOSION, mob.getLocation(), 1);
                            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
                            
                            for (org.bukkit.entity.Entity e : mob.getNearbyEntities(5, 5, 5)) {
                                if (e instanceof Player && !e.isDead()) {
                                    ((Player) e).damage(damage, mob);
                                }
                            }
                            cancel();
                            return;
                        }
                        timeout++;
                    }
                }.runTaskTimer(plugin, 5L, 1L); // Check every tick after 5 ticks initial delay
                
            }, 20L);
            
        }, 10L);
    }

    private void playBetterModelAnimation(LivingEntity entity, String mobId, String animationName) {
        if (animationName == null || animationName.isEmpty()) return;

        org.bukkit.plugin.Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null || !betterModel.isEnabled()) return;

        try {
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            java.lang.reflect.Method adaptMethod = adapterClass.getMethod("adapt", org.bukkit.entity.Entity.class);
            Object platformEntity = adaptMethod.invoke(null, entity);
            if (platformEntity == null) return;

            Class<?> apiClass = Class.forName("kr.toxicity.model.api.BetterModel");
            Class<?> platformEntityClass = Class.forName("kr.toxicity.model.api.platform.PlatformEntity");
            java.lang.reflect.Method registryMethod = apiClass.getMethod("registry", platformEntityClass);
            Object optionalRegistry = registryMethod.invoke(null, platformEntity);

            if (optionalRegistry instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) optionalRegistry;
                if (opt.isPresent()) {
                    Object registry = opt.get();
                    Class<?> registryClass = Class.forName("kr.toxicity.model.api.tracker.EntityTrackerRegistry");
                    java.lang.reflect.Method firstMethod = registryClass.getMethod("first");
                    Object tracker = firstMethod.invoke(registry);

                    if (tracker != null) {
                        Class<?> trackerClass = Class.forName("kr.toxicity.model.api.tracker.Tracker");
                        java.lang.reflect.Method animateMethod = trackerClass.getMethod("animate", String.class);
                        animateMethod.invoke(tracker, animationName);
                    }
                }
            }
        } catch (Exception ex) {
            // Silently fail
        }
    }
}
