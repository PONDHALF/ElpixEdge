package net.elpixedge.core.combat;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.player.PlayerProfile;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.Map;

/**
 * OffhandModule — handles off-hand weapon abilities.
 *
 * Off-hand weapons carry the PDC tag "offhand_weapon" = 1 (BYTE).
 * Stats from an off-hand weapon are only applied when the item is in the off-hand slot
 * (enforced by PlayerModule.updatePlayerStats).
 *
 * When the player holds an off-hand weapon in their OFF-HAND and right-clicks,
 * this module intercepts the event before WeaponSkillManager can process the main-hand.
 *
 * Skill IDs handled here:
 *   SHIELD_BASH — Knight Shield ability (Shieldbash)
 */
public class OffhandModule implements Module, Listener {

    private final ElpixEdge plugin;

    // Shield-bash stun duration in ticks (1s = 20t)
    private static final int STUN_TICKS = 40;
    // Shieldbash lunge speed
    private static final double LUNGE_SPEED = 0.9;
    // Detection radius for hit during lunge
    private static final double HIT_RADIUS = 2.5;
    // Recoil velocity applied to player after a successful hit
    private static final double RECOIL_SPEED = 0.45;

    public OffhandModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("OffhandModule enabled successfully.");
    }

    @Override
    public void onDisable() {}

    // ==========================================
    // Off-hand Interact Event
    // Fires on HAND (main) and OFF_HAND — we only act on the off-hand item.
    // Priority is EventPriority.HIGH so we run before WeaponSkillManager (NORMAL).
    // Requires SHIFT + RIGHT CLICK to activate — allows normal shield blocking without shift.
    // ==========================================
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onOffhandInteract(PlayerInteractEvent event) {
        // Right-click air or block only
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player p = event.getPlayer();

        // Shield Bash requires sneaking — normal right-click raises shield via vanilla behavior
        if (!p.isSneaking()) return;

        ItemStack item = event.getItem(); // The item involved in the interaction
        if (item == null || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        // Must be tagged as an off-hand weapon
        if (!meta.getPersistentDataContainer().has(Keys.offhandTag, PersistentDataType.BYTE)) return;
        // Must carry a skill
        if (!meta.getPersistentDataContainer().has(Keys.skill, PersistentDataType.STRING)) return;

        // Block interaction priority (skip if clicking interactable block unless sneaking)
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        }

        // Menu item check: if holding a menu item in main hand, ignore off-hand combat interaction
        ItemStack mainHand = p.getInventory().getItemInMainHand();
        if (isMenuItem(mainHand)) return;

        String skillId = meta.getPersistentDataContainer().get(Keys.skill, PersistentDataType.STRING);
        
        // Priority Handling: If this is the HAND event, but the user has a Priority Off-hand item 
        // in their actual OFF_HAND slot, we should let the OFF_HAND event take precedence 
        // IF the item in HAND is NOT also a priority off-hand item.
        // Actually, if we are in OffhandModule, and we are processing an item with offhandTag, 
        // we should just execute it and cancel.
        
        event.setCancelled(true);

        PlayerModule playerMod = ElpixEdge.getInstance().getModule(PlayerModule.class);
        PlayerProfile profile = playerMod != null ? playerMod.getProfile(p) : null;
        if (profile == null) return;

        // Skill requirement check
        if (playerMod != null && !playerMod.canUseItem(p, item)) {
            profile.setActionBarMessage(ChatColor.DARK_RED + "☠ You do not meet the requirements for this item!", 2000);
            return;
        }

        // GCD check
        long now = System.currentTimeMillis();
        Map<StatType, Double> stats = profile.getStats();
        double gcdReduc = stats.getOrDefault(StatType.GCD_REDUC, 0.0);

        long baseGcd = plugin.getConfig().getLong("game_settings.active_skills_gcd." + skillId.toLowerCase(), 5000L);
        long currentGcd = (long) (baseGcd * (1.0 - gcdReduc));

        if (profile.getGlobalCooldown() > 0 && now < profile.getGlobalCooldown()) {
            double timeLeft = (profile.getGlobalCooldown() - now) / 1000.0;
            profile.setActionBarMessage(ChatColor.RED + "Cooldown: " + String.format("%.1f", timeLeft) + "s", 1000);
            return;
        }

        // Mana check
        int manaCost = meta.getPersistentDataContainer().getOrDefault(Keys.manaCost, PersistentDataType.INTEGER, 10);
        if (profile.getCurrentMana() < manaCost) {
            profile.setActionBarMessage(ChatColor.RED + "Not enough Mana!", 1500);
            return;
        }

        // Dispatch ability
        if (skillId.equalsIgnoreCase("SHIELD_BASH")) {
            executeShieldBash(p, profile, stats, manaCost, currentGcd, now, item);
        }
    }

    // ==========================================
    // Shieldbash Ability
    // Lunge forward — on entity hit: knockback + stun + damage(dmg*2 + def*5)
    // Boss/Elite mobs are immune to stun.
    // On hit: apply player recoil backwards.
    // ==========================================
    private void executeShieldBash(Player p, PlayerProfile profile, Map<StatType, Double> stats,
                                    int manaCost, long gcd, long now, ItemStack item) {
        profile.setGlobalCooldown(now + gcd);
        p.setCooldown(item.getType(), (int) (gcd / 50));
        profile.setCurrentMana(profile.getCurrentMana() - manaCost);
        profile.setActionBarMessage(ChatColor.GOLD + "⚡ Shield Bash!", 500);

        double playerDmg = stats.getOrDefault(StatType.DAMAGE, 1.0);
        double playerDef = stats.getOrDefault(StatType.DEFENSE, 0.0);
        double bashDamage = playerDmg * 2.0 + playerDef * 5.0;

        // Lunge: push player forward using velocity
        Vector lunge = p.getLocation().getDirection().normalize().multiply(LUNGE_SPEED);
        lunge.setY(0.18); // slight upward arc
        p.setVelocity(lunge);

        p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, p.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0);

        // After 3 ticks check collision with nearby entities
        new BukkitRunnable() {
            int checkTick = 0;
            final int maxChecks = 6; // check up to 6 ticks (0.3s) after lunge

            @Override
            public void run() {
                checkTick++;
                if (checkTick > maxChecks || p.isDead()) {
                    cancel();
                    return;
                }

                for (Entity nearby : p.getWorld().getNearbyEntities(p.getLocation(), HIT_RADIUS, HIT_RADIUS, HIT_RADIUS)) {
                    if (!(nearby instanceof LivingEntity)) continue;
                    if (nearby.equals(p)) continue;

                    LivingEntity target = (LivingEntity) nearby;

                    // Apply override damage (bypasses normal combat calculation for this hit)
                    org.bukkit.NamespacedKey overrideKey = new org.bukkit.NamespacedKey(plugin, "override_dmg");
                    p.getPersistentDataContainer().set(overrideKey, PersistentDataType.DOUBLE, bashDamage);
                    target.damage(bashDamage, p);
                    p.getPersistentDataContainer().remove(overrideKey);

                    // Knockback
                    Vector kb = target.getLocation().toVector().subtract(p.getLocation().toVector()).normalize();
                    kb.setY(0.35);
                    target.setVelocity(kb.multiply(1.4));

                    // Stun — only if not Boss or Elite
                    boolean isBoss  = target.getPersistentDataContainer().has(Keys.bossTag,  PersistentDataType.BYTE);
                    boolean isElite = target.getPersistentDataContainer().has(Keys.eliteTag, PersistentDataType.BYTE);
                    if (!isBoss && !isElite) {
                        applyStun(target);
                    }

                    // Visual effects on hit
                    target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.1);
                    p.playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 1.2f);

                    // Recoil: push player backwards
                    Vector recoil = p.getLocation().getDirection().normalize().multiply(-RECOIL_SPEED);
                    recoil.setY(0.1);
                    p.setVelocity(recoil);

                    cancel(); // Only process first hit per lunge
                    return;
                }
            }
        }.runTaskTimer(plugin, 3L, 1L);
    }

    /**
     * Applies a stun effect to a living entity.
     * Stun = SLOWNESS VI + MINING_FATIGUE VI for STUN_TICKS.
     * A PDC byte "entity_stunned" = 1 is set so other systems can check stun state.
     */
    private void applyStun(LivingEntity target) {
        target.getPersistentDataContainer().set(Keys.stunned, PersistentDataType.BYTE, (byte) 1);
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, STUN_TICKS, 5, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, STUN_TICKS, 5, false, false));

        // Remove stun PDC tag after duration
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isDead()) {
                    target.getPersistentDataContainer().remove(Keys.stunned);
                }
            }
        }.runTaskLater(plugin, STUN_TICKS);
    }

    private boolean isMenuItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String skill = item.getItemMeta().getPersistentDataContainer().get(Keys.skill, PersistentDataType.STRING);
        return "OPEN_MENU".equalsIgnoreCase(skill) || "SUPER_ADMIN".equalsIgnoreCase(skill);
    }
}
