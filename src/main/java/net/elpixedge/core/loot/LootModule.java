package net.elpixedge.core.loot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.loot.types.DungeonChest;
import net.elpixedge.core.loot.types.EventChest;
import net.elpixedge.core.loot.types.ExplorationChest;
import net.elpixedge.core.progression.ProgressionModule;
import net.elpixedge.core.utils.Keys;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LootModule implements Module, Listener {
    private final ElpixEdge plugin;
    private final Map<String, LootChest> templates = new HashMap<>();
    private final Map<String, LootChest> chests = new HashMap<>();
    private final Map<UUID, Set<String>> visibleChests = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, TextDisplay>> activeHolograms = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public LootModule(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadConfig();

        // Updater task for packet-based visibility and holograms
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updatePlayerChests(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        plugin.getLogger().info("LootModule enabled.");
    }

    @Override
    public void onDisable() {
        for (Map<String, TextDisplay> playerHolos : activeHolograms.values()) {
            for (TextDisplay display : playerHolos.values()) {
                display.remove();
            }
        }
    }

    public void loadConfig() {
        templates.clear();
        chests.clear();

        // 1. Load Templates from YAML
        File yamlFile = new File(plugin.getDataFolder(), "loot_chests.yml");
        if (!yamlFile.exists()) {
            plugin.saveResource("loot_chests.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yamlFile);
        ConfigurationSection sec = cfg.getConfigurationSection("templates");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection cSec = sec.getConfigurationSection(id);
                if (cSec == null) continue;
                templates.put(id, loadTemplate(id, cSec));
            }
        }

        // 2. Load Instances from JSON
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists()) dataDir.mkdirs();
        
        File jsonFile = new File(dataDir, "loot_chests.json");
        List<ChestData> dataList = new ArrayList<>();
        boolean needsCleanup = false;

        if (jsonFile.exists()) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                List<ChestData> loadedData = gson.fromJson(reader, new TypeToken<List<ChestData>>(){}.getType());
                if (loadedData != null) {
                    for (ChestData data : loadedData) {
                        LootChest template = templates.get(data.template);
                        if (template != null) {
                            LootChest instance = createInstanceFromTemplate(data.id, data, template);
                            chests.put(data.id, instance);
                            dataList.add(data);
                        } else {
                            // Template was deleted from YAML!
                            needsCleanup = true;
                            
                            // Clear visual block for online players before removing
                            clearVisualBlock(data);
                            
                            plugin.getLogger().warning("Loot Chest instance '" + data.id + "' refers to missing template '" + data.template + "'. Removing from JSON and clearing block.");
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load loot_chests.json: " + e.getMessage());
            }
        }

        // 3. Save cleaned data back to JSON if needed
        if (needsCleanup) {
            saveInstancesToJson(jsonFile, dataList);
        }
    }

    private void clearVisualBlock(ChestData data) {
        World world = Bukkit.getWorld(data.world);
        if (world == null) return;
        Location loc = new Location(world, data.x, data.y, data.z);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(world) && p.getLocation().distanceSquared(loc) < 100 * 100) {
                p.sendBlockChange(loc, Material.AIR.createBlockData());
            }
        }
    }

    private void saveInstancesToJson(File file, List<ChestData> dataList) {
        try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
            gson.toJson(dataList, writer);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save loot_chests.json: " + e.getMessage());
        }
    }

    private LootChest loadTemplate(String id, ConfigurationSection sec) {
        String type = sec.getString("type", "exploration");
        LootChest chest = switch (type.toLowerCase()) {
            case "event" -> new EventChest(id, sec.getInt("respawn_time_sec", 300));
            case "dungeon" -> new DungeonChest(id, sec.getString("dungeon_id"), sec.getString("room_id"));
            default -> new ExplorationChest(id);
        };

        chest.setDisplayName(ChatColor.translateAlternateColorCodes('&', sec.getString("display_name", "")));
        chest.setBlockType(Material.valueOf(sec.getString("block_type", "CHEST").toUpperCase()));
        chest.setSkillExp(sec.getDouble("skill_exp", 0));
        chest.setCollectionId(sec.getString("collection_id"));
        chest.setCollectionExp(sec.getDouble("collection_exp", 0));

        List<Map<?, ?>> rewards = sec.getMapList("rewards");
        for (Map<?, ?> r : rewards) {
            String itemId = (String) r.get("item_id");
            double chance = ((Number) r.getOrDefault("chance", 1.0)).doubleValue();
            int min = ((Number) r.getOrDefault("min", 1)).intValue();
            int max = ((Number) r.getOrDefault("max", 1)).intValue();
            chest.getLootTable().add(new LootChest.LootItem(itemId, chance, min, max));
        }
        return chest;
    }

    private LootChest createInstanceFromTemplate(String instanceId, ChestData data, LootChest template) {
        LootChest instance;
        if (template instanceof EventChest ec) {
            instance = new EventChest(instanceId, ec.getRespawnTimeSec());
        } else if (template instanceof DungeonChest dc) {
            instance = new DungeonChest(instanceId, dc.getDungeonId(), dc.getRoomId());
        } else {
            instance = new ExplorationChest(instanceId);
        }

        instance.setDisplayName(template.getDisplayName());
        instance.setBlockType(template.getBlockType());
        instance.setSkillExp(template.getSkillExp());
        instance.setCollectionId(template.getCollectionId());
        instance.setCollectionExp(template.getCollectionExp());
        instance.getLootTable().addAll(template.getLootTable());

        World world = Bukkit.getWorld(data.world);
        instance.setLocation(new Location(world, data.x, data.y, data.z));
        
        return instance;
    }

    private void updatePlayerChests(Player player) {
        Set<String> currentlyVisible = visibleChests.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        
        for (LootChest chest : chests.values()) {
            if (chest.getLocation() == null || chest.getLocation().getWorld() == null) continue;
            if (!chest.getLocation().getWorld().equals(player.getWorld())) continue;

            double dist = player.getLocation().distanceSquared(chest.getLocation());
            boolean shouldBeVisible = dist < 100 * 100 && chest.canSee(player);

            if (shouldBeVisible && !currentlyVisible.contains(chest.getId())) {
                player.sendBlockChange(chest.getLocation(), chest.getBlockType().createBlockData());
                currentlyVisible.add(chest.getId());
                updateHologram(player, chest);
            } else if (!shouldBeVisible && currentlyVisible.contains(chest.getId())) {
                player.sendBlockChange(chest.getLocation(), chest.getLocation().getBlock().getBlockData());
                currentlyVisible.remove(chest.getId());
                removeHologram(player, chest.getId());
            } else if (shouldBeVisible) {
                updateHologram(player, chest);
            }
        }
    }

    private void updateHologram(Player player, LootChest chest) {
        if (chest instanceof EventChest ec) {
            long remaining = ec.getRemainingCooldown(player);
            if (remaining > 0) {
                String text = ChatColor.RED + "Respawning in: " + ChatColor.YELLOW + (remaining / 1000) + "s";
                showHologram(player, chest.getId(), chest.getLocation().clone().add(0.5, 1.2, 0.5), text);
            } else {
                removeHologram(player, chest.getId());
            }
        } else if (chest.getDisplayName() != null) {
            showHologram(player, chest.getId(), chest.getLocation().clone().add(0.5, 1.2, 0.5), chest.getDisplayName());
        }
    }

    private void showHologram(Player player, String chestId, Location loc, String text) {
        Map<String, TextDisplay> playerHolos = activeHolograms.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        TextDisplay display = playerHolos.get(chestId);
        
        if (display == null || !display.isValid()) {
            display = player.getWorld().spawn(loc, TextDisplay.class, ent -> {
                ent.setText(text);
                ent.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                ent.setVisibleByDefault(false);
            });
            player.showEntity(plugin, display);
            playerHolos.put(chestId, display);
        } else {
            display.setText(text);
        }
    }

    private void removeHologram(Player player, String chestId) {
        Map<String, TextDisplay> playerHolos = activeHolograms.get(player.getUniqueId());
        if (playerHolos != null) {
            TextDisplay display = playerHolos.remove(chestId);
            if (display != null) display.remove();
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getItemMeta() == null) return;
        
        NamespacedKey key = new NamespacedKey(plugin, "loot_template_id");
        String templateId = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        
        if (templateId != null) {
            event.setCancelled(true); // Don't place a real block
            
            LootChest template = templates.get(templateId);
            if (template == null) {
                event.getPlayer().sendMessage(ChatColor.RED + "Template '" + templateId + "' no longer exists!");
                return;
            }

            // Create new instance data
            String instanceId = templateId + "_" + UUID.randomUUID().toString().substring(0, 5);
            Location loc = event.getBlock().getLocation();
            
            ChestData newData = new ChestData();
            newData.id = instanceId;
            newData.template = templateId;
            newData.world = loc.getWorld().getName();
            newData.x = loc.getX();
            newData.y = loc.getY();
            newData.z = loc.getZ();

            // Register in memory
            LootChest instance = createInstanceFromTemplate(instanceId, newData, template);
            chests.put(instanceId, instance);

            // Save to JSON
            addInstanceToJson(newData);
            
            event.getPlayer().sendMessage(ChatColor.GREEN + "Loot Chest '" + template.getDisplayName() + ChatColor.GREEN + "' placed and saved!");
            event.getPlayer().playSound(loc, Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.2f);
        }
    }

    private void addInstanceToJson(ChestData newData) {
        File jsonFile = new File(plugin.getDataFolder(), "data/loot_chests.json");
        List<ChestData> dataList = new ArrayList<>();
        
        if (jsonFile.exists()) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                List<ChestData> loadedData = gson.fromJson(reader, new TypeToken<List<ChestData>>(){}.getType());
                if (loadedData != null) dataList.addAll(loadedData);
            } catch (Exception ignored) {}
        }
        
        dataList.add(newData);
        saveInstancesToJson(jsonFile, dataList);
    }

    public void giveChestItem(Player player, String templateId) {
        LootChest template = templates.get(templateId);
        if (template == null) {
            player.sendMessage(ChatColor.RED + "Loot Template '" + templateId + "' not found!");
            return;
        }

        ItemStack item = new ItemStack(template.getBlockType());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Loot Chest: " + template.getDisplayName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.YELLOW + template.getClass().getSimpleName().replace("Chest", ""));
            lore.add(ChatColor.GRAY + "Place this to register a new chest.");
            meta.setLore(lore);
            
            NamespacedKey key = new NamespacedKey(plugin, "loot_template_id");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, templateId);
            item.setItemMeta(meta);
        }

        player.getInventory().addItem(item);
        player.sendMessage(ChatColor.GREEN + "Received Loot Chest item for: " + template.getDisplayName());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        Location loc = event.getClickedBlock().getLocation();
        Player player = event.getPlayer();

        // --- Admin Removal (Sneak + Left Click) ---
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.isOp() && player.isSneaking()) {
            for (LootChest chest : chests.values()) {
                if (chest.getLocation().equals(loc)) {
                    removeChestInstance(chest.getId());
                    player.sendMessage(ChatColor.RED + "Loot Chest removed successfully.");
                    player.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f);
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        for (LootChest chest : chests.values()) {
            if (chest.getLocation().equals(loc) && chest.canSee(player)) {
                event.setCancelled(true);
                openChest(player, chest);
                return;
            }
        }
    }

    private void removeChestInstance(String id) {
        LootChest chest = chests.remove(id);
        if (chest == null) return;

        // Clear visual for everyone
        ChestData dummy = new ChestData();
        dummy.id = id;
        dummy.world = chest.getLocation().getWorld().getName();
        dummy.x = chest.getLocation().getX();
        dummy.y = chest.getLocation().getY();
        dummy.z = chest.getLocation().getZ();
        clearVisualBlock(dummy);

        // Remove from JSON
        File jsonFile = new File(plugin.getDataFolder(), "data/loot_chests.json");
        if (jsonFile.exists()) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                List<ChestData> dataList = gson.fromJson(reader, new TypeToken<List<ChestData>>(){}.getType());
                if (dataList != null) {
                    dataList.removeIf(d -> d.id.equals(id));
                    saveInstancesToJson(jsonFile, dataList);
                }
            } catch (Exception ignored) {}
        }
    }

    private void openChest(Player player, LootChest chest) {
        if (!chest.canOpen(player)) {
            player.sendMessage(ChatColor.RED + "You cannot open this chest yet!");
            return;
        }

        // Play visual/sound effects
        player.playSound(chest.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        player.spawnParticle(Particle.HAPPY_VILLAGER, chest.getLocation().clone().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3);

        // Process Rewards
        ProgressionModule progression = plugin.getModule(ProgressionModule.class);
        if (progression != null) {
            progression.addExp(player, chest.getSkillExp());
            if (chest.getCollectionId() != null) {
                progression.addCollectionExp(player, chest.getCollectionId(), chest.getCollectionExp());
            }
        }

        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        for (LootChest.LootItem item : chest.getLootTable()) {
            if (Math.random() <= item.getChance()) {
                int amount = item.getMinAmount() + (int) (Math.random() * (item.getMaxAmount() - item.getMinAmount() + 1));
                ItemStack is = null;
                
                String itemId = item.getItemId();
                if (itemId.startsWith("minecraft:")) {
                    try {
                        Material mat = Material.valueOf(itemId.replace("minecraft:", "").toUpperCase());
                        is = new ItemStack(mat);
                    } catch (Exception ignored) {}
                } else {
                    if (itemMod != null) {
                        is = itemMod.generateCustomItem(itemId);
                    }
                }

                if (is != null) {
                    is.setAmount(amount);
                    if (player.getInventory().firstEmpty() != -1) {
                        player.getInventory().addItem(is);
                    } else {
                        player.getWorld().dropItemNaturally(player.getLocation(), is);
                    }
                }
            }
        }

        // Mark as opened
        chest.onOpen(player);
        
        // Save state for exploration chests
        if (chest instanceof ExplorationChest) {
            player.getPersistentDataContainer().set(new NamespacedKey(plugin, "opened_" + chest.getId()), PersistentDataType.BYTE, (byte) 1);
        }

        player.sendMessage(ChatColor.GREEN + "You opened a " + chest.getDisplayName() + "!");
        
        // Visual feedback (fake closing)
        new BukkitRunnable() {
            @Override
            public void run() {
                updatePlayerChests(player);
            }
        }.runTaskLater(plugin, 2L);
    }

    public LootChest getChest(String id) {
        return chests.get(id);
    }

    private static class ChestData {
        String id;
        String template;
        String world;
        double x, y, z;
    }
}
