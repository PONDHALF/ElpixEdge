package net.elpixedge.core.loot.types;

import lombok.Getter;
import lombok.Setter;
import net.elpixedge.core.loot.LootChest;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class EventChest extends LootChest {
    @Getter @Setter
    private long respawnTimeMs;
    private final Map<UUID, Long> playerCooldowns = new HashMap<>();

    public EventChest(String id, Location location) {
        super(id, location);
    }

    @Override
    public boolean canOpen(Player player) {
        Long nextOpen = playerCooldowns.get(player.getUniqueId());
        return nextOpen == null || System.currentTimeMillis() >= nextOpen;
    }

    @Override
    public void onOpen(Player player) {
        if (respawnTimeMs > 0) {
            playerCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + respawnTimeMs);
        }
    }

    public long getRemainingCooldown(Player player) {
        Long nextOpen = playerCooldowns.get(player.getUniqueId());
        if (nextOpen == null) return 0;
        return Math.max(0, nextOpen - System.currentTimeMillis());
    }
}
