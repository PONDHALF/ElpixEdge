package net.elpixedge.core.instance;

import net.elpixedge.core.ElpixEdge;
import net.elpixedge.core.Module;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CinematicController — Controls camera cutscenes using invisible ArmorStands.
 *
 * Uses ProtocolLib (via reflection) to send camera packets, with a fallback
 * to spectator-mode camera if ProtocolLib is unavailable.
 *
 * During cutscene:
 * - Player movement is frozen
 * - Player is invulnerable
 * - Camera follows a sequence of points defined in scenes.yml
 *
 * scenes.yml format for cutscenes:
 * cutscenes:
 * intro_scene:
 * points:
 * - "world, 100, 80, 200, 0, 0"
 * - "world, 110, 75, 210, 45, -10"
 * duration_per_point: 60 # ticks per camera point
 * message_start: "&5[Cinematic] &7The story begins..."
 * message_end: "&5[Cinematic] &7End of cutscene."
 */
public class CinematicController implements Module, Listener {

    private final ElpixEdge plugin;

    // Active cinematic sessions: player UUID -> CinematicSession
    private final ConcurrentHashMap<UUID, CinematicSession> activeCinematics = new ConcurrentHashMap<>();

    // Cutscene definitions loaded from scenes.yml
    private final Map<String, CutsceneDef> cutsceneDefs = new LinkedHashMap<>();

    // Whether ProtocolLib is available
    private ProtocolManager protocolManager;
    private boolean protocolLibAvailable = false;

