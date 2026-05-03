package net.elpixedge.core.dungeon.provider;

import org.bukkit.World;
import java.util.concurrent.CompletableFuture;

public interface DungeonProvider {
    /**
     * Creates or loads a dungeon world instance.
     * @param instanceId Unique ID for this dungeon instance.
     * @param templateName The name of the template/slime-world/schematic to use.
     * @return CompletableFuture that completes with the loaded World.
     */
    CompletableFuture<World> createInstance(String instanceId, String templateName);

    /**
     * Unloads and cleans up a dungeon instance.
     * @param world The world to clean up.
     */
    void deleteInstance(World world);
}
