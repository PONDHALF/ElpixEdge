package net.elpixedge.core.item;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.utils.Keys;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;

public class ItemModule implements Module, Listener {

    private final ElpixEdge plugin;
    private final RecipeManager recipeManager;
    private final MagicItemFactory magicFactory;

    public ItemModule(ElpixEdge plugin) {
        this.plugin = plugin;
        this.recipeManager = new RecipeManager(plugin);
        this.magicFactory = new MagicItemFactory(plugin);
    }

    @Override
    public void onEnable() {
        recipeManager.loadRecipes();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("ItemModule enabled successfully.");
    }

    @Override
    public void onDisable() {
        // Nothing special to disable
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    public MagicItemFactory getMagicItemFactory() {
        return magicFactory;
    }

    /** Generate any item type by ID — checks offhand, staff, grimoire, scroll, then custom item. */
    public ItemStack generateAnyItem(String id) {
        if (magicFactory.isOffhandItem(id)) return magicFactory.generateOffhandItem(id);
        if (magicFactory.isMagicStaff(id))  return magicFactory.generateMagicStaff(id);
        if (magicFactory.isGrimoire(id))    return magicFactory.generateGrimoire(id);
        if (magicFactory.isScrollItem(id))  return magicFactory.generateScrollItem(id);
        return generateCustomItem(id);
    }

    public ItemStack generateCustomItem(String id) {
        FileConfiguration conf = plugin.getConfig();
        if (!conf.contains("items." + id)) return null;
        
        ItemStack item = new ItemStack(Material.valueOf(conf.getString("items." + id + ".material", "DIRT")));
        ItemMeta meta = item.getItemMeta();
        
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', conf.getString("items." + id + ".name", id)));
        
        List<String> lore = new ArrayList<>();
        double dmg = conf.getDouble("items." + id + ".damage", 0.0);
        int hp = conf.getInt("items." + id + ".health", 0);
        int def = conf.getInt("items." + id + ".defense", 0);
        double cc = conf.getDouble("items." + id + ".crit_chance", 0.0);
        double cd = conf.getDouble("items." + id + ".crit_damage", 0.0);
        int manaCost = conf.getInt("items." + id + ".mana_cost", 0);

        if (dmg > 0) lore.add(net.elpixedge.core.utils.StatType.DAMAGE.formatItem(dmg));
        if (hp > 0) lore.add(net.elpixedge.core.utils.StatType.HEALTH.formatItem(hp));
        if (def > 0) lore.add(net.elpixedge.core.utils.StatType.DEFENSE.formatItem(def));
        if (cc > 0) lore.add(net.elpixedge.core.utils.StatType.CRIT_CHANCE.formatItem(cc));
        if (cd > 0) lore.add(net.elpixedge.core.utils.StatType.CRIT_DAMAGE.formatItem(cd));
        
        if (!lore.isEmpty()) lore.add("");

        if (conf.contains("items." + id + ".lore")) {
            for (String l : conf.getStringList("items." + id + ".lore")) {
                lore.add(ChatColor.translateAlternateColorCodes('&', l));
            }
        }

        // Apply Stats
        meta.getPersistentDataContainer().set(Keys.customItemId, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(Keys.dmg, PersistentDataType.DOUBLE, dmg);
        meta.getPersistentDataContainer().set(Keys.hp, PersistentDataType.INTEGER, hp);
        meta.getPersistentDataContainer().set(Keys.def, PersistentDataType.INTEGER, def);
        
        if (cc > 0) meta.getPersistentDataContainer().set(Keys.critChance, PersistentDataType.DOUBLE, cc);
        if (cd > 0) meta.getPersistentDataContainer().set(Keys.critDamage, PersistentDataType.DOUBLE, cd);
        meta.getPersistentDataContainer().set(Keys.manaCost, PersistentDataType.INTEGER, manaCost);
        
        if (conf.contains("items." + id + ".cooldown")) {
            meta.getPersistentDataContainer().set(Keys.itemCooldown, PersistentDataType.DOUBLE, conf.getDouble("items." + id + ".cooldown"));
        }
        
        String skill = conf.getString("items." + id + ".skill");
        if (skill != null && !skill.equalsIgnoreCase("NONE")) {
            meta.getPersistentDataContainer().set(Keys.skill, PersistentDataType.STRING, skill.toUpperCase());
        }

        if (conf.contains("items." + id + ".require_skill") && conf.contains("items." + id + ".require_level")) {
            String rSkill = conf.getString("items." + id + ".require_skill").toUpperCase();
            int rLvl = conf.getInt("items." + id + ".require_level");
            meta.getPersistentDataContainer().set(Keys.reqSkill, PersistentDataType.STRING, rSkill);
            meta.getPersistentDataContainer().set(Keys.reqSkillLvl, PersistentDataType.INTEGER, rLvl);

            lore.add("");
            lore.add(ChatColor.DARK_RED + "☠ Requires " + rSkill + " Lv." + rLvl);
        }

        // Custom Model Data implementation as requested
        if (conf.contains("items." + id + ".custom_model_data")) {
            meta.setCustomModelData(conf.getInt("items." + id + ".custom_model_data"));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Creates a named Enchant Book for a specific Unique enchantment.
     * The book carries a PDC tag "enchant_book_id" = enchId so the enchanting
     * table can verify and consume the correct book.
     *
     * @param enchId  the unique enchant ID (e.g. "life_steal_1")
     * @return a ready-to-drop/give ENCHANTED_BOOK ItemStack
     */
    public ItemStack generateEnchantBook(String enchId) {
        org.bukkit.configuration.ConfigurationSection enchSec =
                plugin.getConfig().getConfigurationSection("enchants.unique." + enchId);
        if (enchSec == null) return null;

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta == null) return book;

        String displayName = ChatColor.translateAlternateColorCodes('&', enchSec.getString("name", enchId));
        meta.setDisplayName(displayName + ChatColor.DARK_GRAY + " [Enchant Book]");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + ChatColor.translateAlternateColorCodes('&', enchSec.getString("lore", "")));
        lore.add("");
        lore.add(ChatColor.GOLD + "Use at the Enchanting Table");
        lore.add(ChatColor.DARK_GRAY + "ID: " + enchId);
        meta.setLore(lore);

        // Tag the book with the specific enchant ID for verification
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "enchant_book_id"),
                PersistentDataType.STRING, enchId);

        book.setItemMeta(meta);
        return book;
    }

    public ItemStack createCustomEgg(String mobId, int lvl) {
        ItemStack egg = new ItemStack(Material.ZOMBIE_SPAWN_EGG);
        ItemMeta meta = egg.getItemMeta();
        
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Spawn " + mobId + ChatColor.GRAY + " (Lv." + lvl + ")");
        
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Place this egg to spawn the custom mob");
        meta.setLore(lore);
        
        meta.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, mobId);
        meta.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, lvl);
        
        egg.setItemMeta(meta);
        return egg;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof org.bukkit.entity.Player) {
            VanillaConverter.attemptConvertVanillaItem(e.getItem().getItemStack());
        }
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getCurrentItem() != null) {
            VanillaConverter.attemptConvertVanillaItem(e.getCurrentItem());
        }
    }
}
