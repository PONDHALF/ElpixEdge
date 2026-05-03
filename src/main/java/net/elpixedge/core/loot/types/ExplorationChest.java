package net.elpixedge.core.loot.types;

import net.elpixedge.core.loot.LootChest;
import net.elpixedge.core.loot.LootModule;
import net.elpixedge.core.utils.Keys;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public class ExplorationChest extends LootChest {

    public ExplorationChest(String id, Location location) {
        super(id, location);
    }

    @Override
    public boolean canOpen(Player player) {
        String key = "chest_opened_" + id;
        return !player.getPersistentDataContainer().has(Keys.chestOpened, PersistentDataType.STRING) || 
               !player.getPersistentDataContainer().get(Keys.chestOpened, PersistentDataType.STRING).contains(id);
    }

    @Override
    public void onOpen(Player player) {
        String current = player.getPersistentDataContainer().getOrDefault(Keys.chestOpened, PersistentDataType.STRING, "");
        if (!current.isEmpty()) current += ",";
        current += id;
        player.getPersistentDataContainer().set(Keys.chestOpened, PersistentDataType.STRING, current);
    }
}
