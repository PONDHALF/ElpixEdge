package net.elpixedge.core.combat;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.player.PlayerProfile;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
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
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;

public class WeaponSkillManager implements Module, Listener {

    private final ElpixEdge plugin;

    public WeaponSkillManager(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("WeaponSkillManager enabled successfully.");
    }

    @Override
    public void onDisable() {
    }

    private PlayerProfile getPlayerProfile(Player p) {
        return ElpixEdge.getInstance().getModule(PlayerModule.class).getProfile(p);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();

        if (item != null && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            
            // Skill Requirements Check
            PlayerModule playerMod = ElpixEdge.getInstance().getModule(PlayerModule.class);
            if (playerMod != null && !playerMod.canUseItem(p, item)) {
                PlayerProfile profile = getPlayerProfile(p);
                if (profile != null) {
                    profile.setActionBarMessage(ChatColor.DARK_RED + "☠ You do not meet the requirements to use this item!", 2000);
                }
                event.setCancelled(true);
                return;
            }

            // Skill Usage
            if (meta.getPersistentDataContainer().has(Keys.skill, PersistentDataType.STRING)) {
                String skill = meta.getPersistentDataContainer().get(Keys.skill, PersistentDataType.STRING);

                if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

                // GUI skill openers
                if (skill.equalsIgnoreCase("OPEN_MENU")) {
                    event.setCancelled(true);
                    net.elpixedge.core.gui.GuiModule gui = ElpixEdge.getInstance().getModule(net.elpixedge.core.gui.GuiModule.class);
                    if (gui != null) gui.openQuickMenu(p);
                    return;
                }
                if (skill.equalsIgnoreCase("SUPER_ADMIN")) {
                    event.setCancelled(true);
                    if (p.isOp()) {
                        net.elpixedge.core.gui.GuiModule gui = ElpixEdge.getInstance().getModule(net.elpixedge.core.gui.GuiModule.class);
                        if (gui != null) gui.openSuperAdminMenu(p);
                    } else {
                        p.sendMessage(ChatColor.RED + "You do not have permission.");
                    }
                    return;
                }

                // Prevent activating combat skills when interacting with blocks
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    if (event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) {
                        return; // Let the block interact happen natively
                    }
                }

                // Check for Priority Off-hand items. If offhand has an ability, let it handle the event instead!
                ItemStack offhand = p.getInventory().getItemInOffHand();
                if (offhand != null && offhand.hasItemMeta()) {
                    org.bukkit.persistence.PersistentDataContainer pdc = offhand.getItemMeta().getPersistentDataContainer();
                    if (pdc.has(Keys.offhandTag, PersistentDataType.BYTE) || pdc.has(Keys.magicDmgItem, PersistentDataType.BYTE) || pdc.has(Keys.scrollType, PersistentDataType.STRING)) {
                        return; // Defer to OffhandModule or MagicModule
                    }
                }

                // Skip executing (and consuming mana/GCD) if it's a skill handled by other modules
                if (skill.equalsIgnoreCase("SHIELD_BASH") || skill.equalsIgnoreCase("ARCANE_BULLET") 
                    || skill.equalsIgnoreCase("ASHEN_SPARK") || skill.equalsIgnoreCase("LITTLE_PHOENIX")) {
                    return;
                }

                PlayerProfile profile = getPlayerProfile(p);
                if (profile == null) return;
                
                long now = System.currentTimeMillis();
                Map<StatType, Double> stats = profile.getStats();
                double maxMana = stats.getOrDefault(StatType.MAX_MANA, 50.0);
                double gcdReduc = stats.getOrDefault(StatType.GCD_REDUC, 0.0);

                long baseGcd = plugin.getConfig().getLong("game_settings.active_skills_gcd." + skill.toLowerCase(), 1500L);
                long currentGcd = (long) (baseGcd * (1.0 - gcdReduc));

                if (profile.getGlobalCooldown() > 0) {
                    long expireTime = profile.getGlobalCooldown();
                    if (now < expireTime) {
                        double timeLeft = (expireTime - now) / 1000.0;
                        profile.setActionBarMessage(ChatColor.RED + "Cooldown: " + String.format("%.1f", timeLeft) + "s", 1000);
                        return;
                    }
                }

                int cost = meta.getPersistentDataContainer().getOrDefault(Keys.manaCost, PersistentDataType.INTEGER, 10);
                double mana = profile.getCurrentMana();

                if (mana < cost) {
                    profile.setActionBarMessage(ChatColor.RED + "Not enough Mana!", 1500);
                    return;
                }

                profile.setGlobalCooldown(now + currentGcd);
                p.setCooldown(item.getType(), (int) (currentGcd / 50));
                profile.setActionBarMessage(ChatColor.YELLOW + "✦ Used " + skill + "!", 2000);

                if (skill.equalsIgnoreCase("TELEPORT")) {
                    event.setCancelled(true);
                    Location eyeLoc = p.getEyeLocation();
                    Vector direction = eyeLoc.getDirection().normalize();
                    RayTraceResult ray = p.getWorld().rayTraceBlocks(eyeLoc, direction, 8.0, FluidCollisionMode.NEVER);
                    Location targetLoc = (ray != null && ray.getHitBlock() != null) ?
                            ray.getHitPosition().toLocation(p.getWorld()).subtract(direction.multiply(0.5)) : eyeLoc.add(direction.multiply(8.0));
                    targetLoc.setY(targetLoc.getY() + 0.1); 
                    targetLoc.setYaw(p.getLocation().getYaw()); 
                    targetLoc.setPitch(p.getLocation().getPitch());

                    profile.setCurrentMana(mana - cost);
                    p.teleport(targetLoc);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
                    p.spawnParticle(Particle.PORTAL, p.getLocation(), 40, 0.2, 0.5, 0.2, 0.1);
                } else if (skill.equalsIgnoreCase("HEAL")) {
                    event.setCancelled(true);
                    PlayerModule pdm = ElpixEdge.getInstance().getModule(PlayerModule.class);
                    Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
                    double maxHp = p.getAttribute(maxHpAttr) != null ? p.getAttribute(maxHpAttr).getBaseValue() : 20.0;
                    p.setHealth(Math.min(maxHp, p.getHealth() + 20));

                    profile.setCurrentMana(mana - cost);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_BURP, 1f, 1f);
                } else if (skill.equalsIgnoreCase("AOE_SLASH")) {
                    event.setCancelled(true);
                    // AOE_SLASH deals pure stat Damage * 5 (no crit, no weapon config lookup)
                    double statDamage = stats.getOrDefault(StatType.DAMAGE, 1.0);
                    double aoeDmg = statDamage * 5.0;
                    Vector forward = p.getLocation().getDirection().normalize();
                    Location center = p.getLocation().add(0, 1, 0);

                    int hitCount = 0;
                    org.bukkit.NamespacedKey overrideKey = new org.bukkit.NamespacedKey(plugin, "override_dmg");
                    p.getPersistentDataContainer().set(overrideKey, PersistentDataType.DOUBLE, aoeDmg);
                    
                    for (Entity nearby : p.getWorld().getNearbyEntities(center, 5, 3, 5)) {
                        if (!(nearby instanceof LivingEntity)) continue;
                        if (nearby.equals(p)) continue;

                        // Forward arc check: dot product > 0 means entity is in front
                        Vector toEntity = nearby.getLocation().toVector().subtract(center.toVector()).normalize();
                        if (forward.dot(toEntity) < 0) continue;

                        LivingEntity target = (LivingEntity) nearby;
                        target.damage(aoeDmg, p);
                        hitCount++;
                    }
                    
                    p.getPersistentDataContainer().remove(overrideKey);

                    // Visual feedback
                    p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 8, 1.5, 0.5, 1.5, 0);
                    p.getWorld().spawnParticle(Particle.ENCHANT, center, 25, 1.5, 0.5, 1.5, 0.1);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);

                    profile.setCurrentMana(mana - cost);
                    profile.setActionBarMessage(
                        ChatColor.DARK_PURPLE + "\u2726 Void Slash! " + ChatColor.RED + "Hit " + hitCount + " target(s)!", 2000
                    );
                }
            }
        }
    }
}
