package net.elpixedge.core.dungeon.provider;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * A high-performance provider that creates a Void World and pastes a schematic into it.
 */
public class SlimeDungeonProvider implements DungeonProvider {

    @Override
    public CompletableFuture<World> createInstance(String instanceId, String schematicName) {
        CompletableFuture<World> future = new CompletableFuture<>();
        
        // Create Void World
        WorldCreator creator = new WorldCreator("dungeon_" + instanceId);
        creator.generator(new VoidGenerator());
        creator.generateStructures(false);
        World world = creator.createWorld();

        if (world == null) {
            future.completeExceptionally(new RuntimeException("Failed to create dungeon world"));
            return future;
        }

        // Paste Schematic Asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("ElpixEdge"), () -> {
            try {
                File schemFile = findSchematicFile(schematicName);
                if (schemFile == null || !schemFile.exists()) {
                    future.completeExceptionally(new RuntimeException("Schematic not found: " + schematicName));
                    return;
                }

                ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
                if (format == null) {
                    future.completeExceptionally(new RuntimeException("Invalid schematic format"));
                    return;
                }

                try (var reader = format.getReader(new FileInputStream(schemFile))) {
                    Clipboard clipboard = reader.read();
                    try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                            .world(BukkitAdapter.adapt(world))
                            .fastMode(true)
                            .build()) {

                        Operation operation = new ClipboardHolder(clipboard)
                                .createPaste(editSession)
                                .to(BlockVector3.at(0, 64, 0)) // Paste at center of void world
                                .ignoreAirBlocks(false)
                                .build();

                        Operations.complete(operation);
                    }
                }

                // Complete on main thread
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("ElpixEdge"), () -> future.complete(world));

            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private File findSchematicFile(String name) {
        File dataFolder = Bukkit.getPluginManager().getPlugin("ElpixEdge").getDataFolder();
        File schemDir = new File(dataFolder, "schematics");
        File file = new File(schemDir, name + ".schem");
        if (file.exists()) return file;
        file = new File(schemDir, name + ".schematic");
        if (file.exists()) return file;
        
        // Fallback to FAWE folder
        File faweDir = new File(Bukkit.getServer().getWorldContainer(), "plugins/FastAsyncWorldEdit/schematics");
        file = new File(faweDir, name + ".schem");
        if (file.exists()) return file;
        file = new File(faweDir, name + ".schematic");
        return file;
    }

    @Override
    public void deleteInstance(World world) {
        if (world == null) return;
        String worldName = world.getName();
        Bukkit.unloadWorld(world, false);
        
        // Optional: Delete world folder
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("ElpixEdge"), () -> {
            deleteDirectory(new File(Bukkit.getServer().getWorldContainer(), worldName));
        });
    }

    private void deleteDirectory(File path) {
        if (path.exists()) {
            File[] files = path.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) deleteDirectory(file);
                    else file.delete();
                }
            }
            path.delete();
        }
    }

    private static class VoidGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
