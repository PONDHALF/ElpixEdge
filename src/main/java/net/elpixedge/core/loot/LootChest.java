package net.elpixedge.core.loot;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public abstract class LootChest {
    protected final String id;
    protected final Location location;
    protected Material blockType = Material.CHEST;
    protected String displayName;
    protected List<LootItem> lootTable = new ArrayList<>();
    protected double skillExp = 0;
    protected double collectionExp = 0;
    protected String collectionId;

    public LootChest(String id, Location location) {
        this.id = id;
        this.location = location;
    }

    public abstract boolean canOpen(Player player);
    public abstract void onOpen(Player player);

    @Getter
    @Setter
    public static class LootItem {
        private String itemId;
        private double chance;
        private int minAmount;
        private int maxAmount;

        public LootItem(String itemId, double chance, int min, int max) {
            this.itemId = itemId;
            this.chance = chance;
            this.minAmount = min;
            this.maxAmount = max;
        }
    }
}
