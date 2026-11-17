package dev.oda.sidedash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Stream-deck actions the Minecraft adapter offers to the hub: every key binding (vanilla + all mods), and commands.
 * ids: "key:<binding name>" presses the binding once; "cmd:<text>" sends a command (or chat line without '/').
 */
final class Deck {
    static String actions() {
        String s = GameData.onMain(() -> {
            JsonArray a = new JsonArray();
            for (KeyMapping k : Minecraft.getInstance().options.keyMappings) {
                JsonObject o = new JsonObject();
                o.addProperty("id", "key:" + k.getName());
                o.addProperty("label", Component.translatable(k.getName()).getString());
                o.addProperty("group", Component.translatable(k.getCategory()).getString());
                o.addProperty("bound", k.getTranslatedKeyMessage().getString());
                a.add(o);
            }
            return a.toString();
        });
        return s == null ? "[]" : s;
    }

    static void run(String id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || id == null) return;
        mc.execute(() -> {
            if (id.startsWith("key:")) {
                for (KeyMapping k : mc.options.keyMappings)
                    if (k.getName().equals(id.substring(4))) {
                        // ponytail: a single click; bindings that must be *held* (sprint, jetpack) need press/release support
                        KeyMapping.click(k.getKey());
                        return;
                    }
            } else if (id.startsWith("cmd:") && mc.player != null) {
                String text = id.substring(4).strip();
                if (text.isEmpty()) return;
                if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
                else mc.player.connection.sendChat(text);
            }
        });
    }
}
