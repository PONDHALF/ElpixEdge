package net.elpixedge.core.loot.types;

import net.elpixedge.core.loot.LootChest;
import net.elpixedge.core.utils.Keys;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public class ExplorationChest extends LootChest {

    public ExplorationChest(String id) {
        super(id);
    }

    public ExplorationChest(String id, Location location) {
        super(id, location);
    }

    @Override
    public boolean canOpen(Player player) {
        String opened = player.getPersistentDataContainer().getOrDefault(Keys.chestOpened, PersistentDataType.STRING, "");
        return !opened.contains(id);
    }

    @Override
    public void onOpen(Player player) {
        String current = player.getPersistentDataContainer().getOrDefault(Keys.chestOpened, PersistentDataType.STRING, "");
        if (!current.isEmpty()) current += ",";
        current += id;
        player.getPersistentDataContainer().set(Keys.chestOpened, PersistentDataType.STRING, current);
    }
}
