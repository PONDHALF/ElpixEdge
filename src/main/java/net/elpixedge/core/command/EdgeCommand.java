package net.elpixedge.core.command;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.gui.GuiModule;
import net.elpixedge.core.tag.TagManager;
import net.elpixedge.core.item.ItemModule;
import net.elpixedge.core.instance.SchematicInstanceManager;
import net.elpixedge.core.instance.CinematicController;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.player.PlayerProfile;
import net.elpixedge.core.utils.Keys;
import net.elpixedge.core.loot.LootModule;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class EdgeCommand implements CommandExecutor, TabCompleter {

    private final ElpixEdge plugin;

    public EdgeCommand(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player p = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("claimstash")) {
            plugin.getModule(PlayerModule.class).getStashManager().claimStash(p);
            return true;
        }

        if (cmd.equals("custom_holo")) {
            PlayerProfile profile = plugin.getModule(PlayerModule.class).getProfile(p);
            if (profile != null) {
                if (profile.isHideDamageHolo()) {
                    profile.setHideDamageHolo(false);
                    p.sendMessage(ChatColor.GREEN + "Damage holograms enabled.");
                } else {
                    profile.setHideDamageHolo(true);
                    p.sendMessage(ChatColor.RED + "Damage holograms disabled.");
                }
            }
            return true;
        }

        if (cmd.equals("status")) {
            plugin.getModule(GuiModule.class).openStatusMenu(p);
            return true;
        }

        if (cmd.equals("edgeloot")) {
            if (!p.isOp()) return false;
            LootModule lootMod = plugin.getModule(LootModule.class);
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    lootMod.loadConfig();
                    p.sendMessage(ChatColor.GREEN + "Loot Chest config reloaded.");
                    return true;
                } else if (args[0].equalsIgnoreCase("give") && args.length > 1) {
                    lootMod.giveChestItem(p, args[1]);
                    return true;
                }
            }
            p.sendMessage(ChatColor.YELLOW + "Usage: /edgeloot <reload|give> [templateId]");
            return true;
        }

        if (cmd.equals("edgedungeon")) {
            if (!p.isOp()) return false;
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /edgedungeon <enter|leave|reload> [dungeonId]");
                return true;
            }
            net.elpixedge.core.dungeon.DungeonModule dMgr = plugin.getModule(net.elpixedge.core.dungeon.DungeonModule.class);
            if (args[0].equalsIgnoreCase("enter") && args.length > 1) {
                dMgr.enterDungeon(p, args[1].toLowerCase());
                p.sendMessage(ChatColor.GREEN + "Entering dungeon: " + args[1]);
                return true;
            } else if (args[0].equalsIgnoreCase("leave")) {
                dMgr.exitDungeon(p);
                return true;
            } else if (args[0].equalsIgnoreCase("reload")) {
                dMgr.loadConfig();
                p.sendMessage(ChatColor.GREEN + "Dungeon config reloaded.");
                return true;
            }
            return true;
        }

        if (cmd.equals("admin_book")) {
            if (!p.isOp()) {
                p.sendMessage(org.bukkit.ChatColor.RED + "You do not have permission.");
                return true;
            }
            // Give admin book item
            ItemStack adminBook = plugin.getModule(net.elpixedge.core.item.ItemModule.class).generateCustomItem("super_admin_book");
            if (adminBook != null) p.getInventory().addItem(adminBook);
            plugin.getModule(net.elpixedge.core.gui.GuiModule.class).openSuperAdminMenu(p);
            return true;
        }

        if (cmd.equals("edgemob") && args.length > 0) {
            String mobId = args[0].toLowerCase();
            String baseEntStr = plugin.getConfig().getString("mobs." + mobId + ".base_entity", mobId);
            EntityType eType;
            try {
                eType = EntityType.valueOf(baseEntStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                eType = EntityType.ENDERMAN;
            }
            LivingEntity mob = (LivingEntity) p.getWorld().spawnEntity(p.getLocation(), eType);
            mob.getPersistentDataContainer().set(Keys.customMobId, PersistentDataType.STRING, args[0].toLowerCase());
            mob.getPersistentDataContainer().set(Keys.lvl, PersistentDataType.INTEGER, 10);
            
            PlayerModule pdm = plugin.getModule(PlayerModule.class);
            Attribute maxHpAttr = pdm != null ? pdm.getHealthAttribute() : Attribute.MAX_HEALTH;
            if (mob.getAttribute(maxHpAttr) != null) mob.getAttribute(maxHpAttr).setBaseValue(500.0);
            mob.setHealth(500.0);
            
            net.elpixedge.core.combat.CombatModule combat = plugin.getModule(net.elpixedge.core.combat.CombatModule.class);
            if (combat != null) {
                combat.updateMobName(mob);
                combat.applyBetterModel(mob, mobId);
                combat.equipMob(mob, mobId);
            }

            net.elpixedge.core.combat.CustomMobAbilityModule abilityMod = plugin.getModule(net.elpixedge.core.combat.CustomMobAbilityModule.class);
            if (abilityMod != null) abilityMod.registerMob(mob);
            
            return true;
        }

        if (cmd.equals("edgeitem") && args.length > 0) {
            ItemStack item = plugin.getModule(ItemModule.class).generateCustomItem(args[0].toLowerCase());
            if (item != null) p.getInventory().addItem(item);
            else p.sendMessage(ChatColor.RED + "Item not found in config.");
            return true;
        }

        // /edgetag <add|remove|list> <player> [tag]
        if (cmd.equals("edgetag")) {
            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "You do not have permission.");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /edgetag <add|remove|list> <player> [tag]");
                p.sendMessage(ChatColor.YELLOW + "       /edgetag reload");
                return true;
            }
            String sub = args[0].toLowerCase();
            if (sub.equals("reload")) {
                net.elpixedge.core.instance.NpcVisibilityManager npcMgr = plugin.getModule(net.elpixedge.core.instance.NpcVisibilityManager.class);
                if (npcMgr != null) {
                    npcMgr.reloadConfig();
                    p.sendMessage(ChatColor.GREEN + "NPC Visibility rules reloaded.");
                } else {
                    p.sendMessage(ChatColor.RED + "NpcVisibilityManager is not loaded.");
                }
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /edgetag <add|remove|list> <player> [tag]");
                return true;
            }
            Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                return true;
            }
            TagManager tagMgr = plugin.getModule(TagManager.class);
            if (tagMgr == null) {
                p.sendMessage(ChatColor.RED + "TagManager is not loaded.");
                return true;
            }
            switch (sub) {
                case "add":
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /edgetag add <player> <tag>");
                        return true;
                    }
                    tagMgr.addTag(target, args[2]);
                    p.sendMessage(ChatColor.GREEN + "Tag '" + args[2] + "' added to " + target.getName() + ".");
                    return true;
                case "remove":
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.YELLOW + "Usage: /edgetag remove <player> <tag>");
                        return true;
                    }
                    tagMgr.removeTag(target, args[2]);
                    p.sendMessage(ChatColor.GREEN + "Tag '" + args[2] + "' removed from " + target.getName() + ".");
                    return true;
                case "list":
                    java.util.Set<String> tags = tagMgr.getTags(target);
                    if (tags.isEmpty()) {
                        p.sendMessage(ChatColor.GRAY + target.getName() + " has no tags.");
                    } else {
                        p.sendMessage(ChatColor.GOLD + target.getName() + "'s tags (" + tags.size() + "):");
                        for (String tag : tags) {
                            p.sendMessage(ChatColor.GRAY + " - " + tag);
                        }
                    }
                    return true;
                default:
                    p.sendMessage(ChatColor.YELLOW + "Usage: /edgetag <add|remove|list> <player> [tag]");
                    p.sendMessage(ChatColor.YELLOW + "       /edgetag reload");
                    return true;
            }
        }
        
        // /edgeinstance <enter|leave> [instanceId]
        if (cmd.equals("edgeinstance")) {
            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "You do not have permission.");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /edgeinstance <enter|leave> [instanceId]");
                return true;
            }
            SchematicInstanceManager instMgr = plugin.getModule(SchematicInstanceManager.class);
            if (instMgr == null) {
                p.sendMessage(ChatColor.RED + "SchematicInstanceManager is not loaded.");
                return true;
            }
            if (args[0].equalsIgnoreCase("enter")) {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /edgeinstance enter <schematicName>");
                    return true;
                }
                instMgr.pasteTemplate(args[1].toLowerCase(), p);
                return true;
            } else if (args[0].equalsIgnoreCase("leave")) {
                instMgr.cleanupInstance(p.getUniqueId());
                return true;
            }
        }

        // /edgescene <play|stop> [cutsceneId]
        if (cmd.equals("edgescene")) {
            if (!p.isOp()) {
                p.sendMessage(ChatColor.RED + "You do not have permission.");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Usage: /edgescene <play|stop> [cutsceneId]");
                return true;
            }
            CinematicController cineCtrl = plugin.getModule(CinematicController.class);
            if (cineCtrl == null) {
                p.sendMessage(ChatColor.RED + "CinematicController is not loaded.");
                return true;
            }
            if (args[0].equalsIgnoreCase("play")) {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.YELLOW + "Usage: /edgescene play <cutsceneId>");
                    return true;
                }
                cineCtrl.playCinematic(p, args[1].toLowerCase());
                return true;
            } else if (args[0].equalsIgnoreCase("stop")) {
                cineCtrl.endCinematic(p, false);
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();

        if (cmd.equals("edgeloot")) {
            if (args.length == 1) {
                String[] subs = {"reload", "give"};
                for (String s : subs) if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                // Suggest Loot Templates from YAML
                File f = new File(plugin.getDataFolder(), "loot_chests.yml");
                if (f.exists()) {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                    ConfigurationSection sec = cfg.getConfigurationSection("templates");
                    if (sec != null) {
                        for (String key : sec.getKeys(false)) {
                            if (key.toLowerCase().startsWith(args[1].toLowerCase())) completions.add(key);
                        }
                    }
                }
            }
            return completions;
        }

        if (cmd.equals("edgedungeon")) {
            if (args.length == 1) {
                String[] subs = {"enter", "leave", "reload"};
                for (String s : subs) if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
                // Suggest Dungeons from YAML
                File f = new File(plugin.getDataFolder(), "dungeons.yml");
                if (f.exists()) {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                    ConfigurationSection sec = cfg.getConfigurationSection("dungeons");
                    if (sec != null) {
                        for (String key : sec.getKeys(false)) {
                            if (key.toLowerCase().startsWith(args[1].toLowerCase())) completions.add(key);
                        }
                    }
                }
            }
            return completions;
        }

        if (cmd.equals("edgeitem")) {
            if (args.length == 1) {
                ConfigurationSection sec = plugin.getConfig().getConfigurationSection("items");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        if (key.toLowerCase().startsWith(args[0].toLowerCase())) completions.add(key);
                    }
                }
            }
            return completions;
        }

        if (cmd.equals("edgemob")) {
            if (args.length == 1) {
                ConfigurationSection sec = plugin.getConfig().getConfigurationSection("mobs");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        if (key.toLowerCase().startsWith(args[0].toLowerCase())) completions.add(key);
                    }
                }
            }
            return completions;
        }

        if (cmd.equals("edgetag")) {
            if (args.length == 1) {
                String[] subs = {"add", "remove", "list", "reload"};
                for (String s : subs) if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            } else if (args.length == 2) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) completions.add(p.getName());
                }
            }
            return completions;
        }

        if (cmd.equals("edgescene")) {
            if (args.length == 1) {
                String[] subs = {"play", "stop"};
                for (String s : subs) if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("play")) {
                ConfigurationSection sec = plugin.getConfig().getConfigurationSection("cutscenes");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        if (key.toLowerCase().startsWith(args[1].toLowerCase())) completions.add(key);
                    }
                }
            }
            return completions;
        }

        if (cmd.equals("edgeinstance")) {
            if (args.length == 1) {
                String[] subs = {"enter", "leave"};
                for (String s : subs) if (s.startsWith(args[0].toLowerCase())) completions.add(s);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
                File dir = new File(plugin.getDataFolder(), "schematics");
                if (dir.exists() && dir.isDirectory()) {
                    for (File f : dir.listFiles()) {
                        String n = f.getName().replace(".schem", "").replace(".schm", "");
                        if (n.toLowerCase().startsWith(args[1].toLowerCase())) completions.add(n);
                    }
                }
            }
            return completions;
        }

        return null;
    }
}
