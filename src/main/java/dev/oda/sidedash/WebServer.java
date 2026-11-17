package dev.oda.sidedash;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Tiny HTTP server on java.base only (no deps, no module issues): static page, Server-Sent Events
 * for live data, PNGs for icons/map, POST for actions.
 * ponytail: hand-rolled HTTP/1.0-style (Connection: close, no keep-alive, no bodies) — enough for one LAN dashboard.
 */
final class WebServer {
    private static final byte[] NOT_FOUND = "not found".getBytes(StandardCharsets.UTF_8);

/** Listens on 127.0.0.1, random free port; only the hub on this PC talks to us. Returns the port, or -1. */
    static int start(String key) {
        ServerSocket ss;
        try {
            ss = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            SideDash.LOG.error("SideDash: can't open local server", e);
            return -1;
        }
        Thread.ofPlatform().daemon().name("SideDash-http").start(() -> {
            try (ss) {
                while (true) {
                    Socket s = ss.accept();
                    Thread.ofVirtual().start(() -> handle(s, key));
                }
            } catch (IOException e) {
                SideDash.LOG.error("SideDash: local server stopped", e);
            }
        });
        SideDash.LOG.info("SideDash listening on 127.0.0.1:{}", ss.getLocalPort());
        return ss.getLocalPort();
    }

    private static void handle(Socket s, String key) {
        try (s; InputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            s.setSoTimeout(10_000);
            String[] req = readLine(in).split(" ");
            while (!readLine(in).isEmpty()) { } // skip headers
            if (req.length < 2) return;
            String method = req[0], path = req[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

            String prefix = "/" + key;
            if (!path.equals(prefix) && !path.startsWith(prefix + "/")) { send(out, 404, "text/plain", NOT_FOUND, ""); return; }
            path = path.substring(prefix.length());

            if (path.isEmpty()) send(out, 302, "text/plain", new byte[0], "Location: " + prefix + "/\r\n");
            else if (path.equals("/")) {
                try (InputStream page = WebServer.class.getResourceAsStream("/web/index.html")) {
                    send(out, 200, "text/html; charset=utf-8", page.readAllBytes(), "Cache-Control: no-cache\r\n");
                }
            }
            else if (path.equals("/events")) events(s, out);
            else if (path.startsWith("/icon/")) png(out, GameData.icon(path.substring(6)), true);
            else if (path.startsWith("/tex/")) png(out, GameData.texture(path.substring(5)), true);
            else if (path.startsWith("/lib/") && !path.contains("..")) {
                try (InputStream f = WebServer.class.getResourceAsStream("/web" + path)) {
                    if (f == null) send(out, 404, "text/plain", NOT_FOUND, "");
                    else send(out, 200, path.endsWith(".css") ? "text/css" : "text/javascript", f.readAllBytes(), "Cache-Control: max-age=86400\r\n");
                }
            }
            else if (path.startsWith("/tile/")) {
                // /tile/<dim>/<tx>/<tz>.png
                String[] p = path.substring(6).replace(".png", "").split("/");
                byte[] b = WorldMap.tilePng(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                if (b == null) send(out, 204, "image/png", new byte[0], "Cache-Control: no-store\r\n");
                else send(out, 200, "image/png", b, "Cache-Control: no-cache\r\n");
            }
            else if (method.equals("POST") && path.equals("/inv/click")) {
                GameData.click(intParam(query, "m", -1), param(query, "type"), intParam(query, "b", -1));
                send(out, 204, "text/plain", new byte[0], "");
            }
            else if (path.equals("/deck/actions")) json(out, Deck.actions());
            else if (method.equals("POST") && path.equals("/deck/run")) {
                Deck.run(java.net.URLDecoder.decode(param(query, "id"), StandardCharsets.UTF_8));
                send(out, 204, "text/plain", new byte[0], "");
            }
            else if (path.equals("/waypoints")) json(out, WorldMap.waypointsJson());
            else if (method.equals("POST") && path.equals("/waypoint/add")) {
                WorldMap.addWaypoint(java.net.URLDecoder.decode(param(query, "name"), StandardCharsets.UTF_8),
                        intParam(query, "x", 0), intParam(query, "z", 0));
                json(out, WorldMap.waypointsJson());
            }
            else if (method.equals("POST") && path.startsWith("/waypoint/del/")) {
                WorldMap.deleteWaypoint(path.substring(14));
                json(out, WorldMap.waypointsJson());
            }
            else if (method.equals("POST") && path.equals("/tp")) {
                String wp = param(query, "wp");
                WorldMap.teleport(intParam(query, "x", 0), wp.isEmpty() ? null : WorldMap.waypointY(wp), intParam(query, "z", 0));
                send(out, 204, "text/plain", new byte[0], "");
            }
            else if (path.equals("/items"))
                json(out, GameData.search(java.net.URLDecoder.decode(param(query, "q"), StandardCharsets.UTF_8)));
            else if (path.startsWith("/recipes/craft/") || path.startsWith("/recipes/uses/"))
                json(out, GameData.recipes(path.substring(path.indexOf('/', 10) + 1), path.startsWith("/recipes/uses/")));
            else if (path.startsWith("/recipe/craft/") || path.startsWith("/recipe/uses/")) {
                // /recipe/<craft|uses>/<index>/<ns>/<path>
                String[] p = path.split("/", 5);
                png(out, GameData.recipePng(p[4], p[2].equals("uses"), Integer.parseInt(p[3])), false);
            }
            else if (path.startsWith("/tooltip/"))
                send(out, 200, "application/json", GameData.tooltip(Integer.parseInt(path.substring(9))).getBytes(StandardCharsets.UTF_8), "");
            else if (method.equals("POST") && path.startsWith("/slot/")) {
                GameData.selectSlot(Integer.parseInt(path.substring(6)));
                send(out, 204, "text/plain", new byte[0], "");
            }
            else send(out, 404, "text/plain", NOT_FOUND, "");
        } catch (Exception ignored) {
            // client went away or bad request — nothing to do
        }
    }

    /** Pushes a JSON snapshot whenever it changes (checked 5x/s), plus a heartbeat so proxies/OS don't drop us. */
    private static void events(Socket s, OutputStream out) throws Exception {
        s.setSoTimeout(0);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String last = null;
        long lastWrite = 0;
        while (true) {
            String json = GameData.snapshot();
            long now = System.currentTimeMillis();
            if (!json.equals(last)) {
                out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                last = json;
                lastWrite = now;
                out.flush();
            } else if (now - lastWrite > 15_000) {
                out.write(":\n\n".getBytes(StandardCharsets.UTF_8));
                lastWrite = now;
                out.flush();
            }
            Thread.sleep(200);
        }
    }

    private static void png(OutputStream out, byte[] data, boolean cache) throws IOException {
        if (data == null || data.length == 0) send(out, 404, "text/plain", NOT_FOUND, "");
        else send(out, 200, "image/png", data, cache ? "Cache-Control: max-age=86400\r\n" : "Cache-Control: no-store\r\n");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extraHeaders) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + (code < 300 ? "OK" : code < 400 ? "Found" : "Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n"
                + extraHeaders + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void json(OutputStream out, String body) throws IOException {
        send(out, 200, "application/json", body.getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\n");
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&"))
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        return "";
    }

    private static int intParam(String query, String name, int def) {
        try { return Integer.parseInt(param(query, name)); } catch (NumberFormatException e) { return def; }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) {
            if (b.size() > 8192) throw new IOException("line too long");
            if (c != '\r') b.write(c);
        }
        return b.toString(StandardCharsets.ISO_8859_1);
    }
}
