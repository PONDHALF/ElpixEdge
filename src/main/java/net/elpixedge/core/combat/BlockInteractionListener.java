package net.elpixedge.core.combat;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import net.elpixedge.core.gui.GuiModule;
import net.elpixedge.core.utils.Keys;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public class BlockInteractionListener implements Module, Listener {

    private final ElpixEdge plugin;

    public BlockInteractionListener(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("BlockInteractionListener enabled.");
    }

    @Override
    public void onDisable() {}

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (!item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(Keys.customItemId, PersistentDataType.STRING)) return;

        String customId = item.getItemMeta().getPersistentDataContainer().get(Keys.customItemId, PersistentDataType.STRING);

        if (customId.equals("edge_crafter_block") || customId.equals("edge_enchant_block")) {
            Block b = e.getBlockPlaced();
            if (b.getState() instanceof TileState) {
                TileState state = (TileState) b.getState();
                state.getPersistentDataContainer().set(Keys.customBlock, PersistentDataType.STRING, customId);
                state.update();
                e.getPlayer().sendMessage(ChatColor.GREEN + "Placed " + customId.replace("_", " ") + "!");
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click on block with main hand
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block b = event.getClickedBlock();
        if (b == null) return;

        Player p = event.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();

        net.elpixedge.core.player.PlayerModule pMod = plugin.getModule(net.elpixedge.core.player.PlayerModule.class);
        if (pMod != null && !pMod.canUseItem(p, item)) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You don't meet the skill requirements to use this tool!");
            return;
        }

        // Custom Mob Spawner logic
        if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(Keys.customMobId, PersistentDataType.STRING)) {
            if (b.getType() == org.bukkit.Material.SPAWNER || b.getType() == org.bukkit.Material.TRIAL_SPAWNER) {
                String mobId = item.getItemMeta().getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING);
                int lvl = item.getItemMeta().getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
                
                // Allow vanilla to change the spawner type, but we set the NBT next tick
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (b.getState() instanceof TileState state) {
                        state.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, mobId);
                        state.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, lvl);
                        state.update();
                    }
                }, 1L);
                p.sendMessage(ChatColor.GREEN + "Updated spawner with " + mobId.replace("_", " ") + " Lv." + lvl + "!");
                return;
            } else {
                String mobId = item.getItemMeta().getPersistentDataContainer().get(Keys.customMobId, PersistentDataType.STRING);
                int lvl = item.getItemMeta().getPersistentDataContainer().getOrDefault(Keys.lvl, PersistentDataType.INTEGER, 1);
                try {
                    org.bukkit.entity.EntityType eType;
                    try {
                        String baseEntStr = plugin.getConfig().getString("mobs." + mobId.toLowerCase() + ".base_entity", mobId);
                        eType = org.bukkit.entity.EntityType.valueOf(baseEntStr.toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        eType = org.bukkit.entity.EntityType.ENDERMAN; // fallback for custom mobs
                    }
                    Class<? extends org.bukkit.entity.Entity> eClass = eType.getEntityClass();
                    if (eClass != null && org.bukkit.entity.LivingEntity.class.isAssignableFrom(eClass)) {
                        event.setCancelled(true);
                        org.bukkit.Location spawnLoc = b.getLocation().add(event.getBlockFace().getDirection()).add(0.5, 0, 0.5);
                        final String fMobId = mobId;
                        final int fLvl = lvl;
                        org.bukkit.entity.LivingEntity mob = (org.bukkit.entity.LivingEntity) p.getWorld().spawn(spawnLoc, eClass, entity -> {
                            entity.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, fLvl);
                            entity.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, fMobId.toLowerCase());
                            if (entity instanceof org.bukkit.entity.LivingEntity le) {
                                double tierHpMult = plugin.getConfig().getDouble("mobs." + fMobId.toLowerCase() + ".tier_hp_mult", 10.0);
                                double tierDmgMult = plugin.getConfig().getDouble("mobs." + fMobId.toLowerCase() + ".tier_dmg_mult", 1.0);
                                double maxHp = 15.0 + (fLvl * tierHpMult);
                                double actualMax = Math.min(1024.0, maxHp);
                                le.getPersistentDataContainer().set(Keys.tierDmgMult, org.bukkit.persistence.PersistentDataType.DOUBLE, tierDmgMult);
                                org.bukkit.attribute.Attribute hpAttr = org.bukkit.attribute.Attribute.MAX_HEALTH;
                                if (le.getAttribute(hpAttr) != null) le.getAttribute(hpAttr).setBaseValue(actualMax);
                                le.setHealth(actualMax);
                            }
                        });
                        net.elpixedge.core.combat.CombatModule combat = plugin.getModule(net.elpixedge.core.combat.CombatModule.class);
                        if (combat != null) {
                            combat.updateMobName(mob);
                            combat.applyBetterModel(mob, fMobId.toLowerCase());
                            combat.equipMob(mob, fMobId.toLowerCase());
                        }
                        
                        net.elpixedge.core.combat.CustomMobAbilityModule abilityMod = plugin.getModule(net.elpixedge.core.combat.CustomMobAbilityModule.class);
                        if (abilityMod != null) abilityMod.registerMob(mob);
                        
                        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                            item.setAmount(item.getAmount() - 1);
                        }
                    }
                } catch (Exception ignored) {}
                return;
            }
        }

        TileState state = b.getState() instanceof TileState ? (TileState) b.getState() : null;

        GuiModule gui = plugin.getModule(GuiModule.class);
        if (gui == null) return;

        // Override Enchanting Tables (keep Vanilla Crafting Table normal)
        if (b.getType() == org.bukkit.Material.ENCHANTING_TABLE) {
            event.setCancelled(true);
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
            gui.openEnchantingTable(p, "basic");
            return;
        }

        if (state == null || !state.getPersistentDataContainer().has(Keys.customBlock, PersistentDataType.STRING)) return;

        String customId = state.getPersistentDataContainer().get(Keys.customBlock, PersistentDataType.STRING);
        event.setCancelled(true);

        if (customId.equals("edge_crafter_block")) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
            gui.openCustomCrafter(p);
        } else if (customId.equals("edge_enchant_block")) {
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
            gui.openEnchantingTable(p, "basic");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        net.elpixedge.core.player.PlayerModule pMod = plugin.getModule(net.elpixedge.core.player.PlayerModule.class);
        if (pMod != null && !pMod.canUseItem(e.getPlayer(), e.getPlayer().getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You don't meet the skill requirements to use this tool!");
            return;
        }

        // Prevent breaking custom block states without dropping data
        Block b = e.getBlock();
        if (!(b.getState() instanceof TileState)) return;
        TileState state = (TileState) b.getState();
        if (state.getPersistentDataContainer().has(Keys.customBlock, PersistentDataType.STRING)) {
            // Just clear the PDC data, vanilla drop handles the block item
            state.getPersistentDataContainer().remove(Keys.customBlock);
            state.update();
        }
    }
}
