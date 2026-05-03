package net.elpixedge.core.item;

import net.elpixedge.core.utils.Keys;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class VanillaConverter {

    public static boolean attemptConvertVanillaItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta.getPersistentDataContainer().has(Keys.dmg, PersistentDataType.DOUBLE) ||
            meta.getPersistentDataContainer().has(Keys.mining, PersistentDataType.INTEGER)) return false;

        String type = item.getType().name();
        double baseDmg = 0.0; 
        int miningPower = 0, gatheringPower = 0;
        boolean changed = false;
        double cc = 0.0, cd = 0.0;

        if (type.contains("SWORD")) {
            cc = 10.0; cd = 50.0;
            if (type.contains("WOODEN")) baseDmg = 4.0;
            else if (type.contains("STONE") || type.contains("COPPER")) baseDmg = 5.0;
            else if (type.contains("IRON")) baseDmg = 6.0;
            else if (type.contains("DIAMOND")) baseDmg = 7.0;
            else if (type.contains("NETHERITE")) baseDmg = 8.0;
        }
        else if (type.contains("AXE")) {
            cc = 5.0; cd = 75.0;
            if (type.contains("WOODEN")) { baseDmg = 7.0; miningPower = 10; }
            else if (type.contains("STONE")) { baseDmg = 9.0; miningPower = 20; }
            else if (type.contains("IRON")) { baseDmg = 9.0; miningPower = 30; }
            else if (type.contains("DIAMOND")) { baseDmg = 9.0; miningPower = 50; }
            else if (type.contains("NETHERITE")) { baseDmg = 10.0; miningPower = 70; }
        }
        else if (type.contains("SPEAR")) {
            if (type.contains("WOODEN") || type.contains("GOLDEN")) baseDmg = 1.0;
            else if (type.contains("STONE") || type.contains("COPPER")) baseDmg = 2.0;
            else if (type.contains("IRON")) baseDmg = 3.0;
            else if (type.contains("DIAMOND")) baseDmg = 4.0;
            else if (type.contains("NETHERITE")) baseDmg = 5.0;
        }
        else if (type.contains("MACE")) { baseDmg = 6.0; cd = 100.0; }
        else if (type.contains("TRIDENT")) { baseDmg = 9.0; }
        else if (type.contains("BOW") || type.contains("CROSSBOW")) { 
            baseDmg = 6.0; 
            meta.getPersistentDataContainer().set(Keys.ranged, PersistentDataType.BYTE, (byte) 1); 
        }

        if (type.contains("PICKAXE")) {
            if (type.contains("WOODEN") || type.contains("GOLDEN")) miningPower = 10;
            else if (type.contains("STONE")) miningPower = 20;
            else if (type.contains("IRON")) miningPower = 30;
            else if (type.contains("DIAMOND")) miningPower = 50;
            else if (type.contains("NETHERITE")) miningPower = 70;
        }
        if (type.contains("HOE")) {
            if (type.contains("WOODEN") || type.contains("GOLDEN")) gatheringPower = 10;
            else if (type.contains("STONE")) gatheringPower = 20;
            else if (type.contains("IRON")) gatheringPower = 30;
            else if (type.contains("DIAMOND")) gatheringPower = 50;
            else if (type.contains("NETHERITE")) gatheringPower = 70;
        }
        else if (type.equals("FISHING_ROD")) gatheringPower = 25;

        List<String> lore = meta.hasLore() ? (List<String>) meta.getLore() : new ArrayList<>();
        if (baseDmg > 0.0) {
            meta.getPersistentDataContainer().set(Keys.dmg, PersistentDataType.DOUBLE, baseDmg * 3.0);
            lore.add(net.elpixedge.core.utils.StatType.DAMAGE.formatFull(baseDmg * 3.0));
            changed = true;
        }
        if (cc > 0.0) {
            meta.getPersistentDataContainer().set(Keys.critChance, PersistentDataType.DOUBLE, cc);
            lore.add(net.elpixedge.core.utils.StatType.CRIT_CHANCE.formatFull(cc));
        }
        if (cd > 0.0) {
            meta.getPersistentDataContainer().set(Keys.critDamage, PersistentDataType.DOUBLE, cd);
            lore.add(net.elpixedge.core.utils.StatType.CRIT_DAMAGE.formatFull(cd));
        }
        if (miningPower > 0) {
            meta.getPersistentDataContainer().set(Keys.mining, PersistentDataType.INTEGER, miningPower);
            lore.add(net.elpixedge.core.utils.StatType.MINING_SPEED.formatFull(miningPower));
            changed = true;
        }
        if (gatheringPower > 0) {
            meta.getPersistentDataContainer().set(Keys.gathering, PersistentDataType.INTEGER, gatheringPower);
            lore.add(net.elpixedge.core.utils.StatType.GATHERING_SPEED.formatFull(gatheringPower));
            changed = true;
        }
        if (changed) {
            lore.add(ChatColor.DARK_GRAY + "✦ Vanilla Converted");
            meta.setLore(lore);
            item.setItemMeta(meta);
            return true;
        }
        return false;
    }
}
