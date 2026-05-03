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
                java.lang.reflect.Type listType = new TypeToken<List<ChestData>>(){}.getType();
                List<ChestData> loadedData = gson.fromJson(reader, listType);
                if (loadedData != null) {
                    for (ChestData data : loadedData) {
                        LootChest template = templates.get(data.template);
                        if (template != null) {
                            LootChest instance = createInstanceFromTemplate(data.id, data, template);
                            chests.put(data.id, instance);
                            dataList.add(data);
                        } else {
                            needsCleanup = true;
                            clearVisualBlock(data);
                            plugin.getLogger().warning("Loot Chest instance '" + data.id + "' refers to missing template '" + data.template + "'. Removing.");
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load loot_chests.json: " + e.getMessage());
            }
        }

        if (needsCleanup) {
            saveInstancesToJson(jsonFile, dataList);
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
            double chance = r.containsKey("chance") ? ((Number) r.get("chance")).doubleValue() : 1.0;
            int min = r.containsKey("min") ? ((Number) r.get("min")).intValue() : 1;
            int max = r.containsKey("max") ? ((Number) r.get("max")).intValue() : 1;
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
        if (world != null) {
            instance.setLocation(new Location(world, data.x, data.y, data.z));
        }
        
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

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || item.getItemMeta() == null) return;
        
        NamespacedKey key = new NamespacedKey(plugin, "loot_template_id");
        String templateId = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        
        if (templateId != null) {
            event.setCancelled(true);
            
            LootChest template = templates.get(templateId);
            if (template == null) {
                event.getPlayer().sendMessage(ChatColor.RED + "Template '" + templateId + "' no longer exists!");
                return;
            }

            String instanceId = templateId + "_" + UUID.randomUUID().toString().substring(0, 5);
            Location loc = event.getBlock().getLocation();
            
            ChestData newData = new ChestData();
            newData.id = instanceId;
            newData.template = templateId;
            newData.world = loc.getWorld().getName();
            newData.x = loc.getX();
            newData.y = loc.getY();
            newData.z = loc.getZ();

            LootChest instance = createInstanceFromTemplate(instanceId, newData, template);
            chests.put(instanceId, instance);
            addInstanceToJson(newData);
            
            event.getPlayer().sendMessage(ChatColor.GREEN + "Loot Chest '" + template.getDisplayName() + ChatColor.GREEN + "' placed!");
        }
    }

    private void addInstanceToJson(ChestData newData) {
        File jsonFile = new File(plugin.getDataFolder(), "data/loot_chests.json");
        List<ChestData> dataList = new ArrayList<>();
        if (jsonFile.exists()) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                java.lang.reflect.Type listType = new TypeToken<List<ChestData>>(){}.getType();
                List<ChestData> loadedData = gson.fromJson(reader, listType);
                if (loadedData != null) dataList.addAll(loadedData);
            } catch (Exception ignored) {}
        }
        dataList.add(newData);
        saveInstancesToJson(jsonFile, dataList);
    }

    public void giveChestItem(Player player, String templateId) {
        LootChest template = templates.get(templateId);
        if (template == null) return;

        ItemStack item = new ItemStack(template.getBlockType());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Loot Chest: " + template.getDisplayName());
            NamespacedKey key = new NamespacedKey(plugin, "loot_template_id");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, templateId);
            item.setItemMeta(meta);
        }
        player.getInventory().addItem(item);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Location loc = event.getClickedBlock().getLocation();
        Player player = event.getPlayer();

        if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.isOp() && player.isSneaking()) {
            for (LootChest chest : chests.values()) {
                if (chest.getLocation() != null && chest.getLocation().equals(loc)) {
                    removeChestInstance(chest.getId());
                    player.sendMessage(ChatColor.RED + "Loot Chest removed.");
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        for (LootChest chest : chests.values()) {
            if (chest.getLocation() != null && chest.getLocation().equals(loc) && chest.canSee(player)) {
                event.setCancelled(true);
                openChest(player, chest);
                return;
            }
        }
    }

    private void removeChestInstance(String id) {
        LootChest chest = chests.remove(id);
        if (chest == null) return;

        ChestData dummy = new ChestData();
        dummy.world = chest.getLocation().getWorld().getName();
        dummy.x = chest.getLocation().getX();
        dummy.y = chest.getLocation().getY();
        dummy.z = chest.getLocation().getZ();
        clearVisualBlock(dummy);

        File jsonFile = new File(plugin.getDataFolder(), "data/loot_chests.json");
        if (jsonFile.exists()) {
            try (java.io.FileReader reader = new java.io.FileReader(jsonFile)) {
                java.lang.reflect.Type listType = new TypeToken<List<ChestData>>(){}.getType();
                List<ChestData> dataList = gson.fromJson(reader, listType);
                if (dataList != null) {
                    dataList.removeIf(d -> d.id.equals(id));
                    saveInstancesToJson(jsonFile, dataList);
                }
            } catch (Exception ignored) {}
        }
    }

    private void openChest(Player player, LootChest chest) {
        if (!chest.canOpen(player)) return;

        ProgressionModule progression = plugin.getModule(ProgressionModule.class);
        if (progression != null) {
            progression.addSkillExp(player, "Exploration", chest.getSkillExp());
            if (chest.getCollectionId() != null) {
                progression.addCollectionExp(player, "exploration", chest.getCollectionId(), chest.getCollectionExp());
            }
        }

        net.elpixedge.core.item.ItemModule itemMod = plugin.getModule(net.elpixedge.core.item.ItemModule.class);
        for (LootChest.LootItem item : chest.getLootTable()) {
            if (Math.random() <= item.getChance()) {
                ItemStack is = null;
                if (item.getItemId().startsWith("minecraft:")) {
                    try {
                        Material mat = Material.valueOf(item.getItemId().replace("minecraft:", "").toUpperCase());
                        is = new ItemStack(mat);
                    } catch (Exception ignored) {}
                } else if (itemMod != null) {
                    is = itemMod.generateCustomItem(item.getItemId());
                }

                if (is != null) {
                    int amount = item.getMinAmount() + (int) (Math.random() * (item.getMaxAmount() - item.getMinAmount() + 1));
                    is.setAmount(amount);
                    player.getInventory().addItem(is);
                }
            }
        }

        chest.onOpen(player);
        updatePlayerChests(player);
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
