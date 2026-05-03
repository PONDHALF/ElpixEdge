package net.elpixedge.core.loot.types;

import lombok.Getter;
import lombok.Setter;
import net.elpixedge.core.loot.LootChest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DungeonChest extends LootChest {
    @Getter @Setter
    private String dungeonId;
    @Getter @Setter
    private String roomId;
    
    private final Set<UUID> unlockedPlayers = new HashSet<>();

    public DungeonChest(String id, Location location) {
        super(id, location);
    }

    public void unlockFor(Player player) {
        unlockedPlayers.add(player.getUniqueId());
    }

    @Override
    public boolean canOpen(Player player) {
        return unlockedPlayers.contains(player.getUniqueId());
    }

    @Override
    public void onOpen(Player player) {
        unlockedPlayers.remove(player.getUniqueId());
    }
}
