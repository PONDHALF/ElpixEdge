package net.elpixedge.core.combat;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.player.PlayerProfile;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class CombatModule implements Module, Listener {

    private final ElpixEdge plugin;
    public final Set<Projectile> homingProjectiles = new HashSet<>();

    public CombatModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("CombatModule enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Cleanup if necessary
    }

    private PlayerProfile getPlayerProfile(Player p) {
        return ElpixEdge.getInstance().getModule(PlayerModule.class).getProfile(p);
    }

    public void updateMobName(LivingEntity e) {
        if (e.isDead() || e instanceof Player || e instanceof org.bukkit.entity.ArmorStand) return;
        int lvl = e.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
        int currentHp = (int) e.getHealth();
        
        PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
        Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
        int maxHp = e.getAttribute(maxHpAttr) != null ? (int) e.getAttribute(maxHpAttr).getValue() : 20;
        
        String customNamePrefix = e.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING) ?
                e.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING).toUpperCase().replace("_", " ") : e.getType().name();
        
        String displayName = ChatColor.GRAY + "[Lv." + lvl + "] " + ChatColor.RED + customNamePrefix + " " + ChatColor.GREEN + currentHp + "/" + maxHp + " HP";
        
        // BetterModel Hologram Check
        if (e.getPersistentDataContainer().has(Keys.hologramUuid, PersistentDataType.STRING)) {
            String uuidStr = e.getPersistentDataContainer().get(Keys.hologramUuid, PersistentDataType.STRING);
            if (uuidStr != null) {
                org.bukkit.entity.Entity holo = Bukkit.getEntity(java.util.UUID.fromString(uuidStr));
                if (holo instanceof TextDisplay) {
                    ((TextDisplay) holo).setText(displayName);
                }
            }
            // Force hide vanilla nametag completely for BetterModel mobs
            e.setCustomNameVisible(false);
            e.setCustomName(null);
        } else {
            e.setCustomName(displayName);
            e.setCustomNameVisible(true);
        }
    }

    public void spawnDamageHologram(Location loc, double damage, int critTier) {
        Random r = new Random();
        loc.add((r.nextDouble() - 0.5) * 1.0, r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 1.0);
        TextDisplay display = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        
        String prefix = "";
        ChatColor color = ChatColor.GRAY;
        
        if (critTier == 1) { color = ChatColor.WHITE; prefix = ""; }
        else if (critTier == 2) { color = ChatColor.YELLOW; prefix = "✧ "; }
        else if (critTier >= 3) { color = ChatColor.RED; prefix = "✯ "; }

        display.setText(color + "" + ChatColor.BOLD + prefix + String.format("%.1f", damage));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));

        new BukkitRunnable() {
            @Override
            public void run() { 
                if (!display.isDead()) display.remove(); 
            }
        }.runTaskLater(plugin, 20L); // 1 second
    }

    /**
     * Starts a homing task for a projectile.
     * tier 1 = weak, tier 2 = strong, tier 3 = extreme
     */
    public void startHomingTask(Projectile arrow, int aimTier) {
        homingProjectiles.add(arrow);

        // Homing strength: tier1=0.15, tier2=0.90 (extreme track), tier3=2.0 (super lock)
        final double homingStrength = aimTier == 1 ? 0.15 : aimTier == 2 ? 0.90 : 2.0;
        final double trackRange = aimTier == 3 ? 32.0 : 16.0;
        final int maxTicks = aimTier == 3 ? 100 : 60; // tier3=5s, others=3s tracking

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                ticks++;
                if (ticks > maxTicks || arrow.isDead() || !arrow.isValid() || !homingProjectiles.contains(arrow)) {
                    homingProjectiles.remove(arrow);
                    if (!arrow.isDead()) arrow.remove(); // 5s expiry or end of track
                    cancel();
                    return;
                }
                // Find nearest living entity in range (exclude shooter)
                LivingEntity nearest = null;
                double nearestDist = trackRange * trackRange;
                for (org.bukkit.entity.Entity nearby : arrow.getWorld().getNearbyEntities(arrow.getLocation(), trackRange, trackRange, trackRange)) {
                    if (!(nearby instanceof LivingEntity)) continue;
                    if (nearby.equals(arrow.getShooter())) continue;
                    if (nearby instanceof Player && arrow.getShooter() instanceof Player) continue; // skip other players
                    double d = nearby.getLocation().distanceSquared(arrow.getLocation());
                    if (d < nearestDist) { nearestDist = d; nearest = (LivingEntity) nearby; }
                }
                if (nearest == null) return;
                // Steer arrow toward target
                org.bukkit.util.Vector toTarget = nearest.getLocation().add(0, nearest.getHeight() * 0.75, 0).toVector()
                        .subtract(arrow.getLocation().toVector()).normalize();
                
                org.bukkit.util.Vector velocity = arrow.getVelocity();
                double speed = velocity.length();

                // Tier 3: Snap-to-target if close enough (within 8 blocks)
                if (aimTier >= 3) {
                    if (nearestDist < 64.0) {
                        arrow.setVelocity(toTarget.multiply(speed));
                    }
                    // If not close enough, do nothing (fly straight)
                } else {
                    velocity.add(toTarget.multiply(homingStrength)).normalize().multiply(speed);
                    arrow.setVelocity(velocity);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        // Backup cleanup for projectiles that aren't homing but need removal
        new BukkitRunnable() {
            @Override public void run() {
                if (!arrow.isDead() && arrow.isValid()) arrow.remove();
            }
        }.runTaskLater(plugin, 100L); // Absolute 5s expiry
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onGenericDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof LivingEntity) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> updateMobName((LivingEntity) e.getEntity()), 1L);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityHeal(org.bukkit.event.entity.EntityRegainHealthEvent e) {
        if (e.getEntity() instanceof LivingEntity) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> updateMobName((LivingEntity) e.getEntity()), 1L);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player) attacker = (Player) e.getDamager();
        else if (e.getDamager() instanceof Projectile && ((Projectile)e.getDamager()).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile)e.getDamager()).getShooter();
        }

        if (attacker != null && e.getEntity() instanceof LivingEntity) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            
            // Skill Requirements Check
            if (weapon != null) {
                PlayerModule playerMod = ElpixEdge.getInstance().getModule(PlayerModule.class);
                if (playerMod != null && !playerMod.canUseItem(attacker, weapon)) {
                    PlayerProfile profile = getPlayerProfile(attacker);
                    if (profile != null) profile.setActionBarMessage(ChatColor.DARK_RED + "☠ You do not meet the requirements to use this item!", 2000);
                    e.setCancelled(true);
                    return;
                }
            }

            PlayerProfile profile = getPlayerProfile(attacker);
            if (profile == null) return;
            
            Map<StatType, Double> stats = profile.getStats();
            double finalDmg = stats.getOrDefault(StatType.DAMAGE, 1.0);
            double cc = stats.getOrDefault(StatType.CRIT_CHANCE, 0.0);
            double cd = stats.getOrDefault(StatType.CRIT_DAMAGE, 0.0);
            boolean isOverride = false;

            NamespacedKey overrideKey = new NamespacedKey(plugin, "override_dmg");
            if (attacker.getPersistentDataContainer().has(overrideKey, PersistentDataType.DOUBLE)) {
                finalDmg = attacker.getPersistentDataContainer().get(overrideKey, PersistentDataType.DOUBLE);
                cc = 0;
                isOverride = true;
            } else if (e.getDamager() instanceof Projectile && e.getDamager().getPersistentDataContainer().has(Keys.dmg, PersistentDataType.DOUBLE)) {
                finalDmg = e.getDamager().getPersistentDataContainer().get(Keys.dmg, PersistentDataType.DOUBLE);
            }

            boolean isRanged = false;
            if (e.getDamager() instanceof Player && weapon != null && weapon.hasItemMeta() && weapon.getItemMeta().getPersistentDataContainer().has(Keys.ranged, PersistentDataType.BYTE)) {
                finalDmg = 1.0;
                isRanged = true;
            }

            if (!isOverride && e.getDamager() instanceof Player) {
                if (attacker.getAttackCooldown() >= 0.99f) cc += 100.0;
                if (attacker.getFallDistance() > 0.0 && !attacker.isOnGround()) cc += 50.0;
            }

            if (!isOverride && weapon != null && weapon.hasItemMeta()) {
                ItemMeta wMeta = weapon.getItemMeta();
                LivingEntity target = (LivingEntity) e.getEntity();
                String targetType = target.getType().name();

                // Scan all enchant entries in config and apply bonuses if the weapon has the matching PDC tag
                for (String cat : new String[]{"basic", "unique"}) {
                    ConfigurationSection catSec = plugin.getConfig().getConfigurationSection("enchants." + cat);
                    if (catSec == null) continue;
                    for (String eId : catSec.getKeys(false)) {
                        NamespacedKey eKey = new NamespacedKey(plugin, "ench_" + eId);
                        if (!wMeta.getPersistentDataContainer().has(eKey, PersistentDataType.BYTE)) continue;

                        // damage_bonus_pct (Sharpness-style)
                        int dmgBonusPct = catSec.getInt(eId + ".damage_bonus_pct", 0);
                        if (dmgBonusPct > 0) finalDmg *= (1.0 + dmgBonusPct / 100.0);

                        // undead_bonus_pct (Smite-style)
                        int undeadPct = catSec.getInt(eId + ".undead_bonus_pct", 0);
                        if (undeadPct > 0) {
                            if (targetType.contains("ZOMBIE") || targetType.contains("SKELETON")
                                    || targetType.contains("WITHER") || targetType.contains("PHANTOM")) {
                                finalDmg *= (1.0 + undeadPct / 100.0);
                            }
                        }

                        // arthro_bonus_pct (Bane of Arthropods-style)
                        int arthroPct = catSec.getInt(eId + ".arthro_bonus_pct", 0);
                        if (arthroPct > 0) {
                            if (targetType.contains("SPIDER") || targetType.contains("SILVERFISH")
                                    || targetType.contains("ENDERMITE") || targetType.contains("BEE")) {
                                finalDmg *= (1.0 + arthroPct / 100.0);
                            }
                        }
                    }
                }
            }

            int critTier = 0;
            if (!isOverride) {
                Random rand = new Random();
                while (cc >= 100.0) { critTier++; cc -= 100.0; }
                if (rand.nextDouble() * 100.0 < cc) critTier++;
            }
            
            if (critTier > 0) finalDmg = finalDmg * (1.0 + ((cd / 100.0) * critTier));

            if (!isOverride && weapon != null && weapon.hasItemMeta()) {
                ItemMeta wMeta = weapon.getItemMeta();
                LivingEntity target = (LivingEntity) e.getEntity();

                // life_steal_3/2/1 — heal based on % of attacker's Max HP (highest tier wins)
                for (String eId : new String[]{"life_steal_3", "life_steal_2", "life_steal_1"}) {
                    NamespacedKey eKey = new NamespacedKey(plugin, "ench_" + eId);
                    if (wMeta.getPersistentDataContainer().has(eKey, PersistentDataType.BYTE)) {
                        int pct = plugin.getConfig().getInt("enchants.unique." + eId + ".life_steal_pct", 10);
                        PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
                        Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
                        double maxHp = attacker.getAttribute(maxHpAttr) != null ? attacker.getAttribute(maxHpAttr).getBaseValue() : 20.0;
                        double healAmt = maxHp * (pct / 100.0);
                        attacker.setHealth(Math.min(maxHp, attacker.getHealth() + healAmt));
                        attacker.spawnParticle(Particle.HEART, attacker.getLocation().add(0, 1, 0), 1);
                        break; // Apply highest tier only
                    }
                }

                // fire_aspect_1/2 (basic) and fire_aspect_3 (unique) — DoT based on item damage
                for (String eId : new String[]{"fire_aspect_1", "fire_aspect_2", "fire_aspect_3"}) {
                    NamespacedKey eKey = new NamespacedKey(plugin, "ench_" + eId);
                    if (wMeta.getPersistentDataContainer().has(eKey, PersistentDataType.BYTE)) {
                        // Determine which category this enchant belongs to
                        String eCat = (eId.equals("fire_aspect_3")) ? "unique" : "basic";
                        int dotPct = plugin.getConfig().getInt("enchants." + eCat + "." + eId + ".fire_dot_pct", 1);
                        final double dotDmgPerTick = finalDmg * (dotPct / 100.0);
                        final LivingEntity fireTarget = target;
                        final Player fireAttacker = attacker;
                        // Set visual fire and store DoT src
                        int fireTicks = eId.equals("fire_aspect_1") ? 40 : eId.equals("fire_aspect_2") ? 60 : 100;
                        fireTarget.setFireTicks(fireTicks);
                        fireTarget.getPersistentDataContainer().set(
                            new NamespacedKey(plugin, "fire_dot_src"),
                            PersistentDataType.STRING,
                            fireAttacker.getUniqueId().toString() + ":" + eId
                        );
                        // Schedule repeating DoT: once per second while target is on fire
                        new org.bukkit.scheduler.BukkitRunnable() {
                            int ticks = fireTicks;
                            @Override
                            public void run() {
                                if (fireTarget.isDead() || fireTarget.getFireTicks() <= 0) { cancel(); return; }
                                NamespacedKey overrideKey = new NamespacedKey(plugin, "override_dmg");
                                fireAttacker.getPersistentDataContainer().set(overrideKey, PersistentDataType.DOUBLE, dotDmgPerTick);
                                fireTarget.damage(dotDmgPerTick, fireAttacker);
                                fireAttacker.getPersistentDataContainer().remove(overrideKey);
                                ticks -= 20;
                                if (ticks <= 0) cancel();
                            }
                        }.runTaskTimer(plugin, 20L, 20L);
                        break;
                    }
                }
            }

            e.setDamage(finalDmg);

            profile.addComboDamage(finalDmg);

            e.getEntity().getPersistentDataContainer().set(Keys.lastDamager, PersistentDataType.STRING, attacker.getUniqueId().toString());
            Bukkit.getScheduler().runTaskLater(plugin, () -> updateMobName((LivingEntity) e.getEntity()), 1L);

            if (!profile.isHideDamageHolo()) {
                double heightOffset = e.getEntity().getHeight() + 0.5;
                spawnDamageHologram(e.getEntity().getLocation().add(0, heightOffset, 0), finalDmg, critTier);
            }
            return;
        }

        // Player Defense
        if (e.getEntity() instanceof Player && !(e.getDamager() instanceof Player)) {
            Player p = (Player) e.getEntity();
            double incoming = e.getDamage();
            
            if (e.getDamager() instanceof LivingEntity) {
                LivingEntity mob = (LivingEntity) e.getDamager();
                int mobLvl = mob.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
                double tierDmgMult = mob.getPersistentDataContainer().getOrDefault(Keys.tierDmgMult, PersistentDataType.DOUBLE, 1.0);
                incoming = 2.0 + (mobLvl * tierDmgMult);

                if (mob.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)) {
                    String mobId = mob.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING);
                    playBetterModelAnimation(mob, mobId, "normal_attack.animation");
                }
            }

            PlayerProfile profile = getPlayerProfile(p);
            if (profile != null) {
                double pDef = profile.getStat(StatType.DEFENSE);
                e.setDamage(incoming * (20.0 / (20.0 + pDef)));
            }
        }
    }



    @EventHandler
    public void onEntityShootBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        ItemStack bow = e.getBow();
        if (bow == null || !bow.hasItemMeta()) return;
        ItemMeta meta = bow.getItemMeta();

        // Determine highest aiming tier on this bow
        int aimTier = 0;
        for (int t = 3; t >= 1; t--) {
            if (meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "ench_aiming_" + t), PersistentDataType.BYTE)) {
                aimTier = t;
                break;
            }
        }
        
        if (!(e.getProjectile() instanceof Projectile)) return;
        Projectile projectile = (Projectile) e.getProjectile();
        
        if (aimTier > 0) {
            startHomingTask(projectile, aimTier);
        } else {
            // No aimbot, but still remove after 5s
            new BukkitRunnable() {
                @Override public void run() {
                    if (!projectile.isDead() && projectile.isValid()) projectile.remove();
                }
            }.runTaskLater(plugin, 100L);
        }
    }

    @EventHandler
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        homingProjectiles.remove(e.getEntity());
    }

    @EventHandler
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof org.bukkit.entity.ArmorStand) return;
        LivingEntity ent = e.getEntity();
        if (!ent.getPersistentDataContainer().has(Keys.lvl, PersistentDataType.INTEGER)) {
            int lvl = new java.util.Random().nextInt(10) + 1;
            ent.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, lvl);

            // Read tier multipliers from config (custom mobs only)
            String mobId = ent.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)
                    ? ent.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING) : null;
            double tierHpMult = 10.0; // default HP per level
            double tierDmgMult = 1.0; // default DMG per level
            if (mobId != null && plugin.getConfig().contains("mobs." + mobId)) {
                tierHpMult = plugin.getConfig().getDouble("mobs." + mobId + ".tier_hp_mult", 10.0);
                tierDmgMult = plugin.getConfig().getDouble("mobs." + mobId + ".tier_dmg_mult", 1.0);
            }

            double maxHp = 15.0 + (lvl * tierHpMult);
            double actualMax = Math.min(1024.0, maxHp);
            ent.getPersistentDataContainer().set(Keys.tierDmgMult, PersistentDataType.DOUBLE, tierDmgMult);
            
            PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
            Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
            if (ent.getAttribute(maxHpAttr) != null) ent.getAttribute(maxHpAttr).setBaseValue(actualMax);
            
            ent.setHealth(actualMax);
            ent.setCustomNameVisible(true);
            updateMobName(ent);

            // BetterModel integration
            applyBetterModel(ent, mobId);
            equipMob(ent, mobId);
            
            CustomMobAbilityModule abilityMod = plugin.getModule(CustomMobAbilityModule.class);
            if (abilityMod != null) abilityMod.registerMob(ent);
        }
    }

    @EventHandler
    public void onSpawnerSpawn(org.bukkit.event.entity.SpawnerSpawnEvent e) {
        org.bukkit.block.CreatureSpawner spawner = e.getSpawner();
        if (spawner.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)) {
            String customMobId = spawner.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING);
            int lvl = spawner.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
            
            LivingEntity ent = (LivingEntity) e.getEntity();
            ent.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, customMobId);
            ent.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, lvl);

            // Read tier multipliers from config
            double tierHpMult = plugin.getConfig().getDouble("mobs." + customMobId + ".tier_hp_mult", 10.0);
            double tierDmgMult = plugin.getConfig().getDouble("mobs." + customMobId + ".tier_dmg_mult", 1.0);
            
            double maxHp = 15.0 + (lvl * tierHpMult);
            double actualMax = Math.min(1024.0, maxHp);
            ent.getPersistentDataContainer().set(Keys.tierDmgMult, PersistentDataType.DOUBLE, tierDmgMult);

            PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
            Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
            if (ent.getAttribute(maxHpAttr) != null) ent.getAttribute(maxHpAttr).setBaseValue(actualMax);
            
            ent.setHealth(actualMax);
            ent.setCustomNameVisible(true);
            updateMobName(ent);

            // BetterModel integration
            applyBetterModel(ent, customMobId);
            equipMob(ent, customMobId);
            
            CustomMobAbilityModule abilityMod = plugin.getModule(CustomMobAbilityModule.class);
            if (abilityMod != null) abilityMod.registerMob(ent);
        }
    }

    // ==========================================
    // Nullify Enchantment Logic
    // ==========================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEndermanTeleport(EntityTeleportEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Enderman)) return;
        
        // Endermen teleport away from projectiles. We check nearby projectiles to see if they belong to a Nullify shooter.
        for (org.bukkit.entity.Entity nearby : e.getEntity().getNearbyEntities(4, 4, 4)) {
            if (nearby instanceof Projectile) {
                Projectile proj = (Projectile) nearby;
                if (proj.getShooter() instanceof Player) {
                    Player p = (Player) proj.getShooter();
                    if (hasNullify(p)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    // ignoreCancelled = false: we must intercept even when vanilla cancels Enderman/Wither immunity
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Projectile)) return;
        Projectile proj = (Projectile) e.getDamager();
        if (!(proj.getShooter() instanceof Player)) return;
        Player p = (Player) proj.getShooter();

        if (hasNullify(p)) {
            // Nullify logic is now handled in onProjectileHitEntity
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHitEntity(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (e.getHitEntity() == null) return;
        Projectile proj = e.getEntity();
        if (!(proj.getShooter() instanceof Player)) return;
        Player p = (Player) proj.getShooter();
        if (!hasNullify(p)) return;

        // Enderman: cancel hit event to prevent deflection, apply damage directly
        if (e.getHitEntity() instanceof org.bukkit.entity.Enderman) {
            org.bukkit.entity.Enderman enderman = (org.bukkit.entity.Enderman) e.getHitEntity();
            e.setCancelled(true); // Stop vanilla deflection
            
            double dmg = proj.getPersistentDataContainer().getOrDefault(Keys.dmg, PersistentDataType.DOUBLE, -1.0);
            if (dmg < 0 && proj instanceof org.bukkit.entity.Arrow) {
                dmg = ((org.bukkit.entity.Arrow) proj).getDamage();
            }
            if (dmg < 0) dmg = 5.0; // fallback
            
            enderman.damage(dmg, p);
            proj.remove();
        }
        
        // Wither Phase 2: cancel hit event to prevent deflection, apply damage directly
        else if (e.getHitEntity() instanceof org.bukkit.entity.Wither) {
            org.bukkit.entity.Wither wither = (org.bukkit.entity.Wither) e.getHitEntity();
            if (wither.getHealth() <= wither.getAttribute(Attribute.MAX_HEALTH).getValue() / 2) {
                e.setCancelled(true); // Stop vanilla deflection
                
                double dmg = proj.getPersistentDataContainer().getOrDefault(Keys.dmg, PersistentDataType.DOUBLE, -1.0);
                if (dmg < 0 && proj instanceof org.bukkit.entity.Arrow) {
                    dmg = ((org.bukkit.entity.Arrow) proj).getDamage();
                }
                if (dmg < 0) dmg = 5.0;
                
                wither.damage(dmg, p);
                proj.remove();
            }
        }
    }

    private boolean hasNullify(Player p) {
        NamespacedKey nullifyKey = new NamespacedKey(plugin, "ench_nullify_1");
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main != null && main.hasItemMeta()) {
            if (main.getItemMeta().getPersistentDataContainer().has(nullifyKey, PersistentDataType.BYTE)) return true;
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && off.hasItemMeta()) {
            if (off.getItemMeta().getPersistentDataContainer().has(nullifyKey, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    // ==========================================
    // BetterModel Plugin Integration
    // Applies custom model to mob if BetterModel plugin is available
    // and the mob has a better_model.model_id in config.
    // Uses reflection to avoid compile-time dependency on BetterModel.
    // ==========================================
    public void applyBetterModel(LivingEntity entity, String mobId) {
        if (mobId == null) return;
        String modelId = plugin.getConfig().getString("mobs." + mobId + ".better_model.model_id", null);
        if (modelId == null || modelId.isEmpty()) return;

        plugin.getLogger().info("[BetterModel-Debug] Attempting to apply model '" + modelId + "' to entity " + entity.getUniqueId());

        // Store model ID on entity PDC for reference
        entity.getPersistentDataContainer().set(Keys.betterModelId, PersistentDataType.STRING, modelId);

        // Attempt to apply via BetterModel plugin API using reflection
        org.bukkit.plugin.Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null || !betterModel.isEnabled()) {
            plugin.getLogger().info("[BetterModel-Debug] BetterModel plugin not found or not enabled.");
            return;
        }

        try {
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            java.lang.reflect.Method adaptMethod = adapterClass.getMethod("adapt", org.bukkit.entity.Entity.class);
            Object platformEntity = adaptMethod.invoke(null, entity);

            if (platformEntity == null) {
                plugin.getLogger().warning("[BetterModel-Debug] Failed to adapt Bukkit entity to PlatformEntity.");
                return;
            }
            plugin.getLogger().info("[BetterModel-Debug] Successfully adapted entity to PlatformEntity.");

            Class<?> apiClass = Class.forName("kr.toxicity.model.api.BetterModel");
            java.lang.reflect.Method modelMethod = apiClass.getMethod("model", String.class);
            Object optionalBlueprint = modelMethod.invoke(null, modelId);

            if (optionalBlueprint instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) optionalBlueprint;
                if (opt.isPresent()) {
                    Object blueprint = opt.get();
                    plugin.getLogger().info("[BetterModel-Debug] Found ModelRenderer for model '" + modelId + "'.");
                    
                    Class<?> platformEntityClass = Class.forName("kr.toxicity.model.api.platform.PlatformEntity");
                    Class<?> modelRendererClass = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer");
                    java.lang.reflect.Method getOrCreateMethod = modelRendererClass.getMethod("getOrCreate", platformEntityClass);
                    Object tracker = getOrCreateMethod.invoke(blueprint, platformEntity);
                    
                    plugin.getLogger().info("[BetterModel-Debug] Successfully called getOrCreate. Tracker: " + (tracker != null));

                    // We spawn an untethered TextDisplay hologram and teleport it smoothly to avoid camera jitter.
                    if (!entity.getPersistentDataContainer().has(Keys.hologramUuid, PersistentDataType.STRING)) {
                        
                        // To prevent BetterModel from rendering its own duplicate nametag,
                        // we must ensure the custom name is hidden or null *before* we do anything,
                        // but since BetterModel might have already cached it, we force it off.
                        entity.setCustomNameVisible(false);
                        entity.setCustomName(null);

                        // Generate the display name dynamically since we wiped it
                        int lvl = entity.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
                        int currentHp = (int) entity.getHealth();
                        PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
                        Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
                        int maxHp = entity.getAttribute(maxHpAttr) != null ? (int) entity.getAttribute(maxHpAttr).getValue() : 20;
                        String customNamePrefix = entity.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING) ?
                                entity.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING).toUpperCase().replace("_", " ") : entity.getType().name();
                        String name = ChatColor.GRAY + "[Lv." + lvl + "] " + ChatColor.RED + customNamePrefix + " " + ChatColor.GREEN + currentHp + "/" + maxHp + " HP";

                        TextDisplay hologram = entity.getWorld().spawn(entity.getLocation(), TextDisplay.class, td -> {
                            td.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, "hologram");
                            td.setBillboard(Display.Billboard.CENTER);
                            td.setDefaultBackground(false);
                            td.setBackgroundColor(Color.fromARGB(50, 0, 0, 0));
                            td.setTeleportDuration(3); // 3 ticks interpolation for perfectly smooth movement without stutter
                            td.setText(name);
                        });
                        
                        entity.getPersistentDataContainer().set(Keys.hologramUuid, PersistentDataType.STRING, hologram.getUniqueId().toString());

                        // Read height from config
                        double configHeight = plugin.getConfig().getDouble("mobs." + mobId + ".better_model.name_height", entity.getHeight() + 0.6);

                        // Teleport and cleanup task
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (hologram.isDead() || entity.isDead() || !entity.isValid()) {
                                    hologram.remove();
                                    this.cancel();
                                    return;
                                }
                                hologram.teleport(entity.getLocation().add(0, configHeight, 0));
                            }
                        }.runTaskTimer(plugin, 1L, 1L);
                    }

                } else {
                    plugin.getLogger().warning("[BetterModel-Debug] Model '" + modelId + "' was not found in BetterModel registry (Optional is empty).");
                }
            } else {
                plugin.getLogger().warning("[BetterModel-Debug] BetterModel.model() did not return an Optional.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[BetterModel-Debug] Exception while applying BetterModel: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Plays a BetterModel animation on the entity using reflection.
     * @param entity The Bukkit entity
     * @param mobId The custom mob ID from config
     * @param configKey The key under better_model in config (e.g., "attack_animation")
     */
    private void playBetterModelAnimation(LivingEntity entity, String mobId, String configKey) {
        if (mobId == null) return;
        String animationName = plugin.getConfig().getString("mobs." + mobId + ".better_model." + configKey);
        if (animationName == null || animationName.isEmpty()) return;

        org.bukkit.plugin.Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null || !betterModel.isEnabled()) return;

        try {
            // 1. Adapt Bukkit Entity to BetterModel PlatformEntity
            Class<?> adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            java.lang.reflect.Method adaptMethod = adapterClass.getMethod("adapt", org.bukkit.entity.Entity.class);
            Object platformEntity = adaptMethod.invoke(null, entity);

            if (platformEntity == null) return;

            // 2. Get EntityTrackerRegistry from BetterModel API
            Class<?> apiClass = Class.forName("kr.toxicity.model.api.BetterModel");
            Class<?> platformEntityClass = Class.forName("kr.toxicity.model.api.platform.PlatformEntity");
            java.lang.reflect.Method registryMethod = apiClass.getMethod("registry", platformEntityClass);
            Object optionalRegistry = registryMethod.invoke(null, platformEntity);

            if (optionalRegistry instanceof java.util.Optional) {
                java.util.Optional<?> opt = (java.util.Optional<?>) optionalRegistry;
                if (opt.isPresent()) {
                    Object registry = opt.get();
                    
                    // 3. Get the first ModelTracker from the registry
                    Class<?> registryClass = Class.forName("kr.toxicity.model.api.tracker.EntityTrackerRegistry");
                    java.lang.reflect.Method firstMethod = registryClass.getMethod("first");
                    Object tracker = firstMethod.invoke(registry);

                    if (tracker != null) {
                        // 4. Call animate(String) on the tracker (inherited from kr.toxicity.model.api.tracker.Tracker)
                        Class<?> trackerClass = Class.forName("kr.toxicity.model.api.tracker.Tracker");
                        java.lang.reflect.Method animateMethod = trackerClass.getMethod("animate", String.class);
                        animateMethod.invoke(tracker, animationName);
                    }
                }
            }
        } catch (Exception ex) {
            // Silently fail to avoid console spam in combat, or log at fine level
        }
    }

    public void equipMob(LivingEntity ent, String mobId) {
        if (mobId == null) return;
        ConfigurationSection eqSec = plugin.getConfig().getConfigurationSection("mobs." + mobId + ".equipment");
        if (eqSec == null) return;
        
        org.bukkit.inventory.EntityEquipment eq = ent.getEquipment();
        if (eq == null) return;

        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);

        java.util.function.Function<String, ItemStack> getItem = (key) -> {
            String val = eqSec.getString(key);
            if (val == null || val.isEmpty()) return null;
            ItemStack custom = itemMod.generateCustomItem(val);
            if (custom != null) return custom;
            try {
                return new ItemStack(org.bukkit.Material.valueOf(val.toUpperCase()));
            } catch (Exception e) {
                return null;
            }
        };

        ItemStack helmet = getItem.apply("helmet");
        if (helmet != null) { eq.setHelmet(helmet); eq.setHelmetDropChance(0f); }
        
        ItemStack chest = getItem.apply("chestplate");
        if (chest != null) { eq.setChestplate(chest); eq.setChestplateDropChance(0f); }
        
        ItemStack legs = getItem.apply("leggings");
        if (legs != null) { eq.setLeggings(legs); eq.setLeggingsDropChance(0f); }
        
        ItemStack boots = getItem.apply("boots");
        if (boots != null) { eq.setBoots(boots); eq.setBootsDropChance(0f); }
        
        ItemStack main = getItem.apply("mainhand");
        if (main != null) { eq.setItemInMainHand(main); eq.setItemInMainHandDropChance(0f); }
        
        ItemStack off = getItem.apply("offhand");
        if (off != null) { eq.setItemInOffHand(off); eq.setItemInOffHandDropChance(0f); }
    }
}
