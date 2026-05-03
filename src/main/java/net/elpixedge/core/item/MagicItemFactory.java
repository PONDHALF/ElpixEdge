package net.elpixedge.core.item;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * MagicItemFactory — generates off-hand weapons, grimoires, magic staves, and spell scrolls
 * from the config sections: offhand_items, magic_staves, grimoires, scrolls.
 *
 * Each factory method reads data from config and stamps the correct PDC tags
 * so the relevant modules (OffhandModule, MagicModule) can identify and handle the items.
 *
 * Off-hand weapons  → offhand_weapon BYTE tag + skill STRING tag
 * Magic staves      → magic_dmg_item BYTE tag + skill STRING tag
 * Grimoires         → magic_dmg_item BYTE tag + grimoire_slots INTEGER tag
 * Spell Scrolls     → scroll_type STRING tag + scroll_consumable BYTE tag
 */
public class MagicItemFactory {

    private final ElpixEdge plugin;

    public MagicItemFactory(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // Off-hand Weapon (e.g. Knight Shield)
    // ==========================================
    public ItemStack generateOffhandItem(String id) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("offhand_items." + id);
        if (sec == null) return null;

        Material mat = Material.matchMaterial(sec.getString("material", "SHIELD"));
        if (mat == null) mat = Material.SHIELD;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", id));
        meta.setDisplayName(name);

        int hp  = sec.getInt("health",  0);
        int def = sec.getInt("defense", 0);
        int manaCost = sec.getInt("mana_cost", 10);
        String skillId = sec.getString("skill", "NONE");

        List<String> lore = new ArrayList<>();
        if (hp  > 0) lore.add(StatType.HEALTH.formatItem(hp));
        if (def > 0) lore.add(StatType.DEFENSE.formatItem(def));
        if (!lore.isEmpty()) lore.add("");

        for (String line : sec.getStringList("lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);

        // Custom Model Data for resource pack
        if (sec.contains("custom_model_data")) {
            meta.setCustomModelData(sec.getInt("custom_model_data"));
        }

        // PDC tags
        meta.getPersistentDataContainer().set(Keys.offhandTag,  PersistentDataType.BYTE,    (byte) 1);
        meta.getPersistentDataContainer().set(Keys.customItemId, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(Keys.hp,          PersistentDataType.INTEGER, hp);
        meta.getPersistentDataContainer().set(Keys.def,         PersistentDataType.INTEGER, def);
        meta.getPersistentDataContainer().set(Keys.manaCost,    PersistentDataType.INTEGER, manaCost);
        if (!skillId.equalsIgnoreCase("NONE")) {
            meta.getPersistentDataContainer().set(Keys.skill, PersistentDataType.STRING, skillId.toUpperCase());
        }

        item.setItemMeta(meta);
        return item;
    }

    // ==========================================
    // Magic Staff (e.g. Whispering Staff)
    // ==========================================
    public ItemStack generateMagicStaff(String id) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("magic_staves." + id);
        if (sec == null) return null;

        Material mat = Material.matchMaterial(sec.getString("material", "BLAZE_ROD"));
        if (mat == null) mat = Material.BLAZE_ROD;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", id));
        meta.setDisplayName(name);

        double magicDmg = sec.getDouble("magic_damage", 0.0);
        int manaCost    = sec.getInt("mana_cost", 5);
        String skillId  = sec.getString("skill", "NONE");

        List<String> lore = new ArrayList<>();
        if (magicDmg > 0) lore.add(StatType.MAGIC_DAMAGE.formatItem(magicDmg));
        if (!lore.isEmpty()) lore.add("");

        for (String line : sec.getStringList("lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);

        // Custom Model Data for resource pack
        if (sec.contains("custom_model_data")) {
            meta.setCustomModelData(sec.getInt("custom_model_data"));
        }

        // PDC tags — magic staves use magicDmgItem instead of dmg so PlayerModule handles them correctly
        meta.getPersistentDataContainer().set(Keys.magicDmgItem, PersistentDataType.BYTE,    (byte) 1);
        meta.getPersistentDataContainer().set(Keys.magicDmg,     PersistentDataType.DOUBLE,  magicDmg);
        meta.getPersistentDataContainer().set(Keys.customItemId, PersistentDataType.STRING,  id);
        meta.getPersistentDataContainer().set(Keys.manaCost,     PersistentDataType.INTEGER, manaCost);
        if (!skillId.equalsIgnoreCase("NONE")) {
            meta.getPersistentDataContainer().set(Keys.skill, PersistentDataType.STRING, skillId.toUpperCase());
        }

        item.setItemMeta(meta);
        return item;
    }

    // ==========================================
    // Grimoire (e.g. Faded Tome)
    // ==========================================
    public ItemStack generateGrimoire(String id) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("grimoires." + id);
        if (sec == null) return null;

        Material mat = Material.matchMaterial(sec.getString("material", "BUNDLE"));
        if (mat == null) mat = Material.BUNDLE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", id));
        meta.setDisplayName(name);

        double manaRegen = sec.getDouble("mana_regen", 0.0);
        double maxMana   = sec.getDouble("max_mana",   0.0);
        int maxSlots     = sec.getInt("max_spell_slots", 3);

        List<String> lore = new ArrayList<>();
        if (manaRegen > 0) lore.add(StatType.MANA_REGEN.formatItem(manaRegen));
        if (maxMana   > 0) lore.add(StatType.MAX_MANA.formatItem(maxMana));
        if (!lore.isEmpty()) lore.add("");

        for (String line : sec.getStringList("lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        // Initial scroll slot display
        lore.add(ChatColor.GRAY + "Scrolls: 0/" + maxSlots);
        lore.add(ChatColor.DARK_GRAY + "No scrolls stored.");

        meta.setLore(lore);

        // Custom Model Data for resource pack
        if (sec.contains("custom_model_data")) {
            meta.setCustomModelData(sec.getInt("custom_model_data"));
        }

        // Max spell slots enforced by grimoire_slots PDC tag + addScrollToGrimoire() slot check

        // PDC tags
        meta.getPersistentDataContainer().set(Keys.magicDmgItem,  PersistentDataType.BYTE,    (byte) 1);
        meta.getPersistentDataContainer().set(Keys.customItemId,  PersistentDataType.STRING,  id);
        meta.getPersistentDataContainer().set(Keys.manaRegen,     PersistentDataType.DOUBLE,  manaRegen);
        meta.getPersistentDataContainer().set(Keys.grimoireSlots, PersistentDataType.INTEGER, maxSlots);
        meta.getPersistentDataContainer().set(Keys.grimoireActive,PersistentDataType.INTEGER, 0);
        
        // Store mana_regen and max_mana so PlayerModule's addStatsFromItem can pick them up
        org.bukkit.NamespacedKey maxManaKey = new org.bukkit.NamespacedKey(plugin, "item_max_mana");
        meta.getPersistentDataContainer().set(maxManaKey, PersistentDataType.DOUBLE, maxMana);

        item.setItemMeta(meta);
        return item;
    }

    // ==========================================
    // Spell Scroll (e.g. Ashen Spark, Little Phoenix)
    // ==========================================
    public ItemStack generateScrollItem(String id) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("scrolls." + id);
        if (sec == null) return null;

        Material mat = Material.matchMaterial(sec.getString("material", "PAPER"));
        if (mat == null) mat = Material.PAPER;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", id));
        meta.setDisplayName(name);

        boolean consumable = sec.getBoolean("consumable", true);
        int manaCost = sec.getInt("mana_cost", 30);
        String spellType = sec.getString("spell_type", id.toUpperCase());

        List<String> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        if (!lore.isEmpty()) lore.add("");
        lore.add(consumable ? ChatColor.RED + "Consumable" : ChatColor.GREEN + "Reusable");

        meta.setLore(lore);

        // Custom Model Data for resource pack
        if (sec.contains("custom_model_data")) {
            meta.setCustomModelData(sec.getInt("custom_model_data"));
        }

        // PDC tags
        meta.getPersistentDataContainer().set(Keys.scrollType,     PersistentDataType.STRING, spellType.toUpperCase());
        meta.getPersistentDataContainer().set(Keys.scrollConsumable, PersistentDataType.BYTE, consumable ? (byte) 1 : (byte) 0);
        meta.getPersistentDataContainer().set(Keys.manaCost,       PersistentDataType.INTEGER, manaCost);
        meta.getPersistentDataContainer().set(Keys.customItemId,   PersistentDataType.STRING,  id);

        item.setItemMeta(meta);
        return item;
    }

    // ==========================================
    // Helper: does this config section exist?
    // ==========================================
    public boolean isOffhandItem(String id) {
        return plugin.getConfig().contains("offhand_items." + id);
    }
    public boolean isMagicStaff(String id) {
        return plugin.getConfig().contains("magic_staves." + id);
    }
    public boolean isGrimoire(String id) {
        return plugin.getConfig().contains("grimoires." + id);
    }
    public boolean isScrollItem(String id) {
        return plugin.getConfig().contains("scrolls." + id);
    }
}
