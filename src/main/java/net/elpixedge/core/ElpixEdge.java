package net.elpixedge.core;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import net.elpixedge.core.combat.*;
import net.elpixedge.core.command.EdgeCommand;
import net.elpixedge.core.gui.GuiModule;
import net.elpixedge.core.instance.CinematicController;
import net.elpixedge.core.instance.NpcVisibilityManager;
import net.elpixedge.core.instance.SchematicInstanceManager;
import net.elpixedge.core.item.ItemModule;
import net.elpixedge.core.player.PlayerModule;
import net.elpixedge.core.progression.ProgressionModule;
import net.elpixedge.core.quest.QuestEngine;
import net.elpixedge.core.spawner.SpawnerModule;
import net.elpixedge.core.tag.QuestTriggerListener;
import net.elpixedge.core.tag.TagManager;
import net.elpixedge.core.loot.LootModule;
import net.elpixedge.core.dungeon.DungeonModule;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.elpixedge.core.utils.Keys;
import lombok.Getter;

public final class ElpixEdge extends JavaPlugin {

    private static ElpixEdge instance;
    private final List<Module> modules = new ArrayList<>();
    private final Map<Class<? extends Module>, Module> moduleMap = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        // Save default config.yml if not present
        saveDefaultConfig();

        // Initialize Keys
        Keys.init(this);

        // Register Modules
        registerModule(new PlayerModule(this));
        registerModule(new ItemModule(this));
        registerModule(new ProgressionModule(this));
        registerModule(new CombatModule(this));
        registerModule(new WeaponSkillManager(this));
        registerModule(new OffhandModule(this));
        registerModule(new MagicModule(this));
        registerModule(new BlockInteractionListener(this));
        registerModule(new GuiModule(this));
        registerModule(new SpawnerModule(this));
        registerModule(new CustomMobAbilityModule(this));
        registerModule(new TagManager(this));
        registerModule(new QuestEngine(this));
        registerModule(new QuestTriggerListener(this));
        registerModule(new CinematicController(this));
        registerModule(new SchematicInstanceManager(this));
        registerModule(new NpcVisibilityManager(this));
        registerModule(new LootModule(this));
        registerModule(new DungeonModule(this));

        for (Module module : modules) {
            module.onEnable();
        }

        // Register Commands
        EdgeCommand cmd = new EdgeCommand(this);
        getCommand("status").setExecutor(cmd);
        getCommand("custom_holo").setExecutor(cmd);
        getCommand("admin_book").setExecutor(cmd);
        getCommand("edgeitem").setExecutor(cmd);
        getCommand("edgemob").setExecutor(cmd);
        getCommand("claimstash").setExecutor(cmd);
        getCommand("edgetag").setExecutor(cmd);
        getCommand("edgeinstance").setExecutor(cmd);
        getCommand("edgescene").setExecutor(cmd);

        getLogger().info("ElpixEdge has been enabled successfully.");
    }

    @Override
    public void onDisable() {
        for (Module module : modules) {
            module.onDisable();
        }
        getLogger().info("ElpixEdge has been disabled.");
    }

    public static ElpixEdge getInstance() {
        return instance;
    }

    public void registerModule(Module module) {
        modules.add(module);
        moduleMap.put(module.getClass(), module);
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T getModule(Class<T> clazz) {
        return (T) moduleMap.get(clazz);
    }
}
