package net.elpixedge.core.item;

import net.elpixedge.core.ElpixEdge;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * RecipeManager — Centralized recipe configuration loader.
 *
 * Loads recipes from recipes.yml (primary) or config.yml (backward compatible fallback).
 * Provides recipe unlock checking via collection/skill requirements.
 */
public class RecipeManager {

    private final ElpixEdge plugin;
    private YamlConfiguration recipeCfg;

    public RecipeManager(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    public void loadRecipes() {
        recipeCfg = null;

        // Primary: recipes.yml
        File recipeFile = new File(plugin.getDataFolder(), "recipes.yml");
        if (!recipeFile.exists()) {
            plugin.saveResource("recipes.yml", false);
        }
        if (recipeFile.exists()) {
            recipeCfg = YamlConfiguration.loadConfiguration(recipeFile);
            if (recipeCfg.getConfigurationSection("recipes") != null) {
                int count = recipeCfg.getConfigurationSection("recipes").getKeys(false).size();
                plugin.getLogger().info("RecipeManager — Loaded " + count + " recipe(s) from recipes.yml");
                return;
            }
        }

        // Fallback: config.yml (backward compatibility)
        plugin.getLogger().info("RecipeManager — Falling back to config.yml recipes section.");
        recipeCfg = null;
    }

    /**
     * Returns the recipes ConfigurationSection.
     * Checks recipes.yml first, then falls back to config.yml.
     *
     * @return the "recipes" ConfigurationSection, or null if not found
     */
    public ConfigurationSection getRecipesSection() {
        if (recipeCfg != null) {
            ConfigurationSection sec = recipeCfg.getConfigurationSection("recipes");
            if (sec != null) return sec;
        }
        // Fallback to config.yml
        return plugin.getConfig().getConfigurationSection("recipes");
    }

    public boolean hasUnlockedRecipe(org.bukkit.entity.Player p, String reqStr) {
        if (p.isOp()) return true;
        if (reqStr == null || reqStr.isEmpty() || reqStr.equalsIgnoreCase("none")) return true;
        
        String[] parts = reqStr.split(":");
        if (parts.length >= 3) {
            String kind = parts[0].trim().toLowerCase();
            if (kind.equals("collection") && parts.length == 4) {
                String colKey = parts[2].trim().toLowerCase();
                int reqLvl = Integer.parseInt(parts[3].trim());
                int pLvl = p.getPersistentDataContainer().getOrDefault(new org.bukkit.NamespacedKey(plugin, "collvl_" + colKey), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
                return pLvl >= reqLvl;
            } else if (kind.equals("skill") && parts.length == 3) {
                String skillKey = parts[1].trim().toLowerCase();
                int reqLvl = Integer.parseInt(parts[2].trim());
                int pLvl = p.getPersistentDataContainer().getOrDefault(new org.bukkit.NamespacedKey(plugin, "skill_lvl_" + skillKey), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
                return pLvl >= reqLvl;
            }
        }
        return false;
    }
}
