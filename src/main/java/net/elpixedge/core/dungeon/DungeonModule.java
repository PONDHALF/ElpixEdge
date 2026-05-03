package net.elpixedge.core.dungeon;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.dungeon.provider.DungeonProvider;
import net.elpixedge.core.dungeon.provider.SlimeDungeonProvider;
import net.elpixedge.core.loot.LootModule;
import net.elpixedge.core.loot.types.DungeonChest;
import net.elpixedge.core.utils.Keys;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DungeonModule implements Module, Listener {
    private final ElpixEdge plugin;
    private final DungeonProvider provider;
    private final Map<String, DungeonDef> dungeonDefs = new HashMap<>();
    private final Map<String, DungeonInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerInstances = new ConcurrentHashMap<>();

    public DungeonModule(ElpixEdge plugin) {
        this.plugin = plugin;
        this.provider = new SlimeDungeonProvider();
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadConfig();
        
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    checkExitPortal(player);
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
        
        plugin.getLogger().info("DungeonModule enabled.");
    }

    @Override
    public void onDisable() {
        for (DungeonInstance instance : activeInstances.values()) {
            provider.deleteInstance(instance.getWorld());
        }
    }

    public void loadConfig() {
        dungeonDefs.clear();
        File f = new File(plugin.getDataFolder(), "dungeons.yml");
        if (!f.exists()) {
            plugin.saveResource("dungeons.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = cfg.getConfigurationSection("dungeons");
        if (sec == null) return;

        for (String id : sec.getKeys(false)) {
            ConfigurationSection dSec = sec.getConfigurationSection(id);
            if (dSec == null) continue;

            DungeonDef def = new DungeonDef();
            def.id = id;
            def.templateName = dSec.getString("template");
            
            ConfigurationSection rSec = dSec.getConfigurationSection("rooms");
            if (rSec != null) {
                for (String rId : rSec.getKeys(false)) {
                    ConfigurationSection roomSec = rSec.getConfigurationSection(rId);
                    if (roomSec == null) continue;

                    DungeonRoom room = new DungeonRoom(rId, roomSec.getString("name", rId));
                    room.setClearCondition(DungeonRoom.ClearCondition.valueOf(roomSec.getString("clear_condition", "KILL_ALL").toUpperCase()));
                    room.setTargetMobId(roomSec.getString("target_mob"));
                    
                    String spawnStr = roomSec.getString("spawn_point", "0,65,0");
                    String[] sp = spawnStr.split(",");
                    room.setSpawnPoint(new Location(null, Double.parseDouble(sp[0].trim()), Double.parseDouble(sp[1].trim()), Double.parseDouble(sp[2].trim())));

                    String exitMinStr = roomSec.getString("exit_min");
                    if (exitMinStr != null) {
                        String[] em = exitMinStr.split(",");
                        room.setExitPortalMin(new Location(null, Double.parseDouble(em[0].trim()), Double.parseDouble(em[1].trim()), Double.parseDouble(em[2].trim())));
                    }
                    String exitMaxStr = roomSec.getString("exit_max");
                    if (exitMaxStr != null) {
                        String[] ex = exitMaxStr.split(",");
                        room.setExitPortalMax(new Location(null, Double.parseDouble(ex[0].trim()), Double.parseDouble(ex[1].trim()), Double.parseDouble(ex[2].trim())));
                    }

                    List<Map<?, ?>> mobList = roomSec.getMapList("mobs");
                    for (Map<?, ?> mobMap : mobList) {
                        String mobId = (String) mobMap.get("mob_id");
                        int amount = mobMap.containsKey("amount") ? ((Number) mobMap.get("amount")).intValue() : 1;
                        int level = mobMap.containsKey("level") ? ((Number) mobMap.get("level")).intValue() : 1;
                        String locStr = mobMap.containsKey("loc") ? (String) mobMap.get("loc") : "0,64,0";
                        String[] lp = locStr.split(",");
                        room.getMobs().add(new DungeonRoom.MobSpawn(mobId, amount, level, Double.parseDouble(lp[0].trim()), Double.parseDouble(lp[1].trim()), Double.parseDouble(lp[2].trim())));
                    }
                    def.rooms.add(room);
                }
            }
            dungeonDefs.put(id, def);
        }
    }

    public void enterDungeon(Player player, String dungeonId) {
        DungeonDef def = dungeonDefs.get(dungeonId);
        if (def == null) return;

        String instanceId = UUID.randomUUID().toString().substring(0, 8);
        Location returnLoc = player.getLocation().clone();
        
        provider.createInstance(instanceId, def.templateName).thenAccept(world -> {
            DungeonInstance instance = new DungeonInstance(instanceId, dungeonId, world, def.rooms);
            instance.getPlayers().add(player.getUniqueId());
            instance.getReturnLocations().put(player.getUniqueId(), returnLoc);
            
            activeInstances.put(instanceId, instance);
            playerInstances.put(player.getUniqueId(), instanceId);

            startRoom(instance, 0);
        });
    }

    private void startRoom(DungeonInstance instance, int roomIndex) {
        DungeonRoom room = instance.getRooms().get(roomIndex);
        Location spawn = room.getSpawnPoint().clone();
        spawn.setWorld(instance.getWorld());

        for (UUID uuid : instance.getPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.teleport(spawn);
                p.sendTitle(ChatColor.GOLD + room.getName(), ChatColor.GRAY + "Room " + (roomIndex + 1) + " of " + instance.getRooms().size(), 10, 40, 10);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
        }
        spawnRoomMobs(instance, room);
    }

    private void spawnRoomMobs(DungeonInstance instance, DungeonRoom room) {
        instance.getActiveMobs().clear();
        for (DungeonRoom.MobSpawn spawn : room.getMobs()) {
            Location loc = new Location(instance.getWorld(), spawn.getX(), spawn.getY(), spawn.getZ());
            for (int i = 0; i < spawn.getAmount(); i++) {
                LivingEntity mob = (LivingEntity) instance.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
                mob.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, spawn.getMobId());
                mob.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, spawn.getLevel());
                instance.getActiveMobs().add(mob);
            }
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        for (DungeonInstance instance : activeInstances.values()) {
            if (instance.getActiveMobs().remove(mob)) {
                checkRoomClear(instance);
                return;
            }
        }
    }

    private void checkRoomClear(DungeonInstance instance) {
        DungeonRoom room = instance.getCurrentRoom();
        boolean cleared = false;
        if (room.getClearCondition() == DungeonRoom.ClearCondition.KILL_ALL) {
            cleared = instance.getActiveMobs().isEmpty();
        } else if (room.getClearCondition() == DungeonRoom.ClearCondition.KILL_TARGET) {
            cleared = instance.getActiveMobs().stream().noneMatch(m -> 
                room.getTargetMobId().equals(m.getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING)));
        }

        if (cleared) {
            instance.getClearedRooms().add(room.getId());
            for (UUID uuid : instance.getPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.sendMessage(ChatColor.GREEN + "Room Cleared!");
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                    
                    LootModule loot = plugin.getModule(LootModule.class);
                    if (loot != null) {
                        String chestId = instance.getDungeonId() + "_" + room.getId() + "_loot";
                        net.elpixedge.core.loot.LootChest chest = loot.getChest(chestId);
                        if (chest instanceof DungeonChest dc) dc.unlockFor(p);
                    }
                }
            }
        }
    }

    private void checkExitPortal(Player player) {
        String instanceId = playerInstances.get(player.getUniqueId());
        if (instanceId == null) return;

        DungeonInstance instance = activeInstances.get(instanceId);
        DungeonRoom room = instance.getCurrentRoom();
        if (room == null || room.getExitPortalMin() == null || room.getExitPortalMax() == null) return;

        if (room.getClearCondition() != DungeonRoom.ClearCondition.NONE && !instance.getClearedRooms().contains(room.getId())) {
            return;
        }

        if (isInside(player.getLocation(), room.getExitPortalMin(), room.getExitPortalMax())) {
            if (instance.hasNextRoom()) {
                instance.nextRoom();
                startRoom(instance, instance.getCurrentRoomIndex());
            } else {
                exitDungeon(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        exitDungeon(event.getPlayer());
    }

    private boolean isInside(Location loc, Location min, Location max) {
        return loc.getX() >= Math.min(min.getX(), max.getX()) && loc.getX() <= Math.max(min.getX(), max.getX()) &&
               loc.getY() >= Math.min(min.getY(), max.getY()) && loc.getY() <= Math.max(min.getY(), max.getY()) &&
               loc.getZ() >= Math.min(min.getZ(), max.getZ()) && loc.getZ() <= Math.max(min.getZ(), max.getZ());
    }

    public void exitDungeon(Player player) {
        String instanceId = playerInstances.remove(player.getUniqueId());
        if (instanceId != null) {
            DungeonInstance instance = activeInstances.get(instanceId);
            instance.getPlayers().remove(player.getUniqueId());
            Location returnLoc = instance.getReturnLocations().remove(player.getUniqueId());
            player.teleport(returnLoc != null ? returnLoc : Bukkit.getWorlds().get(0).getSpawnLocation());
            player.sendMessage(ChatColor.YELLOW + "You have left the dungeon.");

            if (instance.getPlayers().isEmpty()) {
                activeInstances.remove(instanceId);
                provider.deleteInstance(instance.getWorld());
            }
        }
    }

    private static class DungeonDef {
        String id;
        String templateName;
        List<DungeonRoom> rooms = new ArrayList<>();
    }
}