    public CinematicController(ElpixEdge plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onEnable() {
        protocolLibAvailable = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null
                && Bukkit.getPluginManager().getPlugin("ProtocolLib").isEnabled();
        if (protocolLibAvailable) {
            try {
                protocolManager = ProtocolLibrary.getProtocolManager();
                if (protocolManager == null) {
                    protocolLibAvailable = false;
                    plugin.getLogger().warning("CinematicController — ProtocolManager is null; disabling ProtocolLib support.");
                }
            } catch (Exception e) {
                protocolLibAvailable = false;
                plugin.getLogger().warning("CinematicController — Failed to obtain ProtocolManager: " + e.getMessage());
            }
        }

        loadCutsceneDefs();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("CinematicController enabled — " + cutsceneDefs.size()
                + " cutscene(s) loaded. ProtocolLib: " + (protocolLibAvailable ? "YES" : "NO (fallback mode)"));
    }

    @Override
    public void onDisable() {
        // Force-end all active cinematics
        for (UUID uuid : new HashSet<>(activeCinematics.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                endCinematic(p, true);
            }
        }
        activeCinematics.clear();
    }

    // ==========================================
    // Config Loading
    // ==========================================

    private void loadCutsceneDefs() {
        cutsceneDefs.clear();
        org.bukkit.configuration.file.YamlConfiguration cfg = loadScenesConfig();
        if (cfg == null)
            return;

        ConfigurationSection sec = cfg.getConfigurationSection("cutscenes");
        if (sec == null)
            return;

        for (String cutsceneId : sec.getKeys(false)) {
            ConfigurationSection cSec = sec.getConfigurationSection(cutsceneId);
            if (cSec == null)
                continue;

            CutsceneDef def = new CutsceneDef();
            def.id = cutsceneId;
            def.durationPerPoint = cSec.getInt("duration_per_point", 60);
            def.messageStart = cSec.getString("message_start", "");
            def.messageEnd = cSec.getString("message_end", "");

            List<String> pointStrs = cSec.getStringList("points");
            for (String pStr : pointStrs) {
                Location loc = parseLocation(pStr);
                if (loc != null)
                    def.points.add(loc);
            }

            // Store raw strings for re-parsing if worlds aren't loaded yet
            def.rawPoints = pointStrs;

            if (!def.points.isEmpty()) {
                cutsceneDefs.put(cutsceneId, def);
            }
        }
    }

    public void reloadConfig() {
        loadCutsceneDefs();
        plugin.getLogger().info("CinematicController — Reloaded " + cutsceneDefs.size() + " cutscene(s).");
    }

    // ==========================================
    // Public API
    // ==========================================

    /**
     * Plays a cutscene for a player using predefined camera points.
     *
     * @param player     the player to show the cutscene to
     * @param cutsceneId the cutscene ID from scenes.yml
     */
    public void playCinematic(Player player, String cutsceneId) {
        if (player == null || cutsceneId == null)
            return;

        CutsceneDef def = cutsceneDefs.get(cutsceneId);
        if (def == null) {
            plugin.getLogger().warning("CinematicController — Unknown cutscene: " + cutsceneId);
            return;
        }

        // Re-parse points if needed (world may not have been loaded at startup)
        if (def.points.isEmpty() && !def.rawPoints.isEmpty()) {
            for (String pStr : def.rawPoints) {
                Location loc = parseLocation(pStr);
                if (loc != null)
                    def.points.add(loc);
            }
        }
        if (def.points.isEmpty())
            return;

        playCinematic(player, def.points, def.durationPerPoint, def.messageStart, def.messageEnd);
    }

    /**
     * Plays a custom cutscene with arbitrary camera points.
     *
     * @param player        the target player
     * @param points        list of camera locations
     * @param ticksPerPoint ticks to hold each camera point
     * @param messageStart  message shown at start (supports & codes)
     * @param messageEnd    message shown at end (supports & codes)
     */
    public void playCinematic(Player player, List<Location> points, int ticksPerPoint,
                              String messageStart, String messageEnd) {
        if (player == null || points == null || points.isEmpty())
            return;

        // Already in a cinematic?
        if (activeCinematics.containsKey(player.getUniqueId()))
            return;

        CinematicSession session = new CinematicSession();
        session.originalLocation = player.getLocation().clone();
        session.originalGameMode = player.getGameMode();
        session.wasFlying = player.isFlying();
        session.wasInvulnerable = player.isInvulnerable();
        activeCinematics.put(player.getUniqueId(), session);

        // Make player invulnerable during cutscene
        player.setInvulnerable(true);

        // Send start message
        if (messageStart != null && !messageStart.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', messageStart));
        }

        // Start camera sequence
        startCameraSequence(player, session, points, ticksPerPoint, messageEnd);
    }

    /**
     * Checks if a player is currently watching a cutscene.
     */
    public boolean isInCinematic(Player player) {
        return player != null && activeCinematics.containsKey(player.getUniqueId());
    }

    /**
     * Returns the total duration in ticks for a cutscene.
     * Returns 0 if the cutscene is not found.
     */
    public long getCutsceneDuration(String cutsceneId) {
        CutsceneDef def = cutsceneDefs.get(cutsceneId);
        if (def == null)
            return 0L;
        return (long) def.durationPerPoint * Math.max(1, def.points.size());
    }

    /**
     * Force-ends a cutscene for a player.
     */
    public void endCinematic(Player player, boolean skipMessage) {
        if (player == null)
            return;
        CinematicSession session = activeCinematics.remove(player.getUniqueId());
        if (session == null)
            return;

        // Cancel the running task
        if (session.cameraTask != null && !session.cameraTask.isCancelled()) {
            session.cameraTask.cancel();
        }

        // Remove camera entity
        removeCameraEntity(session);

        // Restore player state
        restoreCamera(player);
        player.setInvulnerable(session.wasInvulnerable);

        // Teleport back to original position
        if (session.originalLocation != null && session.originalLocation.getWorld() != null) {
            player.teleport(session.originalLocation);
        }
    }

    // ==========================================
    // Camera Sequence Engine
    // ==========================================

    private void startCameraSequence(Player player, CinematicSession session,
                                     List<Location> points, int ticksPerPoint, String messageEnd) {

        // Spawn invisible ArmorStand as camera entity at first point
        Location firstPoint = points.get(0);
        ArmorStand cameraEntity = firstPoint.getWorld().spawn(firstPoint, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setGravity(false);
            stand.setSmall(true);
            stand.setCustomNameVisible(false);
            stand.setSilent(true);
        });
        session.cameraEntityId = cameraEntity.getUniqueId();

        // Delay setting camera to ensure entity is spawned on client
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && cameraEntity.isValid()) {
                    setCameraToEntity(player, cameraEntity);
                }
            }
        }.runTaskLater(plugin, 2L);

        // Start the point sequence
        session.cameraTask = new BukkitRunnable() {
            int currentPointIdx = 0;
            int ticksAtCurrentPoint = 0;

            @Override
            public void run() {
                // Safety checks
                if (!player.isOnline() || !activeCinematics.containsKey(player.getUniqueId())) {
                    endCinematic(player, true);
                    cancel();
                    return;
                }

                if (cameraEntity.isDead() || !cameraEntity.isValid()) {
                    endCinematic(player, true);
                    cancel();
                    return;
                }

                ticksAtCurrentPoint++;

                // Move to next point when duration expires
                if (ticksAtCurrentPoint >= ticksPerPoint) {
                    currentPointIdx++;
                    ticksAtCurrentPoint = 0;

                    // All points exhausted — end cinematic
                    if (currentPointIdx >= points.size()) {
                        if (messageEnd != null && !messageEnd.isEmpty()) {
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', messageEnd));
                        }
                        endCinematic(player, true);
                        cancel();
                        return;
                    }

                    // Teleport camera entity to next point
                    Location nextPoint = points.get(currentPointIdx);
                    if (nextPoint.getWorld() != null) {
                        cameraEntity.teleport(nextPoint);
                    }
                }

                // Smooth interpolation: lerp toward the next point for smooth camera movement
                if (currentPointIdx < points.size() - 1) {
                    Location current = points.get(currentPointIdx);
                    Location next = points.get(currentPointIdx + 1);
                    double progress = (double) ticksAtCurrentPoint / ticksPerPoint;

                    double lx = current.getX() + (next.getX() - current.getX()) * progress;
                    double ly = current.getY() + (next.getY() - current.getY()) * progress;
                    double lz = current.getZ() + (next.getZ() - current.getZ()) * progress;
                    float lyaw = (float) (current.getYaw() + (next.getYaw() - current.getYaw()) * progress);
                    float lpitch = (float) (current.getPitch() + (next.getPitch() - current.getPitch()) * progress);

                    Location interpolated = new Location(current.getWorld(), lx, ly, lz, lyaw, lpitch);
                    cameraEntity.teleport(interpolated);
                    
                    // Keep player physically at the camera location to ensure entity tracking range
                    // We use a small offset or same location; in Spectator mode this is smooth.
                    player.teleport(interpolated);
                }
            }
        };
        session.cameraTask.runTaskTimer(plugin, 0L, 1L);
    }

    // ==========================================
    // Camera Packet Handling
    // ==========================================

    /**
     * Sets the player's camera to view through the given entity.
     * Uses ProtocolLib reflection if available, otherwise uses spectator mode
     * fallback.
     */
    private void setCameraToEntity(Player player, org.bukkit.entity.Entity cameraEntity) {
        // Force Spectator mode for stable camera following
        player.setGameMode(GameMode.SPECTATOR);
        
        if (protocolLibAvailable && protocolManager != null) {
            try {
                setCameraViaProtocolLib(player, cameraEntity);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("CinematicController — ProtocolLib camera failed, using fallback: " + e.getMessage());
            }
        }
        // Native spectator target
        player.setSpectatorTarget(cameraEntity);
    }

    /**
     * Restores the player's camera to their own view.
     */
    private void restoreCamera(Player player) {
        if (protocolLibAvailable) {
            try {
                setCameraViaProtocolLib(player, player);
                return;
            } catch (Exception ignored) {
            }
        }
        // Fallback: restore gamemode
        CinematicSession session = activeCinematics.get(player.getUniqueId());
        GameMode original = session != null ? session.originalGameMode : GameMode.SURVIVAL;
        player.setSpectatorTarget(null);
        player.setGameMode(original);
    }

    /**
     * Sends a camera packet via ProtocolLib using reflection.
     * PacketType: PacketPlayOutCamera (CAMERA packet)
     */
    private void setCameraViaProtocolLib(Player player, org.bukkit.entity.Entity target) throws Exception {
        // Defensive: ensure the ProtocolManager was obtained during onEnable()
        if (protocolManager == null) {
            throw new IllegalStateException("ProtocolManager is unavailable – ProtocolLib may not be loaded properly");
        }
        PacketType cameraPacketType = PacketType.Play.Server.CAMERA;
        PacketContainer packetContainer = new PacketContainer(cameraPacketType);
        packetContainer.getIntegers().write(0, target.getEntityId());
        protocolManager.sendServerPacket(player, packetContainer);
    }

    private void removeCameraEntity(CinematicSession session) {
        if (session.cameraEntityId != null) {
            org.bukkit.entity.Entity ent = Bukkit.getEntity(session.cameraEntityId);
            if (ent != null && !ent.isDead())
                ent.remove();
            session.cameraEntityId = null;
        }
    }

    // ==========================================
    // Movement Freeze & Invulnerability
    // ==========================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!activeCinematics.containsKey(player.getUniqueId()))
            return;

        // If player is in spectator mode and spectating, don't cancel movement 
        // because the plugin is teleporting them to follow the camera.
        if (player.getGameMode() == GameMode.SPECTATOR && player.getSpectatorTarget() != null) {
            return;
        }

        // Freeze player position during cinematic (allow head rotation)
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player))
            return;
        if (activeCinematics.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    private org.bukkit.configuration.file.YamlConfiguration loadScenesConfig() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "scenes.yml");
        if (!f.exists())
            plugin.saveResource("scenes.yml", false);
        if (!f.exists())
            return null;
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
    }

    private Location parseLocation(String locStr) {
        if (locStr == null || locStr.isEmpty())
            return null;
        String[] p = locStr.split(",");
        if (p.length < 4)
            return null;
        try {
            World w = Bukkit.getWorld(p[0].trim());
            if (w == null)
                return null;
            return new Location(w, Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()),
                    Double.parseDouble(p[3].trim()),
                    p.length > 4 ? Float.parseFloat(p[4].trim()) : 0f,
                    p.length > 5 ? Float.parseFloat(p[5].trim()) : 0f);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==========================================
    // Data Classes
    // ==========================================

    private static class CutsceneDef {
        String id;
        List<Location> points = new ArrayList<>();
        List<String> rawPoints = new ArrayList<>();
        int durationPerPoint;
        String messageStart, messageEnd;
    }

    private static class CinematicSession {
        Location originalLocation;
        GameMode originalGameMode;
        boolean wasFlying, wasInvulnerable;
        UUID cameraEntityId;
        BukkitRunnable cameraTask;
    }
}