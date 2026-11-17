package dev.oda.sidedash;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

@Mod(value = SideDash.ID, dist = Dist.CLIENT)
public final class SideDash {
    public static final String ID = "sidedash";
    static final Logger LOG = LogUtils.getLogger();
    static int port;
    static String key;

    public SideDash() {
        loadConfig();
        WebServer.start(port, key);
        WorldMap.init();
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) ->
                e.getPlayer().displayClientMessage(urlMessage(), false));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) ->
                e.getDispatcher().register(Commands.literal(ID).executes(c -> {
                    c.getSource().sendSuccess(SideDash::urlMessage, false);
                    return 1;
                })));
    }

    /** config/sidedash.properties: port + secret key that is part of the URL (keeps other LAN devices out). */
    private static void loadConfig() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("sidedash.properties");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) { p.load(r); } catch (Exception ignored) { }
        port = Integer.parseInt(p.getProperty("port", "25580").trim());
        key = p.getProperty("key", "").trim();
        if (!key.matches("[a-z0-9]{4,32}")) {
            SecureRandom rnd = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append("abcdefghjkmnpqrstuvwxyz23456789".charAt(rnd.nextInt(31)));
            key = sb.toString();
        }
        p.setProperty("port", String.valueOf(port));
        p.setProperty("key", key);
        try (Writer w = Files.newBufferedWriter(file)) {
            p.store(w, "SideDash: open http://<pc-ip>:<port>/<key>/ on your tablet");
        } catch (Exception e) { LOG.warn("SideDash: can't save config", e); }
    }

    static MutableComponent urlMessage() {
        MutableComponent msg = Component.literal("[SideDash] ").withStyle(ChatFormatting.AQUA)
                .append(Component.translatable("sidedash.open").withStyle(ChatFormatting.WHITE));
        for (String ip : lanIps()) {
            String url = "http://" + ip + ":" + port + "/" + key + "/";
            msg.append(Component.literal("\n  " + url).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
                    .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
        }
        return msg;
    }

    /** LAN IPv4s, home-router ranges first; skips loopback and obvious virtual adapters. */
    static List<String> lanIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()
                        || n.matches(".*(virtual|vmware|hyper-v|vethernet|vbox|wsl|tap|tun|zerotier|radmin|hamachi).*"))
                    continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> Boolean.compare(!a.startsWith("192.168."), !b.startsWith("192.168.")));
        if (out.isEmpty()) out.add("localhost");
        return out;
    }
}
