package net.elpixedge.core.dungeon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class DungeonRoom {
    private final String id;
    private final String name;
    private final List<MobSpawn> mobs = new ArrayList<>();
    private ClearCondition clearCondition = ClearCondition.KILL_ALL;
    private String targetMobId; // For KILL_TARGET condition
    private Location spawnPoint;
    private Location exitPortalMin;
    private Location exitPortalMax;

    public DungeonRoom(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public enum ClearCondition {
        KILL_ALL,
        KILL_TARGET,
        NONE
    }

    @Getter
    @Setter
    public static class MobSpawn {
        private String mobId;
        private int amount;
        private int level;
        private double x, y, z;

        public MobSpawn(String mobId, int amount, int level, double x, double y, double z) {
            this.mobId = mobId;
            this.amount = amount;
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
