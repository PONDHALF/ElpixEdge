package net.elpixedge.core.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class EdgeMenuHolder implements InventoryHolder {
    private final String menuType;
    private final String[] extraData;

    public EdgeMenuHolder(String menuType, String... extraData) {
        this.menuType = menuType;
        this.extraData = extraData;
    }

    public String getMenuType() { return menuType; }
    public String[] getExtraData() { return extraData; }

    @Override
    public Inventory getInventory() { return null; }
}
