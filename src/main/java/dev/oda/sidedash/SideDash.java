package dev.oda.sidedash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;

/**
 * Minecraft adapter for SideDash Hub. Runs a localhost-only server (random port + random token) and registers it
 * in %LOCALAPPDATA%\SideDash\sources so the hub can proxy the tablet to it. The tablet only ever talks to the hub.
 */
@Mod(value = SideDash.ID, dist = Dist.CLIENT)
public final class SideDash {
    public static final String ID = "sidedash";
    static final Logger LOG = LogUtils.getLogger();
    static final String HUB_RELEASES = "https://github.com/0oda0/SideDash/releases";

    public SideDash() {
        String token = randomToken();
        int port = WebServer.start(token);
        if (port > 0) register(port, token);
        WorldMap.init();
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) ->
                e.getPlayer().displayClientMessage(urlMessage(), false));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) ->
                e.getDispatcher().register(Commands.literal(ID).executes(c -> {
                    c.getSource().sendSuccess(SideDash::urlMessage, false);
                    return 1;
                })));
    }

    static Path hubDir() {
        String base = System.getenv("LOCALAPPDATA");
        return (base != null ? Paths.get(base) : Paths.get(System.getProperty("user.home"), ".local", "share")).resolve("SideDash");
    }

    private static String randomToken() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append("abcdefghijkmnpqrstuvwxyz23456789".charAt(rnd.nextInt(32)));
        return sb.toString();
    }

    /** One file per game process; the hub drops it once the process is gone, we also delete it on exit. */
    private static void register(int port, String token) {
        long pid = ProcessHandle.current().pid();
        Path file = hubDir().resolve("sources").resolve("minecraft-" + pid + ".json");
        JsonObject o = new JsonObject();
        o.addProperty("id", "minecraft");
        o.addProperty("name", "Minecraft");
        o.addProperty("url", "http://127.0.0.1:" + port + "/" + token + "/");
        o.addProperty("pid", pid);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(file); } catch (Exception ignored) { }
            }));
        } catch (Exception e) {
            LOG.error("SideDash: can't register with the hub", e);
        }
    }

    /** Tablet address comes from the hub (hub.json); without a hub, point to where to get it. */
    static MutableComponent urlMessage() {
        MutableComponent msg = Component.literal("[SideDash] ").withStyle(ChatFormatting.AQUA);
        try {
            JsonObject hub = JsonParser.parseString(Files.readString(hubDir().resolve("hub.json"))).getAsJsonObject();
            msg.append(Component.translatable("sidedash.open").withStyle(ChatFormatting.WHITE));
            for (var u : hub.getAsJsonArray("urls")) msg.append(link("\n  " + u.getAsString(), u.getAsString()));
        } catch (Exception e) {
            msg.append(Component.translatable("sidedash.noHub").withStyle(ChatFormatting.WHITE))
               .append(link(" " + HUB_RELEASES, HUB_RELEASES));
        }
        return msg;
    }

    private static MutableComponent link(String text, String url) {
        return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.YELLOW).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }
}
