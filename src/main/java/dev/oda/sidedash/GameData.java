package dev.oda.sidedash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Everything that touches the game. All reads hop onto the client thread; HTTP threads never touch MC state directly. */
final class GameData {
    private static final String OFFLINE = "{\"inGame\":false}";
    private static final Map<String, byte[]> ICONS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Method>> GOGGLES = new ConcurrentHashMap<>();

    static <T> T onMain(Supplier<T> f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        try {
            return mc.submit(f).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    static String snapshot() {
        String s = onMain(GameData::snapshotMain);
        return s == null ? OFFLINE : s;
    }

    private static String snapshotMain() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lv = mc.level;
        if (p == null || lv == null) return OFFLINE;

        JsonObject o = new JsonObject();
        o.addProperty("inGame", true);
        o.addProperty("lang", mc.getLanguageManager().getSelected());
        o.addProperty("dimKey", WorldMap.dimKey());
        o.addProperty("canTp", p.hasPermissions(2));
        o.addProperty("name", p.getName().getString());
        o.addProperty("hp", p.getHealth());
        o.addProperty("maxHp", p.getMaxHealth());
        o.addProperty("absorb", p.getAbsorptionAmount());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("sat", p.getFoodData().getSaturationLevel());
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("xp", p.experienceProgress);
        o.addProperty("air", p.getAirSupply());
        o.addProperty("maxAir", p.getMaxAirSupply());
        o.addProperty("x", p.getBlockX());
        o.addProperty("y", p.getBlockY());
        o.addProperty("z", p.getBlockZ());
        o.addProperty("yaw", Math.round(p.getYRot()));
        o.addProperty("dim", lv.dimension().location().toString());
        lv.getBiome(p.blockPosition()).unwrapKey().ifPresent(k -> o.addProperty("biome",
                Component.translatable(k.location().toLanguageKey("biome")).getString()));
        o.addProperty("time", lv.getDayTime() % 24000);
        o.addProperty("day", lv.getDayTime() / 24000);
        o.addProperty("weather", lv.isThundering() ? "thunder" : lv.isRaining() ? "rain" : "clear");
        o.addProperty("fps", mc.getFps());
        o.addProperty("selected", p.getInventory().selected);

        // Inventory slots: s = inventory index (0-8 hotbar, 9-35 main, 36-39 armor feet..head, 40 offhand),
        // m = index of that slot in the currently open menu (what clicks use); a chest etc. goes to "container".
        Inventory pi = p.getInventory();
        AbstractContainerMenu menu = p.containerMenu;
        int[] menuIndex = new int[pi.getContainerSize()];
        java.util.Arrays.fill(menuIndex, -1);
        JsonArray box = new JsonArray();
        for (int m = 0; m < menu.slots.size(); m++) {
            Slot sl = menu.slots.get(m);
            if (sl.container == pi) { if (sl.getContainerSlot() < menuIndex.length) menuIndex[sl.getContainerSlot()] = m; }
            else if (menu != p.inventoryMenu) {
                JsonObject it = sl.getItem().isEmpty() ? new JsonObject() : stackJson(sl.getItem());
                it.addProperty("m", m);
                box.add(it);
            }
        }
        JsonArray inv = new JsonArray();
        for (int i = 0; i < pi.getContainerSize(); i++) {
            ItemStack st = pi.getItem(i);
            if (st.isEmpty()) continue;
            JsonObject it = stackJson(st);
            it.addProperty("s", i);
            it.addProperty("m", menuIndex[i]);
            inv.add(it);
        }
        o.add("inv", inv);
        JsonArray slotMap = new JsonArray();
        for (int m : menuIndex) slotMap.add(m);
        o.add("slotMap", slotMap);
        if (menu != p.inventoryMenu && mc.screen != null) {
            JsonObject c = new JsonObject();
            c.addProperty("title", mc.screen.getTitle().getString());
            c.add("items", box);
            o.add("container", c);
        }

        JsonArray fx = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject j = new JsonObject();
            e.getEffect().unwrapKey().ifPresent(k -> j.addProperty("id", k.location().toString()));
            j.addProperty("name", e.getEffect().value().getDisplayName().getString());
            j.addProperty("amp", e.getAmplifier());
            j.addProperty("dur", e.isInfiniteDuration() ? -1 : e.getDuration());
            j.addProperty("good", e.getEffect().value().isBeneficial());
            fx.add(j);
        }
        o.add("effects", fx);

        JsonArray players = new JsonArray();  // other players the client can see (multiplayer)
        for (var other : lv.players()) {
            if (other == p) continue;
            JsonObject j = new JsonObject();
            j.addProperty("name", other.getName().getString());
            j.addProperty("x", other.getBlockX());
            j.addProperty("z", other.getBlockZ());
            players.add(j);
        }
        o.add("players", players);
        o.addProperty("chatSeq", Memory.chatSeq());
        o.add("pins", Memory.pinsJson(mc));

        JsonObject target = target(mc, lv, p);
        if (target != null) o.add("target", target);
        return o.toString();
    }

    private static JsonObject stackJson(ItemStack st) {
        JsonObject it = new JsonObject();
        it.addProperty("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        it.addProperty("n", st.getCount());
        it.addProperty("name", st.getHoverName().getString());
        if (st.isDamageableItem()) {
            it.addProperty("dmg", st.getDamageValue());
            it.addProperty("max", st.getMaxDamage());
        }
        if (st.isEnchanted()) it.addProperty("ench", true);
        return it;
    }

    /** What the crosshair points at. For blocks we also ask the block entity for its goggle tooltip
     *  (Create's IHaveGoggleInformation and every addon that implements it) — by reflection, so no compile dep. */
    private static JsonObject target(Minecraft mc, ClientLevel lv, LocalPlayer p) {
        HitResult h = mc.hitResult;
        if (h == null || h.getType() == HitResult.Type.MISS) return null;
        JsonObject t = new JsonObject();
        if (h instanceof BlockHitResult b) {
            BlockPos pos = b.getBlockPos();
            BlockState st = lv.getBlockState(pos);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            t.addProperty("kind", "block");
            t.addProperty("name", st.getBlock().getName().getString());
            t.addProperty("icon", BuiltInRegistries.ITEM.getKey(st.getBlock().asItem()).toString());
            t.addProperty("mod", modName(id.getNamespace()));
            t.addProperty("pos", pos.getX() + " " + pos.getY() + " " + pos.getZ());
            JsonArray lines = goggleLines(lv.getBlockEntity(pos), p.isShiftKeyDown());
            if (lines != null) t.add("lines", lines);
        } else if (h instanceof EntityHitResult e) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType());
            t.addProperty("kind", "entity");
            t.addProperty("name", e.getEntity().getDisplayName().getString());
            t.addProperty("mod", modName(id.getNamespace()));
            if (e.getEntity() instanceof LivingEntity le) {
                t.addProperty("hp", le.getHealth());
                t.addProperty("maxHp", le.getMaxHealth());
            }
        }
        return t;
    }

    static JsonArray goggleLines(BlockEntity be, boolean sneaking) {
        if (be == null) return null;
        Optional<Method> m = GOGGLES.computeIfAbsent(be.getClass(), c -> {
            try { return Optional.of(c.getMethod("addToGoggleTooltip", List.class, boolean.class)); }
            catch (NoSuchMethodException e) { return Optional.empty(); }
        });
        if (m.isEmpty()) return null;
        try {
            List<Component> out = new ArrayList<>();
            m.get().invoke(be, out, sneaking);
            JsonArray a = new JsonArray();
            for (Component c : out) a.add(c.getString());
            return a.isEmpty() ? null : a;
        } catch (Exception e) {
            return null;
        }
    }

    private static String modName(String ns) {
        return ModList.get().getModContainerById(ns).map(c -> c.getModInfo().getDisplayName()).orElse(ns);
    }

    static String tooltip(int slot) {
        String s = onMain(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || slot < 0 || slot >= mc.player.getInventory().getContainerSize()) return "[]";
            ItemStack st = mc.player.getInventory().getItem(slot);
            JsonArray a = new JsonArray();
            if (!st.isEmpty())
                for (Component c : st.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL))
                    a.add(c.getString());
            return a.toString();
        });
        return s == null ? "[]" : s;
    }

    /** Clicks a slot of the open menu like the mouse would. type: quick (shift-click), throw (drop stack), swap (with slot b).
     *  Goes through the normal container-click packet, so the server validates it. */
    static void click(int m, String type, int b) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            var p = mc.player;
            if (p == null || mc.gameMode == null) return;
            AbstractContainerMenu menu = p.containerMenu;
            int n = menu.slots.size();
            if (m < 0 || m >= n) return;
            int id = menu.containerId;
            switch (type) {
                case "quick" -> mc.gameMode.handleInventoryMouseClick(id, m, 0, ClickType.QUICK_MOVE, p);
                case "throw" -> mc.gameMode.handleInventoryMouseClick(id, m, 1, ClickType.THROW, p);
                case "swap" -> {
                    if (b < 0 || b >= n || b == m || !menu.getCarried().isEmpty()) return;
                    // pick A, put on B (picks up what was there), put that back on A
                    mc.gameMode.handleInventoryMouseClick(id, m, 0, ClickType.PICKUP, p);
                    mc.gameMode.handleInventoryMouseClick(id, b, 0, ClickType.PICKUP, p);
                    if (!menu.getCarried().isEmpty()) mc.gameMode.handleInventoryMouseClick(id, m, 0, ClickType.PICKUP, p);
                }
                default -> { }
            }
        });
    }

    static void selectSlot(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(() -> {
            if (mc.player != null && slot >= 0 && slot < 9) mc.player.getInventory().selected = slot;
        });
    }

    /** "ns/path" from a URL -> the item, or null. */
    private static ItemStack stackOf(String nsPath) {
        ResourceLocation rl = ResourceLocation.tryParse(nsPath.replaceFirst("/", ":"));
        Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        return item == Items.AIR ? null : new ItemStack(item);
    }

    /** Item icon rendered exactly like in the inventory (3D blocks, custom models), 64x64.
     *  ponytail: cache never invalidates on resource-pack reload; restart the game after changing packs. */
    static byte[] icon(String nsPath) {
        byte[] cached = ICONS.get(nsPath);
        if (cached != null) return cached;
        byte[] b = onMain(() -> {
            ItemStack st = stackOf(nsPath);
            return st == null ? null : Render.png(16, 16, 4, g -> g.renderItem(st, 0, 0));
        });
        if (b != null) ICONS.put(nsPath, b);
        return b;
    }

    /** Item search by display name (game language) or id, for the recipes tab. */
    static String search(String query) {
        String q = query.toLowerCase(java.util.Locale.ROOT).trim();
        String s = onMain(() -> {
            JsonArray a = new JsonArray();
            if (q.length() < 2) return a.toString();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                String name = item.getDescription().getString();
                if (!name.toLowerCase(java.util.Locale.ROOT).contains(q) && !id.contains(q)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                o.addProperty("name", name);
                a.add(o);
                if (a.size() >= 120) break;
            }
            return a.toString();
        });
        return s == null ? "[]" : s;
    }

    private static final int MAX_RECIPES = 40;

    /** JEI recipe list for an item: [{title,w,h}], or {"noJei":true}. Images come from recipePng with the same index. */
    static String recipes(String nsPath, boolean uses) {
        if (!ModList.get().isLoaded("jei")) return "{\"noJei\":true}";
        String s = onMain(() -> {
            ItemStack st = stackOf(nsPath);
            JsonArray a = new JsonArray();
            if (st == null) return a.toString();
            for (var l : SideDashJei.layouts(st, uses, MAX_RECIPES)) {
                JsonObject o = new JsonObject();
                o.addProperty("title", l.getRecipeCategory().getTitle().getString());
                o.addProperty("w", l.getRect().getWidth());
                o.addProperty("h", l.getRect().getHeight());
                a.add(o);
            }
            return a.toString();
        });
        return s == null ? "[]" : s;
    }

    static byte[] recipePng(String nsPath, boolean uses, int index) {
        if (!ModList.get().isLoaded("jei")) return null;
        return onMain(() -> {
            ItemStack st = stackOf(nsPath);
            if (st == null) return null;
            var all = SideDashJei.layouts(st, uses, index + 1);
            if (index < 0 || index >= all.size()) return null;
            var l = all.get(index);
            int pad = 4, w = l.getRect().getWidth() + pad * 2, h = l.getRect().getHeight() + pad * 2;
            l.setPosition(pad, pad);
            return Render.png(w, h, 3, g -> {
                g.fill(0, 0, w, h, 0xFFC6C6C6);  // classic inventory grey so JEI's dark text stays readable
                l.drawRecipe(g, -1000, -1000);
            });
        });
    }

    /** Raw PNG from assets/<ns>/textures/<path>.png across all loaded mods/resource packs. */
    static byte[] texture(String nsPath) {
        int slash = nsPath.indexOf('/');
        if (slash < 0 || nsPath.contains("..")) return null;
        ResourceLocation rl = ResourceLocation.tryBuild(nsPath.substring(0, slash), "textures/" + nsPath.substring(slash + 1) + ".png");
        Minecraft mc = Minecraft.getInstance();
        if (rl == null || mc == null) return null;
        Optional<Resource> r = mc.getResourceManager().getResource(rl);
        if (r.isEmpty()) return null;
        try (InputStream in = r.get().open()) { return in.readAllBytes(); } catch (Exception e) { return null; }
    }

}
