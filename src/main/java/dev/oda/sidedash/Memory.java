package dev.oda.sidedash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Things the adapter remembers while you play:
 *  - chat log (last 200 lines) for the tablet's chat tab;
 *  - contents of every container you open (chest, barrel, vault...), saved per world, for "where is it?";
 *  - pinned blocks whose goggle info the tablet shows live.
 * The client never learns which block a menu belongs to, so we use the block under the crosshair when it opened.
 */
final class Memory {
    // ---------- chat ----------
    record Line(long seq, String text, boolean system) { }
    private static final ArrayDeque<Line> CHAT = new ArrayDeque<>();
    private static long chatSeq;

    // ---------- containers ----------
    record Box(int x, int y, int z, String dim, String title, Map<String, Integer> items) { }
    private static final Map<String, Box> BOXES = new LinkedHashMap<>();
    private static AbstractContainerMenu openMenu;
    private static BlockPos openPos;
    private static String openDim;
    private static int ticks;

    // ---------- pins ----------
    record Pin(int x, int y, int z, String dim) { }
    private static final List<Pin> PINS = new ArrayList<>();

    private static Path loadedFrom;

    static void init() {
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent e) -> {
            synchronized (CHAT) {
                CHAT.add(new Line(++chatSeq, e.getMessage().getString(), e.isSystem()));
                while (CHAT.size() > 200) CHAT.poll();
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> tick());
    }

    static long chatSeq() { synchronized (CHAT) { return chatSeq; } }

    static String chat(long after) {
        JsonArray a = new JsonArray();
        synchronized (CHAT) {
            for (Line l : CHAT) {
                if (l.seq() <= after) continue;
                JsonObject o = new JsonObject();
                o.addProperty("seq", l.seq());
                o.addProperty("text", l.text());
                o.addProperty("sys", l.system());
                a.add(o);
            }
        }
        return a.toString();
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        Path dir = WorldMap.worldDir();
        if (p == null || dir == null) { openMenu = null; return; }
        if (!dir.equals(loadedFrom)) load(dir);

        AbstractContainerMenu menu = p.containerMenu;
        if (menu != openMenu) {
            if (openMenu != null && openPos != null) remember(mc);  // closed or switched: save final contents
            openMenu = menu == p.inventoryMenu ? null : menu;
            openPos = null;
            HitResult h = mc.hitResult;
            if (openMenu != null && h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockEntity(b.getBlockPos()) != null) {  // only real storage blocks
                openPos = b.getBlockPos();
                openDim = WorldMap.dimKey();
            }
        }
        if (openMenu != null && openPos != null && ++ticks % 20 == 0) remember(mc);
    }

    private static void remember(Minecraft mc) {
        Map<String, Integer> items = new HashMap<>();
        for (Slot s : openMenu.slots) {
            if (s.container == mc.player.getInventory() || s.getItem().isEmpty()) continue;
            items.merge(BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString(), s.getItem().getCount(), Integer::sum);
        }
        String title = mc.screen != null ? mc.screen.getTitle().getString() : "";
        String key = openDim + "/" + openPos.getX() + "/" + openPos.getY() + "/" + openPos.getZ();
        Box box = new Box(openPos.getX(), openPos.getY(), openPos.getZ(), openDim, title, items);
        synchronized (BOXES) {
            if (box.equals(BOXES.get(key))) return;
            if (items.isEmpty()) BOXES.remove(key); else BOXES.put(key, box);
        }
        save();
    }

    /** Containers that held this item last time you looked, most first. */
    static String where(String itemId) {
        JsonArray a = new JsonArray();
        List<Box> hits = new ArrayList<>();
        synchronized (BOXES) {
            for (Box b : BOXES.values()) if (b.items().containsKey(itemId)) hits.add(b);
        }
        hits.sort((x, y) -> y.items().get(itemId) - x.items().get(itemId));
        for (Box b : hits) {
            JsonObject o = new JsonObject();
            o.addProperty("x", b.x()); o.addProperty("y", b.y()); o.addProperty("z", b.z());
            o.addProperty("dim", b.dim());
            o.addProperty("title", b.title());
            o.addProperty("n", b.items().get(itemId));
            a.add(o);
        }
        return a.toString();
    }

    // ---------- pins ----------

    static void pin(int x, int y, int z, boolean on) {
        Pin pin = new Pin(x, y, z, WorldMap.dimKey());
        synchronized (PINS) {
            PINS.remove(pin);
            if (on) PINS.add(pin);
        }
        save();
    }

    /** Live goggle info for pinned blocks (client thread). */
    static JsonArray pinsJson(Minecraft mc) {
        JsonArray a = new JsonArray();
        List<Pin> copy;
        synchronized (PINS) { copy = new ArrayList<>(PINS); }
        for (Pin p : copy) {
            if (!p.dim().equals(WorldMap.dimKey())) continue;
            JsonObject o = new JsonObject();
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            o.addProperty("pos", p.x() + " " + p.y() + " " + p.z());
            var st = mc.level.getBlockState(pos);
            // the client may hold an empty placeholder for far chunks: air there means "not loaded", not a real block
            if (mc.level.hasChunk(p.x() >> 4, p.z() >> 4) && !st.isAir()) {
                o.addProperty("name", st.getBlock().getName().getString());
                o.addProperty("icon", BuiltInRegistries.ITEM.getKey(st.getBlock().asItem()).toString());
                JsonArray lines = GameData.goggleLines(mc.level.getBlockEntity(pos), false);
                if (lines != null) o.add("lines", lines);
            } else o.addProperty("far", true);
            a.add(o);
        }
        return a;
    }

    // ---------- storage ----------

    private record Saved(List<Box> boxes, List<Pin> pins) { }

    private static void load(Path dir) {
        loadedFrom = dir;
        synchronized (BOXES) { BOXES.clear(); }
        synchronized (PINS) { PINS.clear(); }
        try (Reader r = Files.newBufferedReader(dir.resolve("memory.json"))) {
            Saved s = new Gson().fromJson(r, new TypeToken<Saved>() { }.getType());
            if (s == null) return;
            synchronized (BOXES) {
                if (s.boxes() != null) for (Box b : s.boxes()) BOXES.put(b.dim() + "/" + b.x() + "/" + b.y() + "/" + b.z(), b);
            }
            synchronized (PINS) { if (s.pins() != null) PINS.addAll(s.pins()); }
        } catch (Exception ignored) { }
    }

    private static void save() {
        Path dir = loadedFrom;
        if (dir == null) return;
        Saved s;
        synchronized (BOXES) { synchronized (PINS) { s = new Saved(new ArrayList<>(BOXES.values()), new ArrayList<>(PINS)); } }
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve("memory.json"))) { new Gson().toJson(s, w); }
        } catch (Exception e) {
            SideDash.LOG.warn("SideDash: can't save memory", e);
        }
    }
}
