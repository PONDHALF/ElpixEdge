package net.elpixedge.core.player;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PlayerModule implements Module, Listener {

    private final ElpixEdge plugin;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final StashManager stashManager = new StashManager();
    private BukkitRunnable task;

    public PlayerModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        for (Player p : Bukkit.getOnlinePlayers()) {
            loadProfile(p);
        }

        startGlobalTask();
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        profiles.clear();
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.get(uuid);
    }
    
    public StashManager getStashManager() {
        return stashManager;
    }
    
    public PlayerProfile getProfile(Player player) {
        return profiles.get(player.getUniqueId());
    }

    private void loadProfile(Player player) {
        PlayerProfile profile = new PlayerProfile(player.getUniqueId());
        profiles.put(player.getUniqueId(), profile);
        updatePlayerStats(player); // initial calculate
        profile.setCurrentMana(profile.getStat(StatType.MAX_MANA)); // fill mana on join
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadProfile(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        profiles.remove(event.getPlayer().getUniqueId());
    }

    public Attribute getHealthAttribute() {
        return Attribute.MAX_HEALTH; // Updated for modern Paper
    }

    public void updatePlayerStats(Player p) {
        PlayerProfile profile = profiles.get(p.getUniqueId());
        if (profile == null) return;

        Map<StatType, Double> stats = new EnumMap<>(StatType.class);
        
        // Base Stats
        for (StatType t : StatType.values()) {
            double v = 0.0;
            if (t.name().contains("HEALTH")) v = 20.0;
            else if (t.name().contains("DEFENSE")) v = 2.0;
            else if (t.name().contains("MAX_MANA")) v = 50.0;
            else if (t.name().contains("DAMAGE") && !t.name().contains("MAGIC")) v = 1.0;
            stats.put(t, v);
        }
        stats.put(StatType.DROP_MULT, 1.0);

        // Armor & Weapons
        for (ItemStack i : p.getInventory().getArmorContents()) addStatsFromItem(i, stats);

        // Main-hand: only include if it does NOT have the offhand tag
        ItemStack mainHand = p.getInventory().getItemInMainHand();
        if (mainHand != null && mainHand.hasItemMeta()) {
            boolean isOffhandItem = mainHand.getItemMeta().getPersistentDataContainer()
                    .has(Keys.offhandTag, PersistentDataType.BYTE);
            if (!isOffhandItem) {
                addStatsFromItem(mainHand, stats);
            }
        }

        // Off-hand: only include if the item has the offhand tag (or is any item without the tag — staves etc.)
        ItemStack offHand = p.getInventory().getItemInOffHand();
        if (offHand != null && offHand.hasItemMeta()) {
            boolean isOffhandWeapon = offHand.getItemMeta().getPersistentDataContainer()
                    .has(Keys.offhandTag, PersistentDataType.BYTE);
            // Off-hand items contribute stats when: tagged as offhand weapon (shield etc.)
            // OR any item with magic_dmg_item tag (grimoire, tome)
            boolean hasMagicTag = offHand.getItemMeta().getPersistentDataContainer()
                    .has(Keys.magicDmgItem, PersistentDataType.BYTE);
            if (isOffhandWeapon || hasMagicTag) {
                addStatsFromItem(offHand, stats);
            }
        }

        // Skill Stat Bonuses from progression
        net.elpixedge.core.progression.ProgressionModule progMod = plugin.getModule(net.elpixedge.core.progression.ProgressionModule.class);
        if (progMod != null) {
            org.bukkit.configuration.ConfigurationSection skillsSec = plugin.getConfig().getConfigurationSection("skills");
            if (skillsSec != null) {
                for (String skillName : skillsSec.getKeys(false)) {
                    int skillLvl = progMod.getSkillLevel(p, skillName);
                    org.bukkit.configuration.ConfigurationSection statGain = skillsSec.getConfigurationSection(skillName + ".stat_gain");
                    if (statGain != null) {
                        for (String statKey : statGain.getKeys(false)) {
                            double gainPerLvl = statGain.getDouble(statKey);
                            try {
                                StatType sType = StatType.valueOf(statKey.toUpperCase());
                                stats.put(sType, stats.getOrDefault(sType, 0.0) + (gainPerLvl * skillLvl));
                            } catch (IllegalArgumentException ignored) {}
                        }
                    }
                }
            }
        }

        // Cap GCD
        if (stats.get(StatType.GCD_REDUC) > 0.8) {
            stats.put(StatType.GCD_REDUC, 0.8);
        }

        profile.updateStats(stats);
    }

    private void addStatsFromItem(ItemStack i, Map<StatType, Double> stats) {
        if (i != null && i.hasItemMeta()) {
            org.bukkit.persistence.PersistentDataContainer pdc = i.getItemMeta().getPersistentDataContainer();
            stats.put(StatType.HEALTH,       stats.get(StatType.HEALTH)       + pdc.getOrDefault(net.elpixedge.core.utils.Keys.hp,          PersistentDataType.INTEGER, 0));
            stats.put(StatType.DEFENSE,      stats.get(StatType.DEFENSE)      + pdc.getOrDefault(net.elpixedge.core.utils.Keys.def,         PersistentDataType.INTEGER, 0));
            stats.put(StatType.DAMAGE,       stats.get(StatType.DAMAGE)       + pdc.getOrDefault(net.elpixedge.core.utils.Keys.dmg,         PersistentDataType.DOUBLE,  0.0));
            // magic_dmg from staves (Keys.magicDmg) OR from magicDmgItem tag items (grimoires store it as Keys.magicDmg too)
            stats.put(StatType.MAGIC_DAMAGE, stats.get(StatType.MAGIC_DAMAGE) + pdc.getOrDefault(net.elpixedge.core.utils.Keys.magicDmg,    PersistentDataType.DOUBLE,  0.0));
            stats.put(StatType.CRIT_CHANCE,  stats.get(StatType.CRIT_CHANCE)  + pdc.getOrDefault(net.elpixedge.core.utils.Keys.critChance,  PersistentDataType.DOUBLE,  0.0));
            stats.put(StatType.CRIT_DAMAGE,  stats.get(StatType.CRIT_DAMAGE)  + pdc.getOrDefault(net.elpixedge.core.utils.Keys.critDamage,  PersistentDataType.DOUBLE,  0.0));
            // Grimoire / tome extras
            stats.put(StatType.MANA_REGEN,   stats.get(StatType.MANA_REGEN)   + pdc.getOrDefault(net.elpixedge.core.utils.Keys.manaRegen,   PersistentDataType.DOUBLE,  0.0));
            org.bukkit.NamespacedKey itemMaxManaKey = new org.bukkit.NamespacedKey(net.elpixedge.core.ElpixEdge.getInstance(), "item_max_mana");
            stats.put(StatType.MAX_MANA,     stats.get(StatType.MAX_MANA)     + pdc.getOrDefault(itemMaxManaKey, PersistentDataType.DOUBLE, 0.0));
        }
    }

    private void startGlobalTask() {
        task = new BukkitRunnable() {
            int tickCounter = 0;
            @Override
            public void run() {
                long now = System.currentTimeMillis(); 
                tickCounter++;
                boolean doManaRegen = (tickCounter % 10 == 0); // Every 0.5s
                if (tickCounter % 50 == 0) {
                    stashManager.cleanup();
                }

                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlayerProfile profile = profiles.get(p.getUniqueId());
                    if (profile == null) continue;

                    // Extremely critical fix for "Dead and Alive" bug:
                    // If a player is dead, setting their health to 1.0 (via scaling) will revive them as a ghost.
                    if (p.isDead() || p.getHealth() <= 0) continue;

                    // Compute stats every tick for safety if wearing armor changed? 
                    // Actually, optimizing this by calling updatePlayerStats on Inventory events is better, 
                    // but we'll stick to original logic's frequency safely inside the loop first, or just run it to ensure freshness.
                    updatePlayerStats(p); 

                    // Combo reset
                    if (profile.getLastHitTime() > 0 && now - profile.getLastHitTime() > 2000) {
                        profile.resetComboDamage();
                    }

                    double maxHp = profile.getStat(StatType.HEALTH);
                    double maxMana = profile.getStat(StatType.MAX_MANA);
                    double mana = profile.getCurrentMana();

                    if (doManaRegen && mana < maxMana) {
                        profile.setCurrentMana(Math.min(maxMana, mana + 5.0 + profile.getStat(StatType.MANA_REGEN)));
                    }
                    mana = profile.getCurrentMana();

                    // HP Scaling
                    AttributeInstance hpAttr = p.getAttribute(getHealthAttribute());
                    if (hpAttr != null) {
                        double oldMax = hpAttr.getBaseValue();
                        if (oldMax != maxHp) {
                            double ratio = p.getHealth() / oldMax;
                            double targetHealth = Math.min(maxHp, Math.max(1.0, ratio * maxHp));
                            
                            // To prevent hurt camera shake, lower health first before lowering max health,
                            // or increase max health first before increasing health.
                            if (maxHp > oldMax) {
                                hpAttr.setBaseValue(maxHp);
                                p.setHealth(targetHealth);
                            } else {
                                p.setHealth(targetHealth);
                                hpAttr.setBaseValue(maxHp);
                            }
                        }
                    }
                    p.setHealthScale(20.0);
                    p.setHealthScaled(true);

                    // Action bar middle section — priority: static alert > exp popups > combo damage
                    String activeMsg = profile.getActiveActionBarMessage();
                    String middle = "";

                    if (!activeMsg.isEmpty()) {
                        middle = activeMsg;
                    } else {
                        String popup = profile.buildPopupText();
                        if (!popup.isEmpty()) {
                            middle = popup;
                        } else if (profile.getComboDamage() > 0) {
                            middle = ChatColor.RED + "Combo DMG: " + String.format("%.1f", profile.getComboDamage());
                        }
                    }

                    // Static Layout - doesn't jump wildly
                    // Left(HP) | Middle(If Any) | Right(Mana)
                    String left = ChatColor.RED + "❤ " + (int)p.getHealth() + "/" + (int)maxHp;
                    String right = ChatColor.AQUA + "✎ " + (int)mana + "/" + (int)maxMana;
                    String finalActionBar;
                    
                    if (middle.isEmpty()) {
                        finalActionBar = left + ChatColor.DARK_GRAY + "  ||  " + right;
                    } else {
                        finalActionBar = left + ChatColor.DARK_GRAY + "  |  " + ChatColor.RESET + middle + ChatColor.DARK_GRAY + "  |  " + right;
                    }

                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(finalActionBar));
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 2L); // Run every 2 ticks like original
    }

    public boolean canUseItem(org.bukkit.entity.Player p, org.bukkit.inventory.ItemStack item) {
        if (item == null || !item.hasItemMeta()) return true;
        
        org.bukkit.persistence.PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (pdc.has(net.elpixedge.core.utils.Keys.reqSkill, org.bukkit.persistence.PersistentDataType.STRING)) {
            String reqSkill = pdc.get(net.elpixedge.core.utils.Keys.reqSkill, org.bukkit.persistence.PersistentDataType.STRING);
            int reqLvl = pdc.getOrDefault(net.elpixedge.core.utils.Keys.reqSkillLvl, org.bukkit.persistence.PersistentDataType.INTEGER, 1);
            
            net.elpixedge.core.progression.ProgressionModule progMod = plugin.getModule(net.elpixedge.core.progression.ProgressionModule.class);
            if (progMod != null) {
                int playerLvl = progMod.getSkillLevel(p, reqSkill);
                if (!p.isOp() && playerLvl < reqLvl) {
                    return false;
                }
            }
        }
        return true;
    }
}
