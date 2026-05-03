package net.elpixedge.core.dungeon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public class DungeonInstance {
    private final String instanceId;
    private final String dungeonId;
    private final World world;
    private final List<DungeonRoom> rooms;
    private int currentRoomIndex = 0;
    private final List<UUID> players = new ArrayList<>();
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    private final List<Entity> activeMobs = new ArrayList<>();
    private final Set<String> clearedRooms = new HashSet<>();
    private boolean completed = false;

    public DungeonInstance(String instanceId, String dungeonId, World world, List<DungeonRoom> rooms) {
        this.instanceId = instanceId;
        this.dungeonId = dungeonId;
        this.world = world;
        this.rooms = rooms;
    }

    public DungeonRoom getCurrentRoom() {
        if (currentRoomIndex < 0 || currentRoomIndex >= rooms.size()) return null;
        return rooms.get(currentRoomIndex);
    }

    public void nextRoom() {
        currentRoomIndex++;
    }

    public boolean hasNextRoom() {
        return currentRoomIndex + 1 < rooms.size();
    }
}
