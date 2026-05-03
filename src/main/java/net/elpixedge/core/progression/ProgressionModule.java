package net.elpixedge.core.progression;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.player.PlayerProfile;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.utils.Keys;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.UUID;

public class ProgressionModule implements Module, Listener {

    private final ElpixEdge plugin;

    public ProgressionModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("ProgressionModule enabled successfully.");
    }

    @Override
    public void onDisable() {
    }

    public int getSkillLevel(Player p, String skillName) {
        NamespacedKey k = new NamespacedKey(plugin, "skill_lvl_" + skillName.toLowerCase());
        return p.getPersistentDataContainer().getOrDefault(k, PersistentDataType.INTEGER, 0);
    }

    public void addSkillExp(Player p, String skill, double amount) {
        NamespacedKey expKey = new NamespacedKey(plugin, "skill_exp_" + skill.toLowerCase());
        NamespacedKey lvlKey = new NamespacedKey(plugin, "skill_lvl_" + skill.toLowerCase());
        
        double currentExp = p.getPersistentDataContainer().getOrDefault(expKey, PersistentDataType.DOUBLE, 0.0);
        int currentLvl = p.getPersistentDataContainer().getOrDefault(lvlKey, PersistentDataType.INTEGER, 0);
        currentExp += amount;

        double requiredExp = plugin.getConfig().getDouble("leveling.skills.base_exp", 250.0) * Math.pow(plugin.getConfig().getDouble("leveling.skills.multiplier", 1.5), currentLvl);
        
        if (currentExp >= requiredExp) {
            currentExp -= requiredExp; 
            currentLvl++;
            p.getPersistentDataContainer().set(lvlKey, PersistentDataType.INTEGER, currentLvl);
            p.sendMessage(ChatColor.GREEN + "★ " + ChatColor.GOLD + skill.toUpperCase() + ChatColor.GREEN + " Level Up! (" + currentLvl + ")");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

            // Synchronize enchantment levels
            if (skill.equalsIgnoreCase("enchantment")) {
                syncEnchantmentLevels(p, currentLvl);
            }
        }
        
        p.getPersistentDataContainer().set(expKey, PersistentDataType.DOUBLE, currentExp);
        PlayerProfile profile = getPlayerProfile(p);
        if (profile != null) profile.addExpPopup(skill, ChatColor.AQUA.toString(), (int) amount);
    }

    public void addCollectionExp(Player p, String category, String collectionId, double amount) {
        NamespacedKey expKey = new NamespacedKey(plugin, "colexp_" + collectionId.toLowerCase());
        NamespacedKey lvlKey = new NamespacedKey(plugin, "collvl_" + collectionId.toLowerCase());
        
        int maxLevel = plugin.getConfig().getInt("leveling.collections.max_level", 5);
        int currentLvl = p.getPersistentDataContainer().getOrDefault(lvlKey, PersistentDataType.INTEGER, 0);
        
        if (category.equalsIgnoreCase("enchantment")) {
            addSkillExp(p, "Enchantment", amount);
            // Allow the collection to level up as well, do not return here!
        }

        // Do not gain EXP if already at max level
        if (currentLvl >= maxLevel) return;
        
        // Apply EXP multiplier from config
        double expMultiplier = plugin.getConfig().getDouble("leveling.collections.exp_multiplier", 1.0);
        amount *= expMultiplier;
        
        double currentExp = p.getPersistentDataContainer().getOrDefault(expKey, PersistentDataType.DOUBLE, 0.0);
        currentExp += amount;

        double requiredExp = plugin.getConfig().getDouble("leveling.collections.base_exp", 150.0) * Math.pow(plugin.getConfig().getDouble("leveling.collections.multiplier", 1.5), currentLvl);

        String path = "collections." + category.toLowerCase() + "." + collectionId.toUpperCase() + ".name";
        String rawName = plugin.getConfig().getString(path, collectionId.toUpperCase());
        String cleanName = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', rawName));
        String colorOnly = ChatColor.translateAlternateColorCodes('&', rawName).replace(cleanName, "");
        if (colorOnly.isEmpty()) colorOnly = ChatColor.LIGHT_PURPLE.toString();

        if (currentExp >= requiredExp) {
            currentExp -= requiredExp; 
            currentLvl++;
            if (currentLvl >= maxLevel) {
                currentExp = 0; // Reset EXP when hitting max level
            }
            p.getPersistentDataContainer().set(lvlKey, PersistentDataType.INTEGER, currentLvl);
            p.sendMessage(ChatColor.GOLD + "✦ " + colorOnly + cleanName + ChatColor.GOLD + " Level Up! (" + currentLvl + "/" + maxLevel + ")");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.5f);

            // Synchronize enchantment levels
            if (category.equalsIgnoreCase("enchantment")) {
                syncEnchantmentLevels(p, currentLvl);
            }
        }
        
        p.getPersistentDataContainer().set(expKey, PersistentDataType.DOUBLE, currentExp);

        PlayerProfile profile = getPlayerProfile(p);
        if (profile != null) profile.addExpPopup(cleanName, colorOnly, (int) amount);
    }

    /**
     * Synchronizes Enchantment Skill level with all Enchantment Collection levels.
     */
    private void syncEnchantmentLevels(Player p, int targetLevel) {
        // Sync Skill
        NamespacedKey skillLvlKey = new NamespacedKey(plugin, "skill_lvl_enchantment");
        p.getPersistentDataContainer().set(skillLvlKey, PersistentDataType.INTEGER, targetLevel);

        // Sync Collections
        ConfigurationSection enchColSec = plugin.getConfig().getConfigurationSection("collections.enchantment");
        if (enchColSec != null) {
            for (String colId : enchColSec.getKeys(false)) {
                NamespacedKey colLvlKey = new NamespacedKey(plugin, "collvl_" + colId.toLowerCase());
                p.getPersistentDataContainer().set(colLvlKey, PersistentDataType.INTEGER, targetLevel);
            }
        }
    }

    private PlayerProfile getPlayerProfile(Player p) {
        PlayerModule pm = ElpixEdge.getInstance().getModule(PlayerModule.class);
        return pm != null ? pm.getProfile(p) : null;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        Player killer = mob.getKiller();

        // Check last damager tag from CombatModule
        if (killer == null && mob.getPersistentDataContainer().has(Keys.lastDamager, PersistentDataType.STRING)) {
            Player p = Bukkit.getPlayer(UUID.fromString(mob.getPersistentDataContainer().get(Keys.lastDamager, PersistentDataType.STRING)));
            if (p != null && p.isOnline()) killer = p;
        }

        if (killer != null) {
            int mobLvl = mob.getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
            String mobId = mob.getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING) 
                ? mob.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING) 
                : mob.getType().name();

            int combatMultiplier = plugin.getConfig().getInt("skills.combat.custom_targets." + mobId, plugin.getConfig().getInt("skills.combat.default_exp", 5));
            addSkillExp(killer, "Combat", mobLvl * combatMultiplier);

            if (plugin.getConfig().contains("skills.arcane.custom_targets." + mobId)) {
                int arcaneMultiplier = plugin.getConfig().getInt("skills.arcane.custom_targets." + mobId);
                addSkillExp(killer, "Arcane", mobLvl * arcaneMultiplier);
            }

            // Use case-insensitive matching so custom mob IDs (stored lowercase)
            // match config target keys regardless of their case
            ConfigurationSection combatSec = plugin.getConfig().getConfigurationSection("collections.combat");
            if (combatSec != null) {
                for (String colKey : combatSec.getKeys(false)) {
                    ConfigurationSection targetsSec = combatSec.getConfigurationSection(colKey + ".targets");
                    if (targetsSec != null) {
                        for (String targetKey : targetsSec.getKeys(false)) {
                            if (targetKey.equalsIgnoreCase(mobId)) {
                                addCollectionExp(killer, "combat", colKey, mobLvl * targetsSec.getInt(targetKey));
                                break;
                            }
                        }
                    }
                }
            }

            String dropMobId = mobId.toLowerCase();
            PlayerProfile profile = getPlayerProfile(killer);
            double dropMult = profile != null ? profile.getStat(net.elpixedge.core.utils.StatType.DROP_MULT) : 1.0;
            boolean scaleLvl = plugin.getConfig().getBoolean("rare_drop.level_scaling", true);
            double levelScaleFactor = plugin.getConfig().getDouble("rare_drop.level_scale_factor", 0.001);

            ConfigurationSection rareSec = plugin.getConfig().getConfigurationSection("mobs." + dropMobId + ".rare_drops");
            if (rareSec != null) {
                for (String itemKey : rareSec.getKeys(false)) {
                    double baseChance = rareSec.getDouble(itemKey);
                    double finalChance = baseChance * dropMult;
                    if (scaleLvl) finalChance += (mobLvl * levelScaleFactor);
                    if (Math.random() < finalChance) {
                        giveDropItem(killer, itemKey, true, baseChance, finalChance);
                        // EXP reward for rare drop
                        addSkillExp(killer, "Enchantment", 20);
                    }
                }
            }

            ConfigurationSection uniqueSec = plugin.getConfig().getConfigurationSection("mobs." + dropMobId + ".unique_drops");
            if (uniqueSec != null) {
                for (String itemKey : uniqueSec.getKeys(false)) {
                    double uc = uniqueSec.getDouble(itemKey);
                    if (Math.random() < uc) {
                        giveDropItem(killer, itemKey, false, uc, uc);
                        // EXP reward for unique drop
                        addSkillExp(killer, "Enchantment", 50);
                    }
                }
            }
        }
    }

    private void giveDropItem(Player p, String itemId, boolean isRare, double baseChance, double finalChance) {
        net.elpixedge.core.item.ItemModule itemModule = ElpixEdge.getInstance().getModule(net.elpixedge.core.item.ItemModule.class);
        if (itemModule == null) return;
        
        ItemStack dropItem = itemModule.generateCustomItem(itemId);
        if (dropItem != null) {
            if (p.getInventory().firstEmpty() == -1) {
                ElpixEdge.getInstance().getModule(PlayerModule.class).getStashManager().addItem(p, dropItem);
            } else {
                p.getInventory().addItem(dropItem);
            }
            
            String color = isRare ? ChatColor.LIGHT_PURPLE.toString() : ChatColor.GOLD.toString();
            String prefix = isRare ? "RARE" : "UNIQUE";
            String itemName = dropItem.hasItemMeta() && dropItem.getItemMeta().hasDisplayName() ? dropItem.getItemMeta().getDisplayName() : itemId;
            
            double bcPct = baseChance * 100.0;
            double fcPct = finalChance * 100.0;
            String suffix = p.isOp() ? String.format(" §7(%.2f%% \u2192 %.2f%%)", bcPct, fcPct) : String.format(" §7(%.2f%%)", fcPct);
            
            p.sendTitle("", color + prefix + " DROP! " + ChatColor.WHITE + itemName + suffix, 10, 40, 10);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 1.5f);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        String blockId = e.getBlock().getType().name();
        
        if (e.getBlock().getBlockData() instanceof Ageable) {
            Ageable ageable = (Ageable) e.getBlock().getBlockData();
            if (ageable.getAge() != ageable.getMaximumAge()) return; // Don't give exp for unripe crops
        }

        int miningExp = plugin.getConfig().getInt("skills.mining.custom_targets." + blockId, plugin.getConfig().getInt("skills.mining.default_exp", 5));
        ConfigurationSection minSec = plugin.getConfig().getConfigurationSection("collections.mining");
        if (minSec != null) {
            for (String colKey : minSec.getKeys(false)) {
                if (minSec.contains(colKey + ".targets." + blockId)) {
                    addSkillExp(p, "Mining", miningExp);
                    addCollectionExp(p, "mining", colKey, minSec.getInt(colKey + ".targets." + blockId));
                    return;
                }
            }
        }

        int gatExp = plugin.getConfig().getInt("skills.gathering.custom_targets." + blockId, plugin.getConfig().getInt("skills.gathering.default_exp", 5));
        ConfigurationSection gatSec = plugin.getConfig().getConfigurationSection("collections.gathering");
        if (gatSec != null) {
            for (String colKey : gatSec.getKeys(false)) {
                if (gatSec.contains(colKey + ".targets." + blockId)) {
                    addSkillExp(p, "Gathering", gatExp);
                    addCollectionExp(p, "gathering", colKey, gatSec.getInt(colKey + ".targets." + blockId));
                    return;
                }
            }
        }

        int enchExp = plugin.getConfig().getInt("skills.enchantment.custom_targets." + blockId, plugin.getConfig().getInt("skills.enchantment.default_exp", 5));
        ConfigurationSection enchSec = plugin.getConfig().getConfigurationSection("collections.enchantment");
        if (enchSec != null) {
            for (String colKey : enchSec.getKeys(false)) {
                if (enchSec.contains(colKey + ".targets." + blockId)) {
                    addSkillExp(p, "Enchantment", enchExp);
                    addCollectionExp(p, "enchantment", colKey, enchSec.getInt(colKey + ".targets." + blockId));
                    return;
                }
            }
        }
    }
}
