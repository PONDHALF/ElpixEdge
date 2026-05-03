package net.elpixedge.core.gui;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.utils.StatType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Due to complex interconnected logic, GuiModule acts as the single listener but logic is broken down.
public class GuiModule implements Module, Listener {

    private final ElpixEdge plugin;
    // Crafter layout: 3x3 grid centered left, result on right
    // Row 1: 11,12,13 Row 2: 20,21,22 Row 3: 29,30,31 Result: 15 (right of grid)
    public final int[] CRAFT_SLOTS = { 11, 12, 13, 20, 21, 22, 29, 30, 31 };
    public final int RESULT_SLOT = 15;
    public final int ENCHANT_ITEM_SLOT = 20; // slot for weapon in enchanting UI
    private final HashMap<UUID, Integer> selectedEditorLvl = new HashMap<>();
    // Stores the weapon being enchanted so the enchanting screen can reference it
    private final HashMap<UUID, ItemStack> pendingEnchantWeapon = new HashMap<>();
    // Current page offset per player per category: key = uuid+cat, value = page
    // index (0-based)
    private final HashMap<String, Integer> enchPageMap = new HashMap<>();

    public GuiModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("GuiModule enabled successfully.");
    }

    @Override
    public void onDisable() {
    }

    public ItemStack createGuiItem(Material mat, String name, String actionInfo, String... loreLines) {
        return createGuiItem(mat, name, actionInfo, Arrays.asList(loreLines));
    }

    public ItemStack createGuiItem(Material mat, String name, String actionInfo, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            if (actionInfo != null)
                meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING, actionInfo);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String getProgressBar(double current, double max, int totalBars) {
        float percent = (float) (current / max);
        if (percent > 1.0f)
            percent = 1.0f;
        int progressBars = (int) (totalBars * percent);
        int leftOver = (totalBars - progressBars);

        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.DARK_GRAY).append("[");
        sb.append(ChatColor.GREEN);
        for (int i = 0; i < progressBars; i++) {
            sb.append("■");
        }
        sb.append(ChatColor.GRAY);
        for (int i = 0; i < leftOver; i++) {
            sb.append("■");
        }
        sb.append(ChatColor.DARK_GRAY).append("]");
        return sb.toString();
    }

    // ==========================================
    // Menus
    // ==========================================

    public void openQuickMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("main"), 27, ChatColor.DARK_GRAY + "RPG Menu");
        inv.setItem(11, createGuiItem(Material.BOOK, ChatColor.AQUA + "Skills & Collections", "open:skills",
                ChatColor.GRAY + "View your progress"));

        ItemStack head = createGuiItem(Material.PLAYER_HEAD, ChatColor.GREEN + "Your Profile & Stats", "open:status",
                ChatColor.GRAY + "Check your current attributes");
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(p);
            head.setItemMeta(meta);
        }
        inv.setItem(15, head);

        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    public void openStatusMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("status"), 27,
                ChatColor.DARK_GRAY + "Your Attributes");
        net.elpixedge.core.player.PlayerProfile profile = plugin.getModule(net.elpixedge.core.player.PlayerModule.class)
                .getProfile(p);
        Map<StatType, Double> stats = profile.getStats();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(p);
            meta.setDisplayName(ChatColor.GOLD + p.getName() + "'s Stats");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "--------------------");
            for (StatType type : StatType.values()) {
                lore.add(type.formatFull(stats.getOrDefault(type, 0.0)));
            }
            lore.add(ChatColor.GRAY + "--------------------");
            meta.setLore(lore);
            head.setItemMeta(meta);
        }

        inv.setItem(13, head);
        inv.setItem(15, createGuiItem(Material.NETHER_STAR, ChatColor.AQUA + "Skill Bonus Breakdown",
                "open:status_skills", ChatColor.GRAY + "See how much stats you get from skills"));
        inv.setItem(22, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "open:quick"));

        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openSuperAdminMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("super_admin"), 27,
                ChatColor.DARK_RED + "Super Admin Control");
        inv.setItem(10, createGuiItem(Material.SPAWNER, ChatColor.LIGHT_PURPLE + "Monster Spawner", "admin:menu:mobs",
                ChatColor.GRAY + "Get custom mob spawn eggs"));
        inv.setItem(12, createGuiItem(Material.DIAMOND_SWORD, ChatColor.GOLD + "Item Spawner", "admin:menu:items",
                ChatColor.GRAY + "Spawn all items from config"));
        inv.setItem(14, createGuiItem(Material.BLAZE_ROD, ChatColor.LIGHT_PURPLE + "Magic Items", "admin:menu:magic",
                ChatColor.GRAY + "Spawn staves, grimoires, shields, scrolls"));
        inv.setItem(16, createGuiItem(Material.LAVA_BUCKET, ChatColor.DARK_RED + "Reset Progress", "admin:reset",
                ChatColor.GRAY + "WARNING: Reset your level and stats"));
        inv.setItem(20, createGuiItem(Material.NAME_TAG, ChatColor.AQUA + "Tag Manager", "admin:menu:tags",
                ChatColor.GRAY + "Enable/Disable your tags"));
        inv.setItem(22, createGuiItem(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "EXP Editor", "admin:menu:exp",
                ChatColor.GRAY + "Add EXP to skills and collections"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openAdminExpMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("admin_exp"), 36,
                ChatColor.DARK_GREEN + "Admin: EXP Editor");

        // Skill EXP row
        inv.setItem(10, createGuiItem(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Skills: +100 EXP",
                "admin:addexp:skills:100"));
        inv.setItem(11, createGuiItem(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Skills: +1000 EXP",
                "admin:addexp:skills:1000"));
        inv.setItem(12, createGuiItem(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Skills: +10000 EXP",
                "admin:addexp:skills:10000"));
        inv.setItem(13, createGuiItem(Material.EXPERIENCE_BOTTLE, ChatColor.GREEN + "Skills: +100000 EXP",
                "admin:addexp:skills:100000"));

        // Collection EXP row
        inv.setItem(19, createGuiItem(Material.LAPIS_LAZULI, ChatColor.AQUA + "Collections: +100 EXP",
                "admin:addexp:collections:100"));
        inv.setItem(20, createGuiItem(Material.LAPIS_LAZULI, ChatColor.AQUA + "Collections: +1000 EXP",
                "admin:addexp:collections:1000"));
        inv.setItem(21, createGuiItem(Material.LAPIS_LAZULI, ChatColor.AQUA + "Collections: +10000 EXP",
                "admin:addexp:collections:10000"));
        inv.setItem(22, createGuiItem(Material.LAPIS_LAZULI, ChatColor.AQUA + "Collections: +100000 EXP",
                "admin:addexp:collections:100000"));

        inv.setItem(31, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:main"));

        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openAdminTagMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("admin_tags"), 54, ChatColor.DARK_GREEN + "Admin: Manage Tags");
        
        java.util.Set<String> allTags = new java.util.HashSet<>();
        // Load tags from QuestTriggerListener's config manager (reads quest_triggers.yml)
        net.elpixedge.core.tag.QuestTriggerListener triggerListener = plugin.getModule(net.elpixedge.core.tag.QuestTriggerListener.class);
        if (triggerListener != null && triggerListener.getConfigManager() != null) {
            for (net.elpixedge.core.tag.QuestConfigManager.QuestTrigger trigger : triggerListener.getConfigManager().getAllTriggers()) {
                allTags.addAll(trigger.requiredTags);
                allTags.addAll(trigger.addTags);
                allTags.addAll(trigger.removeTags);
            }
        }
        
        net.elpixedge.core.tag.TagManager tagMod = plugin.getModule(net.elpixedge.core.tag.TagManager.class);
        if (tagMod != null) {
            java.util.Set<String> playerTags = tagMod.getTags(p);
            allTags.addAll(playerTags);
        }

        // Load tags from NpcVisibilityManager
        net.elpixedge.core.instance.NpcVisibilityManager npcVis = plugin.getModule(net.elpixedge.core.instance.NpcVisibilityManager.class);
        if (npcVis != null) {
            for (net.elpixedge.core.instance.NpcVisibilityManager.NpcVisibilityRule rule : npcVis.getAllRules()) {
                if (rule.hideIfHasTags != null) allTags.addAll(rule.hideIfHasTags);
                if (rule.showOnlyIfHasTags != null) allTags.addAll(rule.showOnlyIfHasTags);
            }
        }
        
        java.util.List<String> cleanedTags = new java.util.ArrayList<>();
        for(String t : allTags) {
            String clean = t.startsWith("!") ? t.substring(1) : t;
            if(!cleanedTags.contains(clean)) cleanedTags.add(clean);
        }
        java.util.Collections.sort(cleanedTags);

        int slot = 0;
        for (String tag : cleanedTags) {
            if (slot >= 45) break;
            boolean has = tagMod != null && tagMod.hasTag(p, tag);
            Material mat = has ? Material.LIME_DYE : Material.GRAY_DYE;
            String name = (has ? ChatColor.GREEN : ChatColor.GRAY) + tag;
            inv.setItem(slot++, createGuiItem(mat, name, "admin:toggletag:" + tag,
                    ChatColor.GRAY + "Status: " + (has ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"),
                    ChatColor.YELLOW + "Click to toggle"));
        }
        
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:main"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openMobEditor(Player p) {
        int currentLvl = selectedEditorLvl.getOrDefault(p.getUniqueId(), 1);
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("mob_editor"), 54,
                ChatColor.DARK_PURPLE + "Admin: Mob Spawner");

        // Level display item
        inv.setItem(4, createGuiItem(Material.EXPERIENCE_BOTTLE,
                ChatColor.GOLD + "Target Level: " + ChatColor.WHITE + currentLvl,
                "none",
                ChatColor.GRAY + "Select a level preset below to update the level.",
                ChatColor.GRAY + "Level affects Mob HP and Damage."));

        // Level Presets (1, 10, 20, 30, 40, 50, 60)
        int[] presetLevels = { 1, 10, 20, 30, 40, 50, 60 };
        int[] levelSlots = { 10, 11, 12, 13, 14, 15, 16 };
        for (int i = 0; i < presetLevels.length; i++) {
            int lvlValue = presetLevels[i];
            Material mat = (lvlValue == currentLvl) ? Material.LIME_STAINED_GLASS_PANE
                    : Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(levelSlots[i], createGuiItem(mat,
                    ChatColor.YELLOW + "Level " + lvlValue,
                    "setlvl:" + lvlValue,
                    ChatColor.GRAY + "Set spawner level to " + lvlValue));
        }

        // +/- 10 Level Buttons
        inv.setItem(18, createGuiItem(Material.RED_STAINED_GLASS_PANE, ChatColor.RED + "-10 Levels", "addlvl:-10",
                ChatColor.GRAY + "Decrease level by 10"));
        inv.setItem(26, createGuiItem(Material.LIME_STAINED_GLASS_PANE, ChatColor.GREEN + "+10 Levels", "addlvl:10",
                ChatColor.GRAY + "Increase level by 10"));

        // Mob Spawn Egg Buttons
        List<String> mobTypes = new ArrayList<>();
        ConfigurationSection mobsSec = plugin.getConfig().getConfigurationSection("mobs");
        if (mobsSec != null) {
            mobTypes.addAll(mobsSec.getKeys(false));
        }

        int slotOffset = 28;
        for (int i = 0; i < mobTypes.size() && slotOffset <= 44; i++) {
            String type = mobTypes.get(i);
            String baseEntStr = plugin.getConfig().getString("mobs." + type.toLowerCase() + ".base_entity", type);
            
            Material eggMat = Material.ZOMBIE_SPAWN_EGG;
            try {
                eggMat = Material.valueOf(baseEntStr.toUpperCase() + "_SPAWN_EGG");
            } catch (Exception ignored) {
            }

            inv.setItem(slotOffset++, createGuiItem(eggMat,
                    ChatColor.LIGHT_PURPLE + "Get Spawn Egg: " + ChatColor.WHITE + type,
                    "getegg:" + type + ":" + currentLvl,
                    ChatColor.GRAY + "Gives a Lv." + currentLvl + " " + type + " custom spawn egg."));
        }

        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:main"));

        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openAdminItemSpawner(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("admin_items"), 54,
                ChatColor.DARK_RED + "Admin: Item Spawner");
        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        net.elpixedge.core.item.MagicItemFactory factory = itemMod.getMagicItemFactory();
        int slot = 0;

        // Custom items
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("items");
        if (items != null) {
            for (String itemKey : items.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack display = itemMod.generateCustomItem(itemKey);
                if (display != null) {
                    ItemMeta meta = display.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getitem:" + itemKey);
                        display.setItemMeta(meta);
                    }
                    inv.setItem(slot++, display);
                }
            }
        }

        // Off-hand weapons (e.g. Knight Shield) — moved here from Magic Items
        ConfigurationSection offhandSec = plugin.getConfig().getConfigurationSection("offhand_items");
        if (offhandSec != null) {
            for (String id : offhandSec.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack item = factory.generateOffhandItem(id);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getmagic:offhand:" + id);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        }

        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:main"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openAdminEnchantBooks(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("admin_enchant_books"), 54,
                ChatColor.DARK_RED + "Admin: Enchant Books");
        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        int slot = 0;
        String[] cats = { "basic", "unique" };
        for (String cat : cats) {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("enchants." + cat);
            if (sec == null)
                continue;
            for (String enchId : sec.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack book = itemMod.generateEnchantBook(enchId);
                if (book != null) {
                    ItemMeta meta = book.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getbook:" + enchId);
                        book.setItemMeta(meta);
                    }
                    inv.setItem(slot++, book);
                }
            }
        }
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:magic"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    // Admin Magic Items menu — staves, grimoires, scrolls, enchant books
    public void openAdminMagicItems(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("admin_magic"), 54,
                ChatColor.DARK_RED + "Admin: Magic Items");
        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        net.elpixedge.core.item.MagicItemFactory factory = itemMod.getMagicItemFactory();
        int slot = 0;

        // 1. Magic Staves
        ConfigurationSection stavesSec = plugin.getConfig().getConfigurationSection("magic_staves");
        if (stavesSec != null) {
            for (String id : stavesSec.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack item = factory.generateMagicStaff(id);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getmagic:staff:" + id);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        }

        // 2. Grimoires
        ConfigurationSection grimoireSec = plugin.getConfig().getConfigurationSection("grimoires");
        if (grimoireSec != null) {
            for (String id : grimoireSec.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack item = factory.generateGrimoire(id);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getmagic:grimoire:" + id);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        }

        // 3. Scrolls
        ConfigurationSection scrollSec = plugin.getConfig().getConfigurationSection("scrolls");
        if (scrollSec != null) {
            for (String id : scrollSec.getKeys(false)) {
                if (slot >= 45)
                    break;
                ItemStack item = factory.generateScrollItem(id);
                if (item != null) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "admin:getmagic:scroll:" + id);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        }

        // Button to open Enchantment Books sub-menu — moved here from Item Spawner
        inv.setItem(48, createGuiItem(Material.ENCHANTED_BOOK, ChatColor.LIGHT_PURPLE + "Enchant Books",
                "admin:menu:enchbooks", ChatColor.GRAY + "Click to open Enchanting Books menu"));
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "admin:menu:main"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openCustomCrafter(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("crafter"), 54,
                ChatColor.DARK_GRAY + "Crafting Bench");
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++)
            inv.setItem(i, glass);
        // Clear craft slots for player items
        for (int slot : CRAFT_SLOTS)
            inv.setItem(slot, null);
        // Clear result slot
        inv.setItem(RESULT_SLOT, null);
        // Arrow pointing from grid to result
        inv.setItem(14, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Result", "none"));
        inv.setItem(49, createGuiItem(Material.BOOK, ChatColor.GOLD + "Recipe Guide", "crafter:guide",
                ChatColor.GRAY + "Click to view unlocked recipes!"));
        p.openInventory(inv);
    }

    public void openGuideBook(Player p) {
        openGuideBook(p, "crafter");
    }

    // backContext: "crafter" or "rewards:skill:<cat>:<page>" or
    // "rewards:col:<cat>:<id>:<page>"
    public void openGuideBook(Player p, String backContext) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("guide", backContext), 54,
                ChatColor.DARK_GRAY + "Recipe Guide");
        net.elpixedge.core.item.RecipeManager rm521 = plugin.getModule(net.elpixedge.core.item.ItemModule.class).getRecipeManager();
        ConfigurationSection recipes = rm521.getRecipesSection();
        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        int slot = 10;
        if (recipes != null) {
            for (String recipeId : recipes.getKeys(false)) {
                if (slot % 9 == 8)
                    slot += 2;
                if (slot >= 45)
                    break;
                String reqStr = recipes.getString(recipeId + ".requires");
                ItemStack displayItem = resolveRecipeOutputDisplay(itemMod, recipeId);
                if (displayItem == null) {
                    slot++;
                    continue;
                }

                if (itemMod.getRecipeManager().hasUnlockedRecipe(p, reqStr)) {
                    ItemMeta m = displayItem.getItemMeta();
                    if (m != null) {
                        List<String> lore = m.hasLore() ? m.getLore() : new ArrayList<>();
                        lore.add("");
                        lore.add(ChatColor.YELLOW + "Click to view recipe!");
                        m.setLore(lore);
                        // Encode backContext into the guide:view action so RecipeView knows where to
                        // return
                        m.getPersistentDataContainer().set(Keys.guiAction, PersistentDataType.STRING,
                                "guide:view:" + recipeId + "|" + backContext);
                        displayItem.setItemMeta(m);
                    }
                    inv.setItem(slot, displayItem);
                } else {
                    inv.setItem(slot, createGuiItem(Material.BARRIER, ChatColor.RED + "Unknown Recipe", "none",
                            ChatColor.GRAY + "Unlock requirement:", ChatColor.DARK_RED + reqStr));
                }
                slot++;
            }
        }
        // Back button returns to backContext
        String backAction = backContext.equals("crafter") ? "guide:back" : "guide:backto:" + backContext;
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", backAction));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openRecipeView(Player p, String recipeId) {
        openRecipeView(p, recipeId, "guide");
    }

    // backContext: "guide" = go back to guide book, or "rewards:skill:<cat>:<pg>"
    // etc.
    public void openRecipeView(Player p, String recipeId, String backContext) {
        String[] ids = new String[] { recipeId };
        int index = 0;
        if (recipeId.contains("@")) {
            String[] parts = recipeId.split("@");
            ids = parts[0].split(",");
            if (parts.length > 1) {
                index = Integer.parseInt(parts[1]);
            }
        } else if (recipeId.contains(",")) {
            ids = recipeId.split(",");
        }

        String currentRecipeId = ids[index];
        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("recipe", backContext), 54,
                ChatColor.DARK_GRAY + "Recipe: " + currentRecipeId.replace("_", " "));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++)
            inv.setItem(i, glass);

        net.elpixedge.core.item.RecipeManager rm596 = itemMod.getRecipeManager();
        ConfigurationSection recipesRoot596 = rm596.getRecipesSection();
        ConfigurationSection recipe = recipesRoot596 != null ? recipesRoot596.getConfigurationSection(currentRecipeId) : null;
        if (recipe != null) {
            List<String> shape = recipe.getStringList("shape");
            ConfigurationSection ingredients = recipe.getConfigurationSection("ingredients");
            for (int row = 0; row < 3; row++) {
                String line = shape.size() > row ? shape.get(row) : "   ";
                while (line.length() < 3)
                    line += " ";
                for (int col = 0; col < 3; col++) {
                    char symbol = line.charAt(col);
                    int invSlot = CRAFT_SLOTS[(row * 3) + col];
                    if (symbol != ' ' && ingredients != null) {
                        String reqItemId = ingredients.getString(String.valueOf(symbol), "DIRT");
                        // Support enchant_book:<enchId> as ingredient
                        ItemStack displayItem = null;
                        if (reqItemId.startsWith("enchant_book:")) {
                            displayItem = itemMod.generateEnchantBook(reqItemId.substring("enchant_book:".length()));
                        } else {
                            displayItem = itemMod.generateCustomItem(reqItemId);
                            if (displayItem == null && Material.matchMaterial(reqItemId) != null)
                                displayItem = new ItemStack(Material.valueOf(reqItemId));
                        }
                        if (displayItem != null)
                            inv.setItem(invSlot, displayItem);
                    } else {
                        inv.setItem(invSlot, new ItemStack(Material.AIR));
                    }
                }
            }
            // Result slot: support enchant_book output
            inv.setItem(RESULT_SLOT, resolveRecipeOutputDisplay(itemMod, currentRecipeId));
        }

        // Back action: go to guide, or back to the rewards page directly
        String backAction = backContext.equals("guide") ? "crafter:guide" : "recipe:backto:" + backContext;
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", backAction));

        // Paging for multiple recipes
        if (index > 0) {
            String prevAction = "recipefrom:" + String.join(",", ids) + "@" + (index - 1) + "|" + backContext;
            inv.setItem(45, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Previous Recipe", prevAction));
        }
        if (index < ids.length - 1) {
            String nextAction = "recipefrom:" + String.join(",", ids) + "@" + (index + 1) + "|" + backContext;
            inv.setItem(53, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Next Recipe", nextAction));
        }

        p.openInventory(inv);
    }

    /**
     * Resolve the display ItemStack for a recipe's output.
     * Recipes with output_type=enchant_book use generateEnchantBook(recipeId).
     * All others use generateCustomItem(recipeId).
     */
    private ItemStack resolveRecipeOutputDisplay(net.elpixedge.core.item.ItemModule itemMod, String recipeId) {
        ConfigurationSection recipesRoot = itemMod.getRecipeManager().getRecipesSection();
        String outputType = "custom_item";
        String enchId = recipeId;
        if (recipesRoot != null) {
            outputType = recipesRoot.getString(recipeId + ".output_type", "custom_item");
            enchId = recipesRoot.getString(recipeId + ".output_enchant_id", recipeId);
        }
        if (outputType.equals("enchant_book")) {
            return itemMod.generateEnchantBook(enchId);
        }
        return itemMod.generateCustomItem(recipeId);
    }

    // ==========================================
    // Enchanting Table — single inventory, two side columns, 4 slots each
    // Layout: slot 22 = item input.
    // Left col: header=0, prev=1, slots={10,19,28,37}, next=46
    // Right col: header=8, prev=7, slots={16,25,34,43}, next=52
    // Close = 49
    // ==========================================
    private static final int ENCH_ITEM_SLOT_MAIN = 22;
    // Basic enchant display slots (column 1, left side) — 4 slots
    private static final int[] BASIC_SLOTS = { 10, 19, 28, 37 };
    // Unique enchant display slots (column 2, right side) — 4 slots
    private static final int[] UNIQUE_SLOTS = { 16, 25, 34, 43 };
    private static final int BASIC_PREV = 1; // above basic col
    private static final int BASIC_NEXT = 46; // below basic col
    private static final int UNIQUE_PREV = 7; // above unique col
    private static final int UNIQUE_NEXT = 52; // below unique col
    private static final int ENCH_CLOSE = 49;
    private static final int ENCHANTS_PER_PAGE = 4; // 4 slots per column

    public void openEnchantingTable(Player p, String category) {
        openEnchantingMain(p);
    }

    public void openEnchantingMain(Player p) {
        ItemStack weapon = pendingEnchantWeapon.get(p.getUniqueId());
        buildEnchantingInventory(p, weapon);
    }

    // Build / refresh the single enchanting inventory
    private void buildEnchantingInventory(Player p, ItemStack weapon) {
        net.elpixedge.core.progression.ProgressionModule progMod = plugin
                .getModule(net.elpixedge.core.progression.ProgressionModule.class);
        int enchSkillLvl = (progMod != null) ? progMod.getSkillLevel(p, "enchantment") : 0;

        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("enchanting"), 54,
                ChatColor.LIGHT_PURPLE + "\u2726 Enchanting Table");
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++)
            inv.setItem(i, glass);

        // Centre item slot — cleared for player to place weapon
        if (weapon != null && weapon.getType() != Material.AIR) {
            inv.setItem(ENCH_ITEM_SLOT_MAIN, weapon.clone());
        } else {
            inv.setItem(ENCH_ITEM_SLOT_MAIN, null);
        }

        // Column headers (row 0)
        inv.setItem(0, createGuiItem(Material.ENCHANTED_BOOK,
                ChatColor.WHITE + "\u26a1 Basic", "none",
                ChatColor.GRAY + "Unlocked by Enchantment Skill."));
        inv.setItem(8, createGuiItem(Material.KNOWLEDGE_BOOK,
                ChatColor.GOLD + "\u2726 Unique", "none",
                ChatColor.GRAY + "Requires specific Enchant Book.",
                ChatColor.GRAY + "Crafted via Collection Rewards."));

        // Populate columns (4 slots each with prev/next paging)
        populateEnchantColumn(p, inv, weapon, enchSkillLvl, "basic", BASIC_SLOTS, BASIC_PREV, BASIC_NEXT);
        populateEnchantColumn(p, inv, weapon, enchSkillLvl, "unique", UNIQUE_SLOTS, UNIQUE_PREV, UNIQUE_NEXT);

        inv.setItem(48, createGuiItem(Material.GRINDSTONE, ChatColor.RED + "Remove Enchantments", "enchant:remove_menu",
                ChatColor.GRAY + "Click to remove applied enchants from this item."));
        // Close button intentionally removed — player uses ESC or clicks outside
        p.openInventory(inv);
    }

    private void populateEnchantColumn(Player p, Inventory inv, ItemStack weapon,
            int enchSkillLvl, String cat,
            int[] colSlots, int prevSlot, int nextSlot) {
        ConfigurationSection enchSec = plugin.getConfig().getConfigurationSection("enchants." + cat);
        if (enchSec == null)
            return;

        // Build filtered list: only enchants compatible with weapon target and where
        // prereq chain is visible
        List<String> eligible = new ArrayList<>();
        for (String eId : enchSec.getKeys(false)) {
            List<String> targets = enchSec.getStringList(eId + ".targets");
            if (weapon == null || weapon.getType() == Material.AIR) {
                eligible.add(eId); // no weapon placed: show all
            } else {
                String wType = weapon.getType().name();
                boolean fits = false;
                for (String t : targets) {
                    if (wType.contains(t.toUpperCase())) {
                        fits = true;
                        break;
                    }
                }
                if (!fits)
                    continue; // skip incompatible targets

                boolean hasSameGroup = false;
                String group = enchSec.getString(eId + ".group", "");
                if (weapon.hasItemMeta() && !group.isEmpty()) {
                    for (String otherId : enchSec.getKeys(false)) {
                        if (enchSec.getString(otherId + ".group", "").equals(group)) {
                            if (weapon.getItemMeta().getPersistentDataContainer()
                                    .has(new NamespacedKey(plugin, "ench_" + otherId), PersistentDataType.BYTE)) {
                                hasSameGroup = true;
                                break;
                            }
                        }
                    }
                }

                // Show if already applied OR prereq satisfied OR player holds the specific
                // enchant book (unique)
                boolean alreadyApplied = weapon.hasItemMeta() && weapon.getItemMeta()
                        .getPersistentDataContainer()
                        .has(new NamespacedKey(plugin, "ench_" + eId), PersistentDataType.BYTE);
                String prereq = enchSec.getString(eId + ".prerequisite", "");

                boolean prereqSatisfied = false;
                if (!prereq.isEmpty()) {
                    prereqSatisfied = weapon.hasItemMeta() && weapon.getItemMeta().getPersistentDataContainer()
                            .has(new NamespacedKey(plugin, "ench_" + prereq), PersistentDataType.BYTE);
                } else if (!hasSameGroup) {
                    prereqSatisfied = true;
                }

                boolean holdsSpecificBook = cat.equals("unique") && playerHoldsEnchantBook(p, eId);
                if (alreadyApplied || prereqSatisfied || holdsSpecificBook)
                    eligible.add(eId);
            }
        }

        int totalPages = eligible.isEmpty() ? 1 : (int) Math.ceil((double) eligible.size() / ENCHANTS_PER_PAGE);
        int pageKey = enchPageMap.getOrDefault(p.getUniqueId() + cat, 0);
        pageKey = Math.max(0, Math.min(pageKey, totalPages - 1));
        enchPageMap.put(p.getUniqueId() + cat, pageKey);

        int start = pageKey * ENCHANTS_PER_PAGE;
        int end = Math.min(start + ENCHANTS_PER_PAGE, eligible.size());

        for (int i = 0; i < colSlots.length; i++) {
            int idx = start + i;
            if (idx >= end) {
                inv.setItem(colSlots[i], createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none"));
                continue;
            }

            String eId = eligible.get(idx);
            String displayName = ChatColor.translateAlternateColorCodes('&', enchSec.getString(eId + ".name", eId));
            String loreLine = ChatColor.translateAlternateColorCodes('&', enchSec.getString(eId + ".lore", ""));
            List<String> targets = enchSec.getStringList(eId + ".targets");
            int levelCost = enchSec.getInt(eId + ".level_cost", 5);
            String prereq = enchSec.getString(eId + ".prerequisite", "");
            int tier = 1;
            int ul = eId.lastIndexOf('_');
            if (ul >= 0) {
                try {
                    tier = Integer.parseInt(eId.substring(ul + 1));
                } catch (NumberFormatException ignored) {
                }
            }

            boolean alreadyHas = weapon != null && weapon.hasItemMeta() &&
                    weapon.getItemMeta().getPersistentDataContainer()
                            .has(new NamespacedKey(plugin, "ench_" + eId), PersistentDataType.BYTE);
            boolean prereqMet = prereq.isEmpty() || (weapon != null && weapon.hasItemMeta() &&
                    weapon.getItemMeta().getPersistentDataContainer()
                            .has(new NamespacedKey(plugin, "ench_" + prereq), PersistentDataType.BYTE));

            // Skill/collection unlock gate
            boolean unlocked = true;
            String lockReason = "";
            if (cat.equals("basic")) {
                int reqLvl = enchSec.getInt(eId + ".required_enchant_skill", 0);
                if (enchSkillLvl < reqLvl) {
                    unlocked = false;
                    lockReason = ChatColor.RED + "Req. Enchant Skill Lv" + reqLvl;
                }
            } else {
                // Unique: player must have the specific enchant book OR meet collection
                // requirement
                boolean holdsBook = playerHoldsEnchantBook(p, eId);
                if (!holdsBook) {
                    // Check recipe-unlock collection requirement
                    String recipeUnlock = enchSec.getString(eId + ".recipe_unlock", "");
                    if (!recipeUnlock.isEmpty()) {
                        String[] pts = recipeUnlock.split(":");
                        if (pts.length == 4) {
                            int colLvl = p.getPersistentDataContainer().getOrDefault(
                                    new NamespacedKey(plugin, "collvl_" + pts[2].toLowerCase()),
                                    PersistentDataType.INTEGER, 0);
                            int reqLvl = Integer.parseInt(pts[3]);
                            if (colLvl < reqLvl) {
                                unlocked = false;
                                lockReason = ChatColor.RED + "Need " + pts[2] + " Lv" + reqLvl + " to craft book";
                            }
                        }
                    }
                }
                // Even with book: still require enchantment_collection per tier
                if (unlocked) {
                    String reqColStr = enchSec.getString(eId + ".required_collection", "");
                    if (!reqColStr.isEmpty()) {
                        String[] pts = reqColStr.split(":");
                        if (pts.length == 4) {
                            int colLvl = p.getPersistentDataContainer().getOrDefault(
                                    new NamespacedKey(plugin, "collvl_" + pts[2].toLowerCase()),
                                    PersistentDataType.INTEGER, 0);
                            int reqLvl = Integer.parseInt(pts[3]);
                            if (colLvl < reqLvl) {
                                unlocked = false;
                                lockReason = ChatColor.RED + "Req. " + pts[2] + " Col Lv" + reqLvl;
                            }
                        }
                    }
                }
            }

            List<String> lore = new ArrayList<>();
            lore.add(loreLine);
            lore.add(ChatColor.DARK_GRAY + "Targets: " + ChatColor.GRAY + String.join(", ", targets));
            lore.add(ChatColor.YELLOW + "Cost: " + ChatColor.GOLD + levelCost + " XP levels");
            if (!prereq.isEmpty()) {
                String pName = ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("enchants.basic." + prereq + ".name",
                                plugin.getConfig().getString("enchants.unique." + prereq + ".name", prereq)));
                lore.add(ChatColor.DARK_GRAY + "Requires: " + pName + ChatColor.DARK_GRAY + " on item");
            }
            lore.add("");

            Material mat;
            String clickAction;

            if (alreadyHas) {
                mat = Material.LIME_STAINED_GLASS_PANE;
                lore.add(ChatColor.GREEN + "\u2714 Already applied.");
                clickAction = "none";
            } else if (!unlocked) {
                mat = Material.RED_STAINED_GLASS_PANE;
                lore.add(lockReason);
                clickAction = "none";
            } else if (!prereqMet && weapon != null) {
                mat = Material.ORANGE_STAINED_GLASS_PANE;
                lore.add(ChatColor.GOLD + "Apply previous tier first!");
                clickAction = "none";
            } else if (weapon == null || weapon.getType() == Material.AIR) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                lore.add(ChatColor.YELLOW + "Place your item in slot first.");
                clickAction = "none";
            } else if (p.getLevel() < levelCost) {
                mat = Material.YELLOW_STAINED_GLASS_PANE;
                lore.add(ChatColor.RED + "Need " + levelCost + " XP levels! Have " + p.getLevel() + ".");
                clickAction = "none";
            } else if (cat.equals("unique")) {
                // Unique requires holding the specific enchant book for this tier
                boolean holdsBook = playerHoldsEnchantBook(p, eId);
                if (!holdsBook) {
                    mat = Material.ORANGE_STAINED_GLASS_PANE;
                    String bookName = ChatColor.translateAlternateColorCodes('&',
                            plugin.getConfig().getString("enchants.unique." + eId + ".name", eId));
                    lore.add(ChatColor.GOLD + "Need book: " + bookName + ChatColor.GOLD + " in inventory!");
                    clickAction = "none";
                } else {
                    lore.add(ChatColor.AQUA + "Consumes: 1x " + displayName + ChatColor.AQUA + " book");
                    mat = Material.ENCHANTED_BOOK;
                    lore.add(ChatColor.GREEN + "\u25b6 Click to apply!");
                    clickAction = "enchant:apply:" + cat + ":" + eId;
                }
            } else {
                mat = Material.ENCHANTED_BOOK;
                lore.add(ChatColor.GREEN + "\u25b6 Click to apply!");
                clickAction = "enchant:apply:" + cat + ":" + eId;
            }

            inv.setItem(colSlots[i], createGuiItem(mat, displayName, clickAction, lore));
        }

        // Pagination navigation buttons
        if (pageKey > 0) {
            inv.setItem(prevSlot, createGuiItem(Material.ARROW,
                    ChatColor.YELLOW + "\u25c4 Prev", "enchant:page:" + cat + ":" + (pageKey - 1)));
        } else {
            inv.setItem(prevSlot, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none"));
        }
        if (pageKey < totalPages - 1) {
            inv.setItem(nextSlot, createGuiItem(Material.ARROW,
                    ChatColor.YELLOW + "Next \u25ba", "enchant:page:" + cat + ":" + (pageKey + 1)));
        } else {
            inv.setItem(nextSlot, createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none"));
        }
    }

    // Legacy compatibility: routes to buildEnchantingInventory
    public void openEnchantingCategory(Player p, String cat, ItemStack weapon) {
        if (weapon != null && weapon.getType() != Material.AIR) {
            pendingEnchantWeapon.put(p.getUniqueId(), weapon.clone());
        }
        buildEnchantingInventory(p, pendingEnchantWeapon.get(p.getUniqueId()));
    }

    public void openEnchantRemoveMenu(Player p) {
        ItemStack weapon = pendingEnchantWeapon.get(p.getUniqueId());
        if (weapon == null || weapon.getType() == Material.AIR)
            return;
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("enchant_remove"), 27,
                ChatColor.DARK_RED + "Select Enchant to Remove");
        int slot = 9;
        String[] cats = { "basic", "unique" };
        for (String cat : cats) {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("enchants." + cat);
            if (sec == null)
                continue;
            for (String eId : sec.getKeys(false)) {
                if (weapon.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "ench_" + eId),
                        PersistentDataType.BYTE)) {
                    if (slot >= 18)
                        break;
                    String eName = ChatColor.translateAlternateColorCodes('&', sec.getString(eId + ".name", eId));
                    inv.setItem(slot++,
                            createGuiItem(Material.ENCHANTED_BOOK, eName, "enchant:confirm_remove:" + cat + ":" + eId,
                                    ChatColor.YELLOW + "Click to remove this enchant."));
                }
            }
        }
        inv.setItem(22, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "enchant:back"));
        p.openInventory(inv);
    }

    public void openEnchantConfirmRemove(Player p, String cat, String eId) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("enchant_confirm_remove", cat + ":" + eId), 27,
                ChatColor.DARK_RED + "Confirm Remove");
        String eName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("enchants." + cat + "." + eId + ".name", eId));
        inv.setItem(11, createGuiItem(Material.LIME_CONCRETE, ChatColor.GREEN + "CONFIRM",
                "enchant:do_remove:" + cat + ":" + eId, ChatColor.GRAY + "Remove " + eName));
        inv.setItem(13, createGuiItem(Material.ENCHANTED_BOOK, ChatColor.YELLOW + "Removing: " + eName, "none"));
        inv.setItem(15, createGuiItem(Material.RED_CONCRETE, ChatColor.RED + "CANCEL", "enchant:remove_menu",
                ChatColor.GRAY + "Go back"));
        p.openInventory(inv);
    }

    public void openEnchantConfirmReplace(Player p, String newCat, String newId, String oldCat, String oldId) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("enchant_confirm_replace", newCat + ":" + newId), 27,
                ChatColor.DARK_RED + "Confirm Replace");
        String newName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("enchants." + newCat + "." + newId + ".name", newId));
        String oldName = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("enchants." + oldCat + "." + oldId + ".name", oldId));

        inv.setItem(11,
                createGuiItem(Material.LIME_CONCRETE, ChatColor.GREEN + "CONFIRM",
                        "enchant:force_apply:" + newCat + ":" + newId, ChatColor.GRAY + "Apply " + newName,
                        ChatColor.RED + "WARNING: " + oldName + " will be LOST!"));
        inv.setItem(13, createGuiItem(Material.ENCHANTED_BOOK, ChatColor.YELLOW + "Replacing " + oldName, "none",
                ChatColor.GRAY + "with " + newName));
        inv.setItem(15, createGuiItem(Material.RED_CONCRETE, ChatColor.RED + "CANCEL", "enchant:back",
                ChatColor.GRAY + "Go back"));
        p.openInventory(inv);
    }

    // Check whether the player holds a specific named enchant book (PDC key:
    // enchant_book_id = enchId)
    private boolean playerHoldsEnchantBook(Player p, String enchId) {
        for (ItemStack inv : p.getInventory().getContents()) {
            if (inv == null || inv.getType() != Material.ENCHANTED_BOOK || !inv.hasItemMeta())
                continue;
            String bookId = inv.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(new NamespacedKey(plugin, "enchant_book_id"), PersistentDataType.STRING, "");
            if (bookId.equals(enchId))
                return true;
        }
        return false;
    }

    // Consume one specific enchant book from player inventory
    private boolean consumeEnchantBook(Player p, String enchId) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack inv = p.getInventory().getItem(i);
            if (inv == null || inv.getType() != Material.ENCHANTED_BOOK || !inv.hasItemMeta())
                continue;
            String bookId = inv.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(new NamespacedKey(plugin, "enchant_book_id"), PersistentDataType.STRING, "");
            if (bookId.equals(enchId)) {
                inv.setAmount(inv.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Apply a specific enchant to the weapon — handles tier overwrite (removes old
    // tier in same group)
    private void applyEnchant(Player p, ItemStack weapon, String cat, String enchId, boolean force) {
        if (weapon == null || weapon.getType() == Material.AIR) {
            p.sendMessage(ChatColor.RED + "No item found. Re-open the Enchanting Table.");
            return;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null)
            return;

        ConfigurationSection enchSec = plugin.getConfig().getConfigurationSection("enchants." + cat + "." + enchId);
        if (enchSec == null)
            return;

        NamespacedKey enchKey = new NamespacedKey(plugin, "ench_" + enchId);
        if (meta.getPersistentDataContainer().has(enchKey, PersistentDataType.BYTE)) {
            p.sendMessage(ChatColor.RED + "This item already has that enchantment!");
            return;
        }

        // --- Prerequisite check ---
        String prereq = enchSec.getString("prerequisite", "");
        if (!prereq.isEmpty()) {
            NamespacedKey prereqKey = new NamespacedKey(plugin, "ench_" + prereq);
            if (!meta.getPersistentDataContainer().has(prereqKey, PersistentDataType.BYTE)) {
                String prereqName = ChatColor.translateAlternateColorCodes('&',
                        plugin.getConfig().getString("enchants.basic." + prereq + ".name",
                                plugin.getConfig().getString("enchants.unique." + prereq + ".name", prereq)));
                p.sendMessage(ChatColor.RED + "You need " + prereqName + ChatColor.RED + " on the item first!");
                return;
            }
        }

        // --- Required Collection check ---
        String reqColStr = enchSec.getString("required_collection", "");
        if (!reqColStr.isEmpty()) {
            String[] pts = reqColStr.split(":");
            if (pts.length == 4) {
                NamespacedKey colKey = new NamespacedKey(plugin, "collvl_" + pts[2].toLowerCase());
                int colLvl = p.getPersistentDataContainer().getOrDefault(colKey, PersistentDataType.INTEGER, 0);
                int reqLvl = Integer.parseInt(pts[3]);
                if (colLvl < reqLvl) {
                    p.sendMessage(ChatColor.RED + "You need " + pts[2] + " Collection Level " + reqLvl + " to apply this enchantment!");
                    return;
                }
            }
        }

        // --- Level cost check ---
        int levelCost = enchSec.getInt("level_cost", 5);
        if (p.getLevel() < levelCost) {
            p.sendMessage(ChatColor.RED + "You need " + levelCost + " XP levels to enchant! You only have "
                    + p.getLevel() + ".");
            return;
        }

        // --- Unique: consume specific enchant book ---
        if (cat.equals("unique")) {
            if (!playerHoldsEnchantBook(p, enchId)) {
                String bookName = ChatColor.translateAlternateColorCodes('&', enchSec.getString("name", enchId));
                p.sendMessage(ChatColor.RED + "You need the " + bookName + ChatColor.RED + " book in your inventory!");
                return;
            }
        }

        // --- Conflict check for prompting Replace Confirmation ---
        String group = enchSec.getString("group", "");
        String conflictId = null;
        String conflictCat = null;
        List<String> loreCopy = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        if (!group.isEmpty()) {
            String[] categories = { "basic", "unique" };
            for (String catKey : categories) {
                ConfigurationSection catSection = plugin.getConfig().getConfigurationSection("enchants." + catKey);
                if (catSection == null)
                    continue;
                for (String otherId : catSection.getKeys(false)) {
                    if (otherId.equals(enchId))
                        continue;
                    String otherGroup = catSection.getString(otherId + ".group", "");
                    if (!otherGroup.equals(group))
                        continue;
                    NamespacedKey otherKey = new NamespacedKey(plugin, "ench_" + otherId);
                    if (meta.getPersistentDataContainer().has(otherKey, PersistentDataType.BYTE)) {
                        if (prereq.equals(otherId)) {
                            // Direct upgrade - no prompt needed
                        } else {
                            conflictId = otherId;
                            conflictCat = catKey;
                        }

                        // We will remove it if we bypass or force
                        if (force || prereq.equals(otherId)) {
                            meta.getPersistentDataContainer().remove(otherKey);
                            String oldName = ChatColor.translateAlternateColorCodes('&',
                                    catSection.getString(otherId + ".name", otherId));
                            loreCopy.removeIf(
                                    line -> ChatColor.stripColor(line).contains(ChatColor.stripColor(oldName)));
                        }
                    }
                }
            }
        }

        if (!force && conflictId != null) {
            openEnchantConfirmReplace(p, cat, enchId, conflictCat, conflictId);
            return;
        }

        // --- Consume Book AFTER confirmation ---
        if (cat.equals("unique") && !consumeEnchantBook(p, enchId)) {
            p.sendMessage(ChatColor.RED + "Could not consume the enchant book. Please try again.");
            return;
        }

        // --- Deduct XP levels ---
        p.setLevel(p.getLevel() - levelCost);

        // --- Write new enchant tag + lore ---
        meta.getPersistentDataContainer().set(enchKey, PersistentDataType.BYTE, (byte) 1);
        String enchName = ChatColor.translateAlternateColorCodes('&', enchSec.getString("name", enchId));
        String enchDesc = ChatColor.translateAlternateColorCodes('&', enchSec.getString("lore", ""));
        loreCopy.add(enchName + ChatColor.DARK_GRAY + " \u2014 " + ChatColor.GRAY + enchDesc);
        meta.setLore(loreCopy);
        weapon.setItemMeta(meta);

        // Update cache
        pendingEnchantWeapon.put(p.getUniqueId(), weapon.clone());

        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.5f);
        p.spawnParticle(org.bukkit.Particle.ENCHANT, p.getLocation().add(0, 1, 0), 40);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "\u2726 " + enchName + ChatColor.GREEN + " applied! "
                + ChatColor.YELLOW + "(-" + levelCost + " levels)");

        // Give Enchantment skill EXP
        net.elpixedge.core.progression.ProgressionModule progMod = plugin
                .getModule(net.elpixedge.core.progression.ProgressionModule.class);
        if (progMod != null)
            progMod.addSkillExp(p, "enchantment", levelCost * 5);

        // Refresh the enchanting inventory in-place
        buildEnchantingInventory(p, weapon);
    }

    public void openRewardsMenu(Player p, String type, String category, String id, int page) {
        openRewardsMenu(p, type, category, id, page, null);
    }

    // backCtx: null = go back to category, "status_skills" = go back to skill
    // status menu
    public void openRewardsMenu(Player p, String type, String category, String id, int page, String backCtx) {
        boolean isSkill = type.equals("skill");
        String title = isSkill ? "Skill Rewards: " + category.toUpperCase() : "Collection: " + id.toUpperCase();
        Inventory inv = Bukkit.createInventory(
                new EdgeMenuHolder("rewards", type + ":" + category + ":" + id + ":" + page), 54,
                ChatColor.DARK_GRAY + title + " (Pg." + page + ")");

        String lvlKey = isSkill ? "skill_lvl_" + category.toLowerCase() : "collvl_" + id.toLowerCase();
        String expKey = isSkill ? "skill_exp_" + category.toLowerCase() : "colexp_" + id.toLowerCase();
        int pLvl = p.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, lvlKey),
                PersistentDataType.INTEGER, 0);
        double pExp = p.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, expKey),
                PersistentDataType.DOUBLE, 0.0);

        double baseExp = plugin.getConfig()
                .getDouble(isSkill ? "leveling.skills.base_exp" : "leveling.collections.base_exp", 250.0);
        double mult = plugin.getConfig()
                .getDouble(isSkill ? "leveling.skills.multiplier" : "leveling.collections.multiplier", 1.5);

        ConfigurationSection statSec = isSkill
                ? plugin.getConfig().getConfigurationSection("skills." + category + ".stat_gain")
                : null;

        int maxLevel;
        if (!isSkill) {
            maxLevel = plugin.getConfig().getInt("leveling.collections.max_level", 5);
        } else {
            maxLevel = category.equalsIgnoreCase("combat") ? 40 : 14;
        }



        int startIndex = (page - 1) * 28 + 1;
        int endIndex = Math.min(maxLevel, page * 28);

        int slot = 10;
        for (int i = startIndex; i <= endIndex; i++) {
            if (slot % 9 == 8)
                slot += 2;
            double expRequired = baseExp * Math.pow(mult, i - 1);

            Material mat = (pLvl >= i) ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            List<String> lore = new ArrayList<>();

            // Enchantment collections suppress the progress bar (too noisy for skill-gated
            // display).
            // Skills and all other collections always show numeric progress + bar.
            boolean isEnchantCollection = !isSkill && category.equalsIgnoreCase("enchantment");

            if (pLvl >= i) {
                lore.add(ChatColor.GREEN + "UNLOCKED");
                if (!isEnchantCollection) {
                    // Show cumulative EXP / cap for this tier so players can see how far they've
                    // gone
                    lore.add(getProgressBar(1, 1, 10) + ChatColor.GREEN + " MAX");
                }
            } else {
                lore.add(ChatColor.RED + "LOCKED");
                if (!isEnchantCollection) {
                    int displayExp = (pLvl == i - 1) ? (int) pExp : 0;
                    lore.add(ChatColor.GRAY + "Progress: " + displayExp + "/" + (int) expRequired);
                    lore.add(getProgressBar(pLvl == i - 1 ? pExp : 0, expRequired, 10));
                }
            }
            lore.add("");

            boolean hasSpecialReward = false;
            String recipeAction = "none";

            if (isSkill && statSec != null) {
                lore.add(ChatColor.AQUA + "Stat Bonuses:");
                for (String statKey : statSec.getKeys(false)) {
                    double gainPerLvl = statSec.getDouble(statKey);
                    String statName = statKey;
                    try {
                        statName = net.elpixedge.core.utils.StatType.valueOf(statKey.toUpperCase()).getDisplayName();
                    } catch (Exception ignored) {
                    }

                    if (i == 1) {
                        lore.add(statName + ChatColor.WHITE + " +" + String.format("%.2f", gainPerLvl));
                    } else {
                        lore.add(statName + ChatColor.WHITE + " +" + String.format("%.2f", gainPerLvl * i) +
                                ChatColor.GRAY + " (" + String.format("%.2f", gainPerLvl * (i - 1)) + " \u2192 "
                                + String.format("%.2f", gainPerLvl * i) + ")");
                    }
                }
            }

            // Check Recipes
            ConfigurationSection recipes = plugin.getModule(net.elpixedge.core.item.ItemModule.class).getRecipeManager().getRecipesSection();
            List<String> unlockedRecipes = new ArrayList<>();
            if (recipes != null) {
                for (String reqKey : recipes.getKeys(false)) {
                    String req = recipes.getString(reqKey + ".requires");
                    if (isSkill && req != null && req.equalsIgnoreCase("skill:" + category + ":" + i)) {
                        unlockedRecipes.add(reqKey);
                        hasSpecialReward = true;
                    } else if (!isSkill && req != null
                            && req.equalsIgnoreCase("collection:" + category + ":" + id + ":" + i)) {
                        unlockedRecipes.add(reqKey);
                        hasSpecialReward = true;
                    }
                }
            }
            if (!unlockedRecipes.isEmpty()) {
                if (unlockedRecipes.size() == 1) {
                    lore.add(ChatColor.GOLD + "Unlocks Recipe: " + ChatColor.WHITE
                            + unlockedRecipes.get(0).replace("_", " "));
                } else {
                    lore.add(ChatColor.GOLD + "Unlocks " + unlockedRecipes.size() + " Recipes");
                    for (String reqKey : unlockedRecipes) {
                        lore.add(ChatColor.GRAY + " - " + reqKey.replace("_", " "));
                    }
                }
                recipeAction = "guide:view:" + String.join(",", unlockedRecipes) + "@0";
            }

            // Check Zones
            ConfigurationSection zones = plugin.getConfig().getConfigurationSection("zones");
            if (zones != null) {
                for (String zKey : zones.getKeys(false)) {
                    String req = zones.getString(zKey + ".requires");
                    if (isSkill && req != null && req.equalsIgnoreCase("skill:" + category + ":" + i)) {
                        lore.add(ChatColor.GOLD + "Unlocks Zone: " + ChatColor.WHITE
                                + ChatColor.translateAlternateColorCodes('&', zones.getString(zKey + ".name", zKey)));
                        hasSpecialReward = true;
                    } else if (!isSkill && req != null
                            && req.equalsIgnoreCase("collection:" + category + ":" + id + ":" + i)) {
                        lore.add(ChatColor.GOLD + "Unlocks Zone: " + ChatColor.WHITE
                                + ChatColor.translateAlternateColorCodes('&', zones.getString(zKey + ".name", zKey)));
                        hasSpecialReward = true;
                    }
                }
            }

            // Check Enchants — shown in their specific enchantment collection reward track
            // Format: "SHARPNESS Lv1: Unlock Enchant Sharpness I" (basic)
            // "AIMING Lv2: Unlock Book Recipe Aiming II" (unique)
            ConfigurationSection enchants = plugin.getConfig().getConfigurationSection("enchants");
            if (enchants != null) {
                for (String catKey : enchants.getKeys(false)) {
                    ConfigurationSection eSec = enchants.getConfigurationSection(catKey);
                    if (eSec == null)
                        continue;
                    for (String eKey : eSec.getKeys(false)) {
                        String reqCol = eSec.getString(eKey + ".required_collection", "");
                        if (!isSkill && !reqCol.isEmpty()
                                && reqCol.equalsIgnoreCase("collection:" + category + ":" + id + ":" + i)) {
                            String enchDisplayName = ChatColor.stripColor(
                                    ChatColor.translateAlternateColorCodes('&', eSec.getString(eKey + ".name", eKey)));
                            String collectionLabel = id.toUpperCase() + " Lv" + i;
                            if (catKey.equals("unique")) {
                                lore.add(ChatColor.LIGHT_PURPLE + collectionLabel + ": " + ChatColor.WHITE
                                        + "Unlock Book Recipe " + enchDisplayName);
                            } else {
                                lore.add(ChatColor.GOLD + collectionLabel + ": " + ChatColor.WHITE + "Unlock Enchant "
                                        + enchDisplayName);
                            }
                            hasSpecialReward = true;

                            if (catKey.equals("unique")) {
                                String bookRecipeId = "book_" + eKey;
                                ConfigurationSection recipesRootEnch = plugin.getModule(net.elpixedge.core.item.ItemModule.class).getRecipeManager().getRecipesSection();
                                if (recipesRootEnch != null && recipesRootEnch.contains(bookRecipeId)) {
                                    recipeAction = "guide:view:" + bookRecipeId;
                                }
                            }
                        }
                    }
                }
            }

            // Check Items
            if (isSkill) {
                ConfigurationSection items = plugin.getConfig().getConfigurationSection("items");
                if (items != null) {
                    for (String iKey : items.getKeys(false)) {
                        if (items.getString(iKey + ".require_skill", "").equalsIgnoreCase(category)
                                && items.getInt(iKey + ".require_level", 0) == i) {
                            lore.add(ChatColor.GOLD + "Unlocks Item Use: " + ChatColor.WHITE + ChatColor
                                    .translateAlternateColorCodes('&', items.getString(iKey + ".name", iKey)));
                            hasSpecialReward = true;
                        }
                    }
                }
            }

            if (!hasSpecialReward && !(isSkill && statSec != null)) {
                lore.add(ChatColor.DARK_GRAY + "No special reward.");
            }

            if (hasSpecialReward) {
                mat = Material.PAPER;
            }

            // Build the back-context string for this rewards page, so recipe/guide can
            // return here
            // Format: rewards:<type>:<category>:<id>:<page> — 5 colon-separated parts,
            // matching the handler at action.startsWith("rewards:skill:") | "rewards:col:"
            String rewardsBackCtx = isSkill
                    ? "rewards:skill:" + category + ":" + id + ":" + page
                    : "rewards:col:" + category + ":" + id + ":" + page;

            if (hasSpecialReward && recipeAction.startsWith("guide:view:")) {
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click to view Recipe!");
                // Encode the back context into a special action:
                // "recipefrom:<recipeId>|<backCtx>"
                String recipeId = recipeAction.substring("guide:view:".length());
                String clickAction = "recipefrom:" + recipeId + "|" + rewardsBackCtx;
                inv.setItem(slot++, createGuiItem(mat, ChatColor.YELLOW + "Level " + i, clickAction, lore));
            } else {
                inv.setItem(slot++, createGuiItem(mat, ChatColor.YELLOW + "Level " + i, "none", lore));
            }
        }

        if (page > 1) {
            inv.setItem(45, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Previous Page",
                    "reward_page:prev:" + type + ":" + category + ":" + id + ":" + page));
        }
        if (endIndex < maxLevel) {
            inv.setItem(53, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Next Page",
                    "reward_page:next:" + type + ":" + category + ":" + id + ":" + page));
        }

        // Back button: return to openSkillStatusMenu if backCtx == "status_skills",
        // else to category
        String backAction = (backCtx != null && backCtx.equals("status_skills"))
                ? "open:status_skills"
                : "back:cat:" + category;
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", backAction));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    private void spawnMobForAdmin(Player p, String mobTypeStr, int lvl) {
        org.bukkit.entity.EntityType entityType;
        String baseEntStr = plugin.getConfig().getString("mobs." + mobTypeStr.toLowerCase() + ".base_entity", mobTypeStr);
        try {
            entityType = org.bukkit.entity.EntityType.valueOf(baseEntStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            entityType = org.bukkit.entity.EntityType.ENDERMAN; // fallback for custom IDs like apex_enderman
        }
        Class<? extends org.bukkit.entity.Entity> entityClass = entityType.getEntityClass();
        if (entityClass == null || !org.bukkit.entity.LivingEntity.class.isAssignableFrom(entityClass)) {
            p.sendMessage(ChatColor.RED + "Cannot spawn entity type: " + mobTypeStr);
            return;
        }
        org.bukkit.entity.LivingEntity mob = (org.bukkit.entity.LivingEntity) p.getWorld().spawn(p.getLocation(),
                entityClass, entity -> {
                    entity.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, lvl);
                    entity.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING,
                            mobTypeStr.toLowerCase());
                    if (entity instanceof org.bukkit.entity.LivingEntity le) {
                        double maxHp = 15.0 + (lvl * 10.0);
                        org.bukkit.attribute.Attribute hpAttr = org.bukkit.attribute.Attribute.MAX_HEALTH;
                        if (le.getAttribute(hpAttr) != null)
                            le.getAttribute(hpAttr).setBaseValue(maxHp);
                        le.setHealth(maxHp);
                    }
                });
        net.elpixedge.core.combat.CombatModule combat = plugin.getModule(net.elpixedge.core.combat.CombatModule.class);
        if (combat != null)
            combat.updateMobName(mob);
        p.sendMessage(ChatColor.GREEN + "Spawned " + mobTypeStr + " Lv." + lvl);
    }

    public void openSkillStatusMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("status_skills"), 27,
                ChatColor.DARK_GRAY + "Skill Stat Bonuses");
        String[] cats = { "combat", "mining", "gathering", "arcane", "enchantment" };
        Material[] mats = { Material.DIAMOND_SWORD, Material.GOLDEN_PICKAXE, Material.IRON_HOE, Material.END_CRYSTAL,
                Material.ENCHANTING_TABLE };
        String[] titles = { ChatColor.RED + "⚔ Combat", ChatColor.GRAY + "⛏ Mining", ChatColor.GREEN + "🌿 Gathering",
                ChatColor.LIGHT_PURPLE + "✧ Arcane", ChatColor.AQUA + "✎ Enchantment" };
        int[] slots = { 11, 12, 13, 14, 15 };

        net.elpixedge.core.progression.ProgressionModule progMod = plugin
                .getModule(net.elpixedge.core.progression.ProgressionModule.class);

        for (int i = 0; i < 5; i++) {
            int lvl = progMod.getSkillLevel(p, cats[i]);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Current Level: " + ChatColor.YELLOW + lvl);
            lore.add("");

            ConfigurationSection statSec = plugin.getConfig()
                    .getConfigurationSection("skills." + cats[i] + ".stat_gain");
            if (statSec != null) {
                lore.add(ChatColor.AQUA + "Total Stat Bonus (Lv." + lvl + "):");
                for (String statKey : statSec.getKeys(false)) {
                    double gainPerLvl = statSec.getDouble(statKey);
                    try {
                        net.elpixedge.core.utils.StatType sType = net.elpixedge.core.utils.StatType
                                .valueOf(statKey.toUpperCase());
                        lore.add(sType.formatShort(gainPerLvl * lvl));
                    } catch (Exception ex) {
                        lore.add(ChatColor.GREEN + "+ " + String.format("%.2f", gainPerLvl * lvl) + " "
                                + statKey.toUpperCase());
                    }
                }
            } else {
                lore.add(ChatColor.DARK_GRAY + "No stat bonuses.");
            }

            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to view Rewards!");
            // Pass backContext so rewards screen knows to return here
            inv.setItem(slots[i], createGuiItem(mats[i], titles[i],
                    "reward:skill:" + cats[i] + ":1:status_skills", lore));
        }

        inv.setItem(22, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "open:status"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("skills"), 27, ChatColor.DARK_GRAY + "Progression");
        String[] cats = { "combat", "mining", "gathering", "arcane", "enchantment" };
        Material[] mats = { Material.DIAMOND_SWORD, Material.GOLDEN_PICKAXE, Material.IRON_HOE, Material.END_CRYSTAL,
                Material.ENCHANTING_TABLE };
        String[] titles = { ChatColor.RED + "⚔ Combat", ChatColor.GRAY + "⛏ Mining", ChatColor.GREEN + "🌿 Gathering",
                ChatColor.LIGHT_PURPLE + "✧ Arcane", ChatColor.AQUA + "✎ Enchantment" };
        int[] slots = { 11, 12, 13, 14, 15 };

        net.elpixedge.core.progression.ProgressionModule progMod = plugin
                .getModule(net.elpixedge.core.progression.ProgressionModule.class);

        for (int i = 0; i < 5; i++) {
            int lvl = progMod.getSkillLevel(p, cats[i]);
            double exp = p.getPersistentDataContainer().getOrDefault(new NamespacedKey(plugin, "skill_exp_" + cats[i]),
                    PersistentDataType.DOUBLE, 0.0);
            double reqExp = plugin.getConfig().getDouble("leveling.skills.base_exp", 250.0)
                    * Math.pow(plugin.getConfig().getDouble("leveling.skills.multiplier", 1.5), lvl);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Level " + ChatColor.YELLOW + lvl);
            lore.add(getProgressBar(exp, reqExp, 10) + ChatColor.AQUA + " " + (int) exp + ChatColor.GRAY + "/"
                    + ChatColor.AQUA + (int) reqExp);
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to view Rewards!");

            inv.setItem(slots[i], createGuiItem(mats[i], titles[i], "open:cat:" + cats[i], lore));
        }

        inv.setItem(22, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "open:quick"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    public void openCategoryMenu(Player p, String category) {
        openCategoryMenu(p, category, 1);
    }

    public void openCategoryMenu(Player p, String category, int page) {
        Inventory inv = Bukkit.createInventory(new EdgeMenuHolder("category"), 54,
                ChatColor.DARK_GRAY + "Menu: " + category.toUpperCase() + (category.equalsIgnoreCase("enchantment") ? (" (Page " + page + ")") : ""));
        int skillLvl = plugin.getModule(net.elpixedge.core.progression.ProgressionModule.class).getSkillLevel(p,
                category);
        inv.setItem(4,
                createGuiItem(Material.NETHER_STAR, ChatColor.AQUA + "✦ " + category.toUpperCase() + " REWARDS ✦",
                        "reward:skill:" + category + ":1",
                        ChatColor.GRAY + "Current Level: " + ChatColor.YELLOW + skillLvl, "",
                        ChatColor.YELLOW + "Click to view Rewards!"));

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("collections." + category);
        if (section != null) {
            if (category.equalsIgnoreCase("enchantment")) {
                if (page == 1) {
                    inv.setItem(10, createGuiItem(Material.BOOK, ChatColor.YELLOW + "Basic Enchants", "none",
                            ChatColor.GRAY + "Standard progression."));
                    int slot = 19;
                    for (String colKey : section.getKeys(false)) {
                        String type = section.getString(colKey + ".category_type", "basic");
                        if (!type.equalsIgnoreCase("basic")) continue;
                        if (slot % 9 == 8) slot += 3;
                        populateCollectionSlot(inv, p, category, colKey, section, slot++);
                    }
                    inv.setItem(53, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Next Page (Unique)", "open:cat:enchantment:2"));
                } else {
                    inv.setItem(10, createGuiItem(Material.ENCHANTED_BOOK, ChatColor.LIGHT_PURPLE + "Unique Enchants", "none",
                            ChatColor.GRAY + "Special abilities."));
                    int slot = 19;
                    for (String colKey : section.getKeys(false)) {
                        String type = section.getString(colKey + ".category_type", "basic");
                        if (!type.equalsIgnoreCase("unique")) continue;
                        if (slot % 9 == 8) slot += 3;
                        populateCollectionSlot(inv, p, category, colKey, section, slot++);
                    }
                    inv.setItem(45, createGuiItem(Material.ARROW, ChatColor.YELLOW + "Previous Page (Basic)", "open:cat:enchantment:1"));
                }
            } else {
                int slot = 19;
                for (String colKey : section.getKeys(false)) {
                    if (slot % 9 == 8)
                        slot += 3;
                    populateCollectionSlot(inv, p, category, colKey, section, slot++);
                }
            }
        }
        inv.setItem(49, createGuiItem(Material.ARROW, ChatColor.RED + "Go Back", "open:skills"));
        ItemStack glass = createGuiItem(Material.BLACK_STAINED_GLASS_PANE, " ", "none");
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null)
                inv.setItem(i, glass);
        }
        p.openInventory(inv);
    }

    private void populateCollectionSlot(Inventory inv, Player p, String category, String colKey,
            ConfigurationSection section, int slot) {
        String iconName = section.getString(colKey + ".icon", "PAPER");
        Material mat = Material.matchMaterial(iconName) != null ? Material.valueOf(iconName) : Material.PAPER;
        String name = ChatColor.translateAlternateColorCodes('&', section.getString(colKey + ".name", colKey));

        int lvl = p.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "collvl_" + colKey.toLowerCase()), PersistentDataType.INTEGER, 0);
        double exp = p.getPersistentDataContainer().getOrDefault(
                new NamespacedKey(plugin, "colexp_" + colKey.toLowerCase()), PersistentDataType.DOUBLE, 0.0);
        double reqExp = plugin.getConfig().getDouble("leveling.collections.base_exp", 150.0)
                * Math.pow(plugin.getConfig().getDouble("leveling.collections.multiplier", 1.5), lvl);
        int colMaxLevel = plugin.getConfig().getInt("leveling.collections.max_level", 5);

        List<String> lore = new ArrayList<>();
        if (lvl >= colMaxLevel) {
            lore.add(ChatColor.GRAY + "Level " + ChatColor.GOLD + lvl + ChatColor.GREEN + " (MAX)");
            lore.add(getProgressBar(1, 1, 10) + ChatColor.GREEN + " MAX");
        } else {
            lore.add(ChatColor.GRAY + "Level " + ChatColor.YELLOW + lvl);
            lore.add(getProgressBar(exp, reqExp, 10) + ChatColor.AQUA + " " + (int) exp + ChatColor.GRAY + "/"
                    + ChatColor.AQUA + (int) reqExp);
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to view Rewards!");

        String actionStr = category.equalsIgnoreCase("enchantment") ? "reward:skill:enchantment:1"
                : "reward:col:" + category + ":" + colKey + ":1";
        inv.setItem(slot, createGuiItem(mat, name, "reward:col:" + category + ":" + colKey + ":1", lore));
    }

    // ==========================================
    // Event Handlers
    // ==========================================

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EdgeMenuHolder))
            return;
        EdgeMenuHolder holder = (EdgeMenuHolder) e.getInventory().getHolder();
        Player p = (Player) e.getPlayer();

        if (holder.getMenuType().equals("crafter")) {
            for (int slot : CRAFT_SLOTS) {
                ItemStack item = e.getInventory().getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    if (!p.getInventory().addItem(item).isEmpty()) {
                        p.getWorld().dropItem(p.getLocation(), item);
                    }
                    e.getInventory().setItem(slot, null);
                }
            }
        } else if (holder.getMenuType().equals("enchanting")) {
            // Return weapon only when the player fully leaves the enchanting flow.
            // The flow includes: enchanting, enchant_remove, enchant_confirm_remove,
            // enchant_confirm_replace.
            // buildEnchantingInventory / sub-menu opens all trigger close on the current
            // inventory,
            // so we wait 1 tick and check if the newly opened inventory is still part of
            // the flow.
            Player enchPlayer = p;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (enchPlayer.getOpenInventory().getTopInventory().getHolder() instanceof EdgeMenuHolder) {
                    EdgeMenuHolder newHolder = (EdgeMenuHolder) enchPlayer.getOpenInventory().getTopInventory()
                            .getHolder();
                    String nt = newHolder.getMenuType();
                    // Still inside the enchanting flow — do NOT return the weapon
                    if (nt.equals("enchanting")
                            || nt.startsWith("enchant_remove")
                            || nt.startsWith("enchant_confirm")) {
                        return;
                    }
                }
                // Player left the enchanting flow — return the weapon and clear cache
                ItemStack cached = pendingEnchantWeapon.remove(enchPlayer.getUniqueId());
                enchPageMap.remove(enchPlayer.getUniqueId() + "basic");
                enchPageMap.remove(enchPlayer.getUniqueId() + "unique");
                if (cached != null && cached.getType() != Material.AIR) {
                    if (!enchPlayer.getInventory().addItem(cached).isEmpty()) {
                        enchPlayer.getWorld().dropItem(enchPlayer.getLocation(), cached);
                    }
                }
            }, 1L);
        } else if (holder.getMenuType().equals("enchanting_cat")) {
            // When closing category list (going back to main or fully closing),
            // return the cached enchanted weapon to the player's inventory
            ItemStack cached = pendingEnchantWeapon.get(p.getUniqueId());
            if (cached != null) {
                // Schedule 1 tick later so the new menu can open first; if no new menu, item is
                // in inventory
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.getInventory().addItem(cached).isEmpty()) {
                        p.getWorld().dropItem(p.getLocation(), cached);
                    }
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof EdgeMenuHolder))
            return;
        EdgeMenuHolder holder = (EdgeMenuHolder) e.getInventory().getHolder();

        if (holder.getMenuType().equals("crafter")) {
            for (int slot : e.getRawSlots()) {
                // Only allow placing in craft slots and player inventory (slots >= 54)
                boolean isCraftSlot = false;
                for (int cSlot : CRAFT_SLOTS)
                    if (slot == cSlot) {
                        isCraftSlot = true;
                        break;
                    }
                if (!isCraftSlot && slot < 54)
                    e.setCancelled(true);
            }
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> checkRecipeMatch(e.getInventory(), (Player) e.getWhoClicked()), 1L);
        } else if (holder.getMenuType().equals("enchanting")) {
            for (int slot : e.getRawSlots()) {
                // Only allow drag into weapon item slot or player inventory
                if (slot != ENCH_ITEM_SLOT_MAIN && slot < 54) {
                    e.setCancelled(true);
                    return;
                }
            }
            // If drag includes the weapon slot, refresh inventory after 1 tick
            if (e.getRawSlots().contains(ENCH_ITEM_SLOT_MAIN)) {
                Player dragPlayer = (Player) e.getWhoClicked();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    ItemStack placed = e.getInventory().getItem(ENCH_ITEM_SLOT_MAIN);
                    pendingEnchantWeapon.put(dragPlayer.getUniqueId(), placed != null ? placed.clone() : null);
                    buildEnchantingInventory(dragPlayer, placed);
                }, 1L);
            }
        } else if (holder.getMenuType().equals("enchanting_cat")) {
            // Category list is fully static – block all drags
            e.setCancelled(true);
        } else if (holder.getMenuType().equals("recipe") || holder.getMenuType().equals("guide")) {
            e.setCancelled(true);
        } else {
            // Cancel drag on all other static menus
            e.setCancelled(true);
        }
    }

    private void checkRecipeMatch(Inventory inv, Player p) {
        if (inv == null || !(inv.getHolder() instanceof EdgeMenuHolder)
                || !((EdgeMenuHolder) inv.getHolder()).getMenuType().equals("crafter"))
            return;

        // Build grid: each cell = custom item ID (uppercase) or vanilla material name,
        // or "AIR"
        String[] grid = new String[9];
        // For enchant_book ingredients, store "enchant_book:<enchId>" per cell
        String[] gridRaw = new String[9];
        boolean empty = true;
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(CRAFT_SLOTS[i]);
            if (item != null && item.getType() != Material.AIR) {
                // Check if it's a specific enchant book
                if (item.getType() == Material.ENCHANTED_BOOK && item.hasItemMeta()) {
                    String bookId = item.getItemMeta().getPersistentDataContainer()
                            .getOrDefault(new NamespacedKey(plugin, "enchant_book_id"), PersistentDataType.STRING, "");
                    if (!bookId.isEmpty()) {
                        grid[i] = ("ENCHANT_BOOK:" + bookId).toUpperCase();
                        gridRaw[i] = "enchant_book:" + bookId;
                        empty = false;
                        continue;
                    }
                }
                grid[i] = item.getType().name();
                if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                        .has(Keys.customItemId, PersistentDataType.STRING)) {
                    grid[i] = item.getItemMeta().getPersistentDataContainer()
                            .get(Keys.customItemId, PersistentDataType.STRING).toUpperCase();
                }
                gridRaw[i] = grid[i];
                empty = false;
            } else {
                grid[i] = "AIR";
                gridRaw[i] = "AIR";
            }
        }

        if (empty) {
            inv.setItem(RESULT_SLOT, null);
            return;
        }

        ConfigurationSection recipes = plugin.getModule(net.elpixedge.core.item.ItemModule.class).getRecipeManager().getRecipesSection();
        if (recipes == null)
            return;

        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        net.elpixedge.core.item.RecipeManager rm = itemMod.getRecipeManager();

        for (String key : recipes.getKeys(false)) {
            String req = recipes.getString(key + ".requires");
            if (!rm.hasUnlockedRecipe(p, req))
                continue;

            List<String> shape = recipes.getStringList(key + ".shape");
            ConfigurationSection ingredients = recipes.getConfigurationSection(key + ".ingredients");
            if (shape.size() != 3 || ingredients == null)
                continue;

            boolean match = true;
            for (int r = 0; r < 3 && match; r++) {
                String row = shape.get(r);
                while (row.length() < 3)
                    row += " ";
                for (int c = 0; c < 3; c++) {
                    char ch = row.charAt(c);
                    String expected = "AIR";
                    if (ch != ' ' && ingredients.contains(String.valueOf(ch))) {
                        String ingVal = ingredients.getString(String.valueOf(ch), "AIR");
                        // Normalize all ingredients to uppercase for comparison
                        expected = ingVal.toUpperCase();
                    }
                    if (!grid[r * 3 + c].equals(expected)) {
                        match = false;
                        break;
                    }
                }
            }

            if (match) {
                ItemStack result = resolveRecipeOutputDisplay(itemMod, key);
                inv.setItem(RESULT_SLOT, result);
                return;
            }
        }
        inv.setItem(RESULT_SLOT, null);
    }

    private void enchantWeapon(Player p, ItemStack weapon, String category) {
        ConfigurationSection enchants = plugin.getConfig().getConfigurationSection("enchants." + category);
        if (enchants == null)
            return;

        net.elpixedge.core.progression.ProgressionModule progMod = plugin
                .getModule(net.elpixedge.core.progression.ProgressionModule.class);
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null)
            return;

        List<String> validEnchants = new ArrayList<>();
        for (String eId : enchants.getKeys(false)) {
            if (meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "ench_" + eId),
                    PersistentDataType.BYTE))
                continue;

            String reqLvlStr = enchants.getString(eId + ".required_level");
            if (reqLvlStr != null && progMod.getSkillLevel(p, "enchantment") < Integer.parseInt(reqLvlStr))
                continue;

            String reqCol = enchants.getString(eId + ".required_collection");
            if (reqCol != null) {
                String[] pts = reqCol.split(":");
                if (pts.length == 3 && p.getPersistentDataContainer().getOrDefault(
                        new NamespacedKey(plugin, "collvl_" + pts[1].toLowerCase()), PersistentDataType.INTEGER,
                        0) < Integer.parseInt(pts[2]))
                    continue;
            }

            List<String> targets = enchants.getStringList(eId + ".targets");
            boolean validTarget = false;
            String type = weapon.getType().name();
            for (String t : targets)
                if (type.contains(t.toUpperCase())) {
                    validTarget = true;
                    break;
                }
            if (validTarget)
                validEnchants.add(eId);
        }

        if (validEnchants.isEmpty()) {
            p.sendMessage(ChatColor.RED + "No eligible enchantments available for this item!");
            return;
        }

        String chosen = validEnchants.get(new java.util.Random().nextInt(validEnchants.size()));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "ench_" + chosen), PersistentDataType.BYTE,
                (byte) 1);

        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', enchants.getString(chosen + ".name")));
        meta.setLore(lore);
        weapon.setItemMeta(meta);

        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.5f);
        p.spawnParticle(org.bukkit.Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30);
        p.sendMessage(ChatColor.GREEN + "Enchantment applied!");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof EdgeMenuHolder))
            return;
        Player p = (Player) e.getWhoClicked();
        EdgeMenuHolder holder = (EdgeMenuHolder) e.getInventory().getHolder();

        String action = "none";
        if (e.getCurrentItem() != null && e.getCurrentItem().hasItemMeta()) {
            action = e.getCurrentItem().getItemMeta().getPersistentDataContainer().getOrDefault(Keys.guiAction,
                    PersistentDataType.STRING, "none");
        }

        if (holder.getMenuType().equals("crafter")) {
            if (e.getClickedInventory() == e.getView().getTopInventory()) {
                int slot = e.getSlot();
                boolean isCraftSlot = false;
                for (int cSlot : CRAFT_SLOTS)
                    if (slot == cSlot)
                        isCraftSlot = true;

                if (isCraftSlot) {
                    // Allow placing/removing items in craft grid
                } else if (slot == RESULT_SLOT) {
                    e.setCancelled(true);
                    ItemStack result = e.getCurrentItem();
                    if (result != null && result.getType() != Material.AIR) {
                        p.getInventory().addItem(result.clone());
                        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                        e.getInventory().setItem(RESULT_SLOT, null);
                        for (int cSlot : CRAFT_SLOTS) {
                            ItemStack cItem = e.getInventory().getItem(cSlot);
                            if (cItem != null && cItem.getType() != Material.AIR) {
                                cItem.setAmount(cItem.getAmount() - 1);
                                e.getInventory().setItem(cSlot, cItem.getAmount() > 0 ? cItem : null);
                            }
                        }
                        Bukkit.getScheduler().runTaskLater(plugin, () -> checkRecipeMatch(e.getInventory(), p), 1L);
                    }
                    return;
                } else {
                    e.setCancelled(true);
                    if (action.equals("crafter:guide")) {
                        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
                        openGuideBook(p);
                    }
                }
            } else if (e.isShiftClick()) {
                e.setCancelled(true);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> checkRecipeMatch(e.getInventory(), p), 1L);
            return;
        } else if (holder.getMenuType().equals("enchanting")) {
            if (e.getClickedInventory() == e.getView().getTopInventory()) {
                int slot = e.getSlot();
                if (slot == ENCH_ITEM_SLOT_MAIN) {
                    // allow item placement — schedule rebuild so columns refresh
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        ItemStack placed = e.getInventory().getItem(ENCH_ITEM_SLOT_MAIN);
                        pendingEnchantWeapon.put(p.getUniqueId(), placed != null ? placed.clone() : null);
                        buildEnchantingInventory(p, placed);
                    }, 1L);
                } else {
                    e.setCancelled(true);
                    // Paging
                    if (action.startsWith("enchant:page:")) {
                        String[] pts = action.split(":");
                        // enchant:page:<cat>:<pageIdx>
                        String cat = pts[2];
                        int pg = Integer.parseInt(pts[3]);
                        enchPageMap.put(p.getUniqueId() + cat, pg);
                        ItemStack w = e.getInventory().getItem(ENCH_ITEM_SLOT_MAIN);
                        buildEnchantingInventory(p, w);
                    } else if (action.startsWith("enchant:apply:") || action.startsWith("enchant:force_apply:")) {
                        boolean isForce = action.startsWith("enchant:force_apply:");
                        String[] pts = action.split(":");
                        String cat = pts[isForce ? 2 : 2]; // same index actually! "enchant:apply:basic:fire" ... wait!
                        // enchant:apply:basic:eId => length=4, pts[2] = cat, pts[3] = id
                        // enchant:force_apply:basic:eId => length=4, pts[2] = cat, pts[3] = id
                        String realCat = pts[2];
                        String enchId = pts[3];
                        ItemStack weapon = pendingEnchantWeapon.get(p.getUniqueId());
                        if (weapon == null || weapon.getType() == Material.AIR) {
                            p.sendMessage(ChatColor.RED + "Place your item in the enchanting slot first!");
                            return;
                        }
                        applyEnchant(p, weapon, realCat, enchId, isForce);
                    } else if (action.equals("enchant:remove_menu")) {
                        openEnchantRemoveMenu(p);
                    }
                }
            } else {
                // Click from player's inventory while enchanting menu is open
                // Block shift-click to prevent items being duplicated into enchanting slot
                if (e.isShiftClick())
                    e.setCancelled(true);
            }
            return;
        } else if (holder.getMenuType().equals("enchanting_cat")) {
            // Unused legacy method
            e.setCancelled(true);
            return;
        } else if (holder.getMenuType().startsWith("enchant_remove")
                || holder.getMenuType().startsWith("enchant_confirm")) {
            e.setCancelled(true);
            if (action.equals("enchant:back")) {
                // Return to main enchanting table — close handler will NOT return weapon
                // because the new inventory will be "enchanting" (part of the flow).
                openEnchantingMain(p);
            } else if (action.equals("enchant:remove_menu")) {
                openEnchantRemoveMenu(p);
            } else if (action.startsWith("enchant:confirm_remove:")) {
                String[] pts = action.split(":");
                openEnchantConfirmRemove(p, pts[2], pts[3]);
            } else if (action.startsWith("enchant:do_remove:")) {
                String[] pts = action.split(":");
                String cat = pts[2];
                String enchId = pts[3];
                ItemStack weapon = pendingEnchantWeapon.get(p.getUniqueId());
                if (weapon != null && weapon.hasItemMeta()) {
                    ItemMeta meta = weapon.getItemMeta();
                    meta.getPersistentDataContainer().remove(new NamespacedKey(plugin, "ench_" + enchId));
                    List<String> loreCopy = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    String oldName = ChatColor.translateAlternateColorCodes('&',
                            plugin.getConfig().getString("enchants." + cat + "." + enchId + ".name", enchId));
                    loreCopy.removeIf(line -> ChatColor.stripColor(line).contains(ChatColor.stripColor(oldName)));
                    meta.setLore(loreCopy);
                    weapon.setItemMeta(meta);
                    pendingEnchantWeapon.put(p.getUniqueId(), weapon);
                    p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
                    p.sendMessage(ChatColor.GREEN + "Removed " + oldName + ChatColor.GREEN + " from your item.");
                    // Reopen remove menu — still inside the enchanting flow, weapon is NOT returned
                    openEnchantRemoveMenu(p);
                }
            } else if (action.startsWith("enchant:force_apply:")) {
                String[] pts = action.split(":");
                String cat = pts[2];
                String enchId = pts[3];
                ItemStack weapon = pendingEnchantWeapon.get(p.getUniqueId());
                if (weapon != null) {
                    // applyEnchant will call buildEnchantingInventory — still inside the flow
                    applyEnchant(p, weapon, cat, enchId, true);
                }
            }
            return;
        }

        // For all other EdgeMenuHolder types: cancel all clicks then handle action
        e.setCancelled(true);
        if (action.equals("none"))
            return;

        if (action.equals("close")) {
            p.closeInventory();
            return;
        }

        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);

        if (action.equals("open:skills"))
            openMainMenu(p);
        else if (action.equals("open:status_skills"))
            openSkillStatusMenu(p);
        else if (action.equals("open:status"))
            openStatusMenu(p);
        else if (action.equals("open:quick"))
            openQuickMenu(p);
        else if (action.equals("admin:menu:main"))
            openSuperAdminMenu(p);
        else if (action.equals("admin:menu:exp"))
            openAdminExpMenu(p);
        else if (action.equals("admin:menu:tags"))
            openAdminTagMenu(p);
        else if (action.startsWith("admin:toggletag:")) {
            String tag = action.substring("admin:toggletag:".length());
            net.elpixedge.core.tag.TagManager tagMod = plugin.getModule(net.elpixedge.core.tag.TagManager.class);
            if (tagMod != null) {
                if (tagMod.hasTag(p, tag)) {
                    tagMod.removeTag(p, tag);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);
                } else {
                    tagMod.addTag(p, tag);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
                }
            }
            openAdminTagMenu(p);
        }
        else if (action.equals("admin:menu:mobs"))
            openMobEditor(p);
        else if (action.equals("admin:menu:items"))
            openAdminItemSpawner(p);
        else if (action.equals("admin:menu:enchbooks"))
            openAdminEnchantBooks(p);
        else if (action.equals("admin:menu:magic"))
            openAdminMagicItems(p);
        else if (action.startsWith("open:cat:")) {
            String[] parts = action.split(":");
            int page = 1;
            if (parts.length > 3) {
                try { page = Integer.parseInt(parts[3]); } catch (Exception ignored) {}
            }
            openCategoryMenu(p, parts[2], page);
        }
        else if (action.startsWith("admin:getitem:")) {
            String id = action.split(":")[2];
            ItemStack item = plugin.getModule(net.elpixedge.core.item.ItemModule.class).generateCustomItem(id);
            if (item != null)
                p.getInventory().addItem(item);
        } else if (action.startsWith("admin:getbook:")) {
            String id = action.split(":")[2];
            ItemStack item = plugin.getModule(net.elpixedge.core.item.ItemModule.class).generateEnchantBook(id);
            if (item != null)
                p.getInventory().addItem(item);
        } else if (action.startsWith("admin:getmagic:")) {
            String[] parts = action.split(":");
            String type = parts[2];
            String id = parts[3];
            ItemStack item = plugin.getModule(net.elpixedge.core.item.ItemModule.class).generateAnyItem(id);
            if (item != null)
                p.getInventory().addItem(item);
        } else if (action.startsWith("getegg:")) {
            String id = action.split(":")[1];
            int lvl = Integer.parseInt(action.split(":")[2]);
            p.getInventory()
                    .addItem(plugin.getModule(net.elpixedge.core.item.ItemModule.class).createCustomEgg(id, lvl));
        } else if (action.startsWith("spawnnow:")) {
            String[] parts = action.split(":");
            spawnMobForAdmin(p, parts[1], Integer.parseInt(parts[2]));
        } else if (action.startsWith("setlvl:")) {
            int lvl = Math.max(1, Integer.parseInt(action.split(":")[1]));
            selectedEditorLvl.put(p.getUniqueId(), lvl);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
            openMobEditor(p);
        } else if (action.startsWith("addlvl:")) {
            int diff = Integer.parseInt(action.split(":")[1]);
            int current = selectedEditorLvl.getOrDefault(p.getUniqueId(), 1);
            int next = Math.max(1, current + diff);
            selectedEditorLvl.put(p.getUniqueId(), next);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
            openMobEditor(p);
        } else if (action.equals("admin:reset")) {
            p.getPersistentDataContainer().getKeys().forEach(k -> {
                String kn = k.getKey();
                if (kn.startsWith("skill_lvl_") || kn.startsWith("skill_exp_") || kn.startsWith("collvl_")
                        || kn.startsWith("colexp_")) {
                    p.getPersistentDataContainer().remove(k);
                }
            });
            p.sendMessage(ChatColor.GREEN + "Progression reset.");
            p.closeInventory();
        } else if (action.startsWith("admin:addexp:skills:")) {
            int amount = Integer.parseInt(action.split(":")[3]);
            net.elpixedge.core.progression.ProgressionModule progMod = plugin
                    .getModule(net.elpixedge.core.progression.ProgressionModule.class);
            String[] cats = { "combat", "mining", "gathering", "arcane", "enchantment" };
            for (String cat : cats)
                progMod.addSkillExp(p, cat, amount);
            p.sendMessage(ChatColor.GREEN + "Added " + amount + " EXP to all skills.");
        } else if (action.startsWith("admin:addexp:collections:")) {
            int amount = Integer.parseInt(action.split(":")[3]);
            net.elpixedge.core.progression.ProgressionModule progMod = plugin
                    .getModule(net.elpixedge.core.progression.ProgressionModule.class);
            ConfigurationSection config = plugin.getConfig().getConfigurationSection("collections");
            if (config != null) {
                for (String catKey : config.getKeys(false)) {
                    ConfigurationSection catSec = config.getConfigurationSection(catKey);
                    if (catSec != null) {
                        for (String id : catSec.getKeys(false)) {
                            progMod.addCollectionExp(p, catKey, id, amount);
                        }
                    }
                }
            }
            p.sendMessage(ChatColor.AQUA + "Added " + amount + " EXP to all collections.");
        } else if (action.startsWith("back:cat:"))
            openCategoryMenu(p, action.split(":")[2]);
        else if (action.startsWith("reward_page:next:")) {
            String[] pts = action.split(":");
            openRewardsMenu(p, pts[2], pts[3], pts[4], Integer.parseInt(pts[5]) + 1);
        } else if (action.startsWith("reward_page:prev:")) {
            String[] pts = action.split(":");
            openRewardsMenu(p, pts[2], pts[3], pts[4], Integer.parseInt(pts[5]) - 1);
        } else if (action.startsWith("reward:skill:")) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
            String[] parts = action.split(":");
            // action = reward:skill:<category>:<page> OR
            // reward:skill:<category>:<page>:<backCtx>
            int page = (parts.length > 3) ? Integer.parseInt(parts[3]) : 1;
            String backCtx = (parts.length > 4) ? parts[4] : null;
            openRewardsMenu(p, "skill", parts[2], parts[2], page, backCtx);
        } else if (action.startsWith("reward:col:")) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
            String[] parts = action.split(":");
            // action = reward:col:<category>:<id>:<page>
            int page = (parts.length > 4) ? Integer.parseInt(parts[4]) : 1;
            openRewardsMenu(p, "col", parts[2], parts[3], page);
        }
        // rewards:skill:<type>:<category>:<id>:<page> — used by guide:backto and
        // recipe:backto
        else if (action.startsWith("rewards:skill:") || action.startsWith("rewards:col:")) {
            String[] pts = action.split(":");
            // format: rewards:<type>:<category>:<id>:<page> (5 parts)
            if (pts.length >= 5)
                openRewardsMenu(p, pts[1], pts[2], pts[3], Integer.parseInt(pts[4]));
        }
        // recipefrom:<recipeId>|<backContext> — opened from Rewards screen
        else if (action.startsWith("recipefrom:")) {
            String rest = action.substring("recipefrom:".length());
            int pipe = rest.indexOf('|');
            if (pipe >= 0) {
                String recipeId = rest.substring(0, pipe);
                String backCtx = rest.substring(pipe + 1);
                openRecipeView(p, recipeId, backCtx);
            } else {
                openRecipeView(p, rest);
            }
        }
        // recipe:backto:<backContext> — from RecipeView back button
        else if (action.startsWith("recipe:backto:")) {
            String backCtx = action.substring("recipe:backto:".length());
            String[] pts = backCtx.split(":");
            // backCtx: rewards:<type>:<cat>:<id>:<page>
            if (pts.length >= 5)
                openRewardsMenu(p, pts[1], pts[2], pts[3], Integer.parseInt(pts[4]));
            else
                openCustomCrafter(p);
        }
        // guide:backto:<backContext> — from GuideBook back button when opened from
        // Rewards
        else if (action.startsWith("guide:backto:")) {
            String backCtx = action.substring("guide:backto:".length());
            String[] pts = backCtx.split(":");
            if (pts.length >= 5)
                openRewardsMenu(p, pts[1], pts[2], pts[3], Integer.parseInt(pts[4]));
            else
                openCustomCrafter(p);
        } else if (action.equals("crafter:guide"))
            openGuideBook(p);
        else if (action.equals("guide:back"))
            openCustomCrafter(p);
        else if (action.startsWith("guide:view:")) {
            // guide:view:<recipeId>|<backContext> or guide:view:<recipeId>
            String rest = action.substring("guide:view:".length());
            int pipe = rest.indexOf('|');
            if (pipe >= 0) {
                openRecipeView(p, rest.substring(0, pipe), rest.substring(pipe + 1));
            } else {
                openRecipeView(p, rest);
            }
        }
    }
}
