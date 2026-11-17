package dev.oda.sidedash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent explored-world map, like a world-map mod: every chunk the client loads is drawn into 256x256-block
 * tiles (1 px = 1 block, vanilla map colours) saved under .minecraft/sidedash/maps/<world>/<dimension>/.
 * Also stores per-world waypoints. All image work happens on the client thread.
 */
final class WorldMap {
    static final int TILE = 256;
    private static final int MAX_TILES_IN_MEMORY = 48, CHUNKS_PER_TICK = 6;

    private static final Map<String, NativeImage> TILES = new LinkedHashMap<>(16, 0.75f, true); // LRU
    private static final Set<String> DIRTY = new HashSet<>();
    private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static volatile Path worldDir;   // null when not in a world
    private static volatile String dimKey = "";
    private static int ticks;

    record Waypoint(String id, String name, int x, int y, int z, String dim) { }
    private static final List<Waypoint> WAYPOINTS = new ArrayList<>();

    static void init() {
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide()) enqueue(e.getChunk().getPos().toLong());
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> closeWorld());
        // respawn (and dimension change) recreates the player: keep a single "death" waypoint at the last death spot
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.Clone e) ->
                e.getNewPlayer().getLastDeathLocation().ifPresent(WorldMap::deathPoint));
    }

    private static void deathPoint(net.minecraft.core.GlobalPos g) {
        if (worldDir == null) return;
        BlockPos p = g.pos();
        Waypoint w = new Waypoint("death", "☠", p.getX(), p.getY(), p.getZ(), g.dimension().location().toString().replace(':', '_'));
        synchronized (WAYPOINTS) {
            if (WAYPOINTS.contains(w)) return;
            WAYPOINTS.removeIf(x -> x.id().equals("death"));
            WAYPOINTS.add(w);
            saveWaypoints();
        }
    }

    private static void enqueue(long chunk) {
        if (QUEUED.add(chunk)) QUEUE.add(chunk);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        if (lv == null || mc.player == null) return;
        Path dir = currentWorldDir(mc);
        String dim = lv.dimension().location().toString().replace(':', '_');
        if (!dir.equals(worldDir) || !dim.equals(dimKey)) {
            closeWorld();
            worldDir = dir;
            dimKey = dim;
            loadWaypoints();
        }
        // re-draw the area around the player now and then, so placed/broken blocks show up
        if (++ticks % 100 == 0) {
            ChunkPos c = mc.player.chunkPosition();
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) enqueue(ChunkPos.asLong(c.x + dx, c.z + dz));
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !QUEUE.isEmpty(); i++) {
            long cp = QUEUE.poll();
            QUEUED.remove(cp);
            drawChunk(lv, ChunkPos.getX(cp), ChunkPos.getZ(cp), mc.player.getBlockY());
        }
        if (ticks % 600 == 0) flush();
    }

    /** Singleplayer: save folder name. Multiplayer: server address. */
    private static Path currentWorldDir(Minecraft mc) {
        String name;
        if (mc.getSingleplayerServer() != null)
            name = "sp_" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        else {
            ServerData s = mc.getCurrentServer();
            name = "mp_" + (s == null ? "unknown" : s.ip);
        }
        return FMLPaths.GAMEDIR.get().resolve("sidedash").resolve("maps").resolve(name.replaceAll("[^\\p{L}\\p{N}._-]", "_"));
    }

    private static void drawChunk(ClientLevel lv, int cx, int cz, int playerY) {
        if (!lv.hasChunk(cx, cz)) return;
        int x0 = cx << 4, z0 = cz << 4;
        int tx = Math.floorDiv(x0, TILE), tz = Math.floorDiv(z0, TILE);
        NativeImage img = tile(dimKey, tx, tz, true);
        boolean ceiling = lv.dimensionType().hasCeiling();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) {
            int x = x0 + dx;
            int north = lv.hasChunk(cx, (z0 - 1) >> 4) ? surfaceY(lv, pos, x, z0 - 1, ceiling, playerY) : Integer.MIN_VALUE;
            for (int dz = 0; dz < 16; dz++) {
                int z = z0 + dz;
                int y = surfaceY(lv, pos, x, z, ceiling, playerY);
                img.setPixelRGBA(x - tx * TILE, z - tz * TILE, color(lv, pos, x, y, z, north));
                north = y;
            }
        }
        DIRTY.add(key(dimKey, tx, tz));
    }

    private static int surfaceY(ClientLevel lv, BlockPos.MutableBlockPos pos, int x, int z, boolean ceiling, int playerY) {
        if (!ceiling) return lv.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        // nether-like: first solid block with air above, scanning down from just above the player
        // ponytail: 64-block scan cap; cave/nether tiles depend on the height you explored at
        int top = playerY + 2;
        for (int y = top; y > top - 64 && y > lv.getMinBuildHeight(); y--)
            if (!lv.getBlockState(pos.set(x, y, z)).isAir() && lv.getBlockState(pos.set(x, y + 1, z)).isAir()) return y;
        return top - 64;
    }

    /** Vanilla map colour (ABGR for NativeImage); slopes shaded against the block to the north, water by depth. */
    private static int color(ClientLevel lv, BlockPos.MutableBlockPos pos, int x, int y, int z, int northY) {
        BlockState st = lv.getBlockState(pos.set(x, y, z));
        int depth = 0;
        while (!st.getFluidState().isEmpty() && depth < 12 && y - depth > lv.getMinBuildHeight())
            st = lv.getBlockState(pos.set(x, y - ++depth, z));
        if (depth > 0) st = lv.getBlockState(pos.set(x, y, z));
        MapColor c = st.getMapColor(lv, pos);
        if (c == MapColor.NONE) return 0;
        MapColor.Brightness br;
        if (depth > 0) br = depth > 6 ? MapColor.Brightness.LOW : depth > 2 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
        else if (northY == Integer.MIN_VALUE || northY == y) br = MapColor.Brightness.NORMAL;
        else br = y > northY ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
        return c.calculateRGBColor(br);
    }

    private static String key(String dim, int tx, int tz) { return dim + "/" + tx + "_" + tz; }

    private static Path file(String key) { return worldDir.resolve(key + ".png"); }

    /** Client thread only. */
    private static NativeImage tile(String dim, int tx, int tz, boolean create) {
        String k = key(dim, tx, tz);
        NativeImage img = TILES.get(k);
        if (img != null) return img;
        Path f = file(k);
        if (Files.exists(f)) {
            try (InputStream in = Files.newInputStream(f)) { img = NativeImage.read(in); }
            catch (Exception e) { img = null; }
        }
        if (img == null || img.getWidth() != TILE || img.getHeight() != TILE) {
            if (img != null) img.close();
            if (!create) return null;
            img = new NativeImage(TILE, TILE, true);
        }
        TILES.put(k, img);
        evict();
        return img;
    }

    private static void evict() {
        Iterator<Map.Entry<String, NativeImage>> it = TILES.entrySet().iterator();
        while (TILES.size() > MAX_TILES_IN_MEMORY && it.hasNext()) {
            Map.Entry<String, NativeImage> e = it.next();
            save(e.getKey(), e.getValue());
            e.getValue().close();
            it.remove();
        }
    }

    private static void save(String key, NativeImage img) {
        if (!DIRTY.remove(key) || worldDir == null) return;
        try {
            Path f = file(key);
            Files.createDirectories(f.getParent());
            img.writeToFile(f);
        } catch (Exception e) {
            SideDash.LOG.warn("SideDash: can't save map tile {}", key, e);
        }
    }

    private static void flush() {
        for (Map.Entry<String, NativeImage> e : TILES.entrySet()) save(e.getKey(), e.getValue());
    }

    private static void closeWorld() {
        flush();
        TILES.values().forEach(NativeImage::close);
        TILES.clear();
        DIRTY.clear();
        QUEUE.clear();
        QUEUED.clear();
        worldDir = null;
        synchronized (WAYPOINTS) { WAYPOINTS.clear(); }
    }

    // ---------- HTTP side ----------

    static String dimKey() { return dimKey; }

    /** PNG of one tile of the current world, or null if never explored. */
    static byte[] tilePng(String dim, int tx, int tz) {
        if (worldDir == null) return null;
        return GameData.onMain(() -> {
            if (worldDir == null) return null;
            NativeImage img = dim.equals(dimKey) ? tile(dim, tx, tz, false) : null;
            try {
                if (img != null) return img.asByteArray();
                Path f = file(key(dim, tx, tz));  // other dimension: straight from disk
                return Files.exists(f) ? Files.readAllBytes(f) : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // ---------- waypoints ----------

    private static Path waypointFile() { return worldDir.resolve("waypoints.json"); }

    private static void loadWaypoints() {
        synchronized (WAYPOINTS) {
            WAYPOINTS.clear();
            try (Reader r = Files.newBufferedReader(waypointFile())) {
                List<Waypoint> l = new Gson().fromJson(r, new TypeToken<List<Waypoint>>() { }.getType());
                if (l != null) WAYPOINTS.addAll(l);
            } catch (Exception ignored) { }
        }
    }

    private static void saveWaypoints() {
        if (worldDir == null) return;
        try {
            Files.createDirectories(worldDir);
            try (Writer w = Files.newBufferedWriter(waypointFile())) { new Gson().toJson(WAYPOINTS, w); }
        } catch (Exception e) {
            SideDash.LOG.warn("SideDash: can't save waypoints", e);
        }
    }

    static String waypointsJson() {
        synchronized (WAYPOINTS) {
            JsonArray a = new JsonArray();
            for (Waypoint w : WAYPOINTS) {
                JsonObject o = new JsonObject();
                o.addProperty("id", w.id());
                o.addProperty("name", w.name());
                o.addProperty("x", w.x());
                o.addProperty("y", w.y());
                o.addProperty("z", w.z());
                o.addProperty("dim", w.dim());
                a.add(o);
            }
            return a.toString();
        }
    }

    static void addWaypoint(String name, int x, int z) {
        if (worldDir == null) return;
        Integer y = GameData.onMain(() -> {
            ClientLevel lv = Minecraft.getInstance().level;
            if (lv == null || Minecraft.getInstance().player == null) return null;
            return lv.hasChunk(x >> 4, z >> 4) && !lv.dimensionType().hasCeiling()
                    ? lv.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Minecraft.getInstance().player.getBlockY();
        });
        if (y == null) return;
        String clean = name.strip();
        if (clean.isEmpty()) clean = x + " " + z;
        if (clean.length() > 40) clean = clean.substring(0, 40);
        synchronized (WAYPOINTS) {
            WAYPOINTS.add(new Waypoint(Long.toString(System.nanoTime(), 36), clean, x, y, z, dimKey));
            saveWaypoints();
        }
    }

    static void deleteWaypoint(String id) {
        synchronized (WAYPOINTS) {
            if (WAYPOINTS.removeIf(w -> w.id().equals(id))) saveWaypoints();
        }
    }

    /** Teleport via a normal chat command, so the server's permission rules apply (cheats/op needed).
     *  Unknown height (unexplored spot) -> spreadplayers, which lands you on the surface. */
    static void teleport(int x, Integer y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.player == null || mc.level == null) return;
            String cmd;
            if (y != null) cmd = "tp @s " + x + " " + y + " " + z;
            else if (mc.level.dimensionType().hasCeiling()) cmd = "spreadplayers " + x + " " + z + " 0 1 under 120 false @s";
            else cmd = "spreadplayers " + x + " " + z + " 0 1 false @s";
            mc.player.connection.sendCommand(cmd);
        });
    }

    static Integer waypointY(String id) {
        synchronized (WAYPOINTS) {
            for (Waypoint w : WAYPOINTS) if (w.id().equals(id)) return w.y();
        }
        return null;
    }
}
