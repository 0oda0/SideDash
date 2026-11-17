using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using QRCoder;

namespace SideDash.Hub;

/// <summary>
/// SideDash Hub: the one program you install. Tablet connects here (LAN, secret key in the URL);
/// games plug in through adapters (a mod, a BepInEx plugin, ...) that run a localhost-only server
/// and register themselves by dropping a JSON file into %LOCALAPPDATA%\SideDash\sources.
/// The hub proxies /{key}/g/{id}/... to the adapter, so the adapter ships its own dashboard page.
/// </summary>
static class Program
{
    static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SideDash");
    static readonly string SourcesDir = Path.Combine(Dir, "sources");
    static readonly HttpClient Http = new(new SocketsHttpHandler { UseProxy = false }) { Timeout = Timeout.InfiniteTimeSpan };

    record HubConfig(int Port, string Key);
    public record Source(string Id, string Name, string Url, int Pid);

    static HubConfig Cfg = null!;

    [STAThread]
    static void Main()
    {
        using var single = new Mutex(true, "SideDashHub", out bool first);
        if (!first) return;  // already running in the tray

        Directory.CreateDirectory(SourcesDir);
        Cfg = LoadConfig();
        File.WriteAllText(Path.Combine(Dir, "hub.json"),
            JsonSerializer.Serialize(new { port = Cfg.Port, key = Cfg.Key, urls = Urls() }));

        var web = StartWeb();

        ApplicationConfiguration.Initialize();
        var window = new HubWindow(Urls, LiveSources);
        using var tray = new NotifyIcon { Icon = window.Icon, Text = "SideDash Hub", Visible = true };
        tray.DoubleClick += (_, _) => window.ShowAndFocus();
        var menu = new ContextMenuStrip();
        menu.Items.Add("Показать QR-код", null, (_, _) => window.ShowAndFocus());
        menu.Items.Add("Открыть в браузере", null, (_, _) => Process.Start(new ProcessStartInfo(Urls()[0]) { UseShellExecute = true }));
        var autostart = new ToolStripMenuItem("Запускать с Windows") { Checked = Autostart.Enabled, CheckOnClick = true };
        autostart.CheckedChanged += (_, _) => Autostart.Set(autostart.Checked);
        menu.Items.Add(autostart);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Выход", null, (_, _) => Application.Exit());
        tray.ContextMenuStrip = menu;

        if (!Environment.GetCommandLineArgs().Contains("--tray")) window.ShowAndFocus();
        Application.Run();
        tray.Visible = false;
        web.StopAsync().Wait(2000);
    }

    static HubConfig LoadConfig()
    {
        var file = Path.Combine(Dir, "hub-config.json");
        try { var c = JsonSerializer.Deserialize<HubConfig>(File.ReadAllText(file)); if (c is { Key.Length: >= 4 }) return c; }
        catch { }
        const string abc = "abcdefghjkmnpqrstuvwxyz23456789";
        var key = new string(Enumerable.Range(0, 6).Select(_ => abc[RandomNumberGenerator.GetInt32(abc.Length)]).ToArray());
        var cfg = new HubConfig(25580, key);
        File.WriteAllText(file, JsonSerializer.Serialize(cfg));
        return cfg;
    }

    /// <summary>LAN URLs for the tablet, home-router ranges first; skips virtual adapters.</summary>
    public static List<string> Urls()
    {
        var ips = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                        && !System.Text.RegularExpressions.Regex.IsMatch(n.Description + n.Name,
                            "virtual|vmware|hyper-v|vethernet|vbox|wsl|tap|tun|zerotier|radmin|hamachi", System.Text.RegularExpressions.RegexOptions.IgnoreCase))
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Select(a => a.Address)
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a))
            .Select(a => a.ToString())
            .OrderBy(a => a.StartsWith("192.168.") ? 0 : 1)
            .ToList();
        if (ips.Count == 0) ips.Add("localhost");
        return ips.Select(ip => $"http://{ip}:{Cfg.Port}/{Cfg.Key}/").ToList();
    }

    static bool IsPrivate(IPAddress a)
    {
        var b = a.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] < 32) || (b[0] == 192 && b[1] == 168);
    }

    /// <summary>Registered adapters whose process is still running; stale files are removed. Newest wins per id.</summary>
    public static List<Source> LiveSources()
    {
        var list = new List<(Source s, DateTime t)>();
        foreach (var f in Directory.GetFiles(SourcesDir, "*.json"))
        {
            try
            {
                var s = JsonSerializer.Deserialize<Source>(File.ReadAllText(f), new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                if (s == null || !Alive(s.Pid)) { File.Delete(f); continue; }
                list.Add((s, File.GetLastWriteTimeUtc(f)));
            }
            catch { /* half-written file: next poll */ }
        }
        return list.OrderByDescending(x => x.t).Select(x => x.s).DistinctBy(s => s.Id).ToList();
    }

    static bool Alive(int pid)
    {
        try { return !Process.GetProcessById(pid).HasExited; } catch { return false; }
    }

    static WebApplication StartWeb()
    {
        var b = WebApplication.CreateSlimBuilder();
        b.WebHost.ConfigureKestrel(k => k.ListenAnyIP(Cfg.Port));
        b.Logging.ClearProviders();
        var app = b.Build();
        string k = Cfg.Key;
        string home = Resource("home.html");
        string hubJs = Resource("NoSleep.min.js") + "\n" + Resource("hub.js");
        InjectTag = $"<script src=\"/{k}/hub.js\"></script>";

        // routing treats "/k" and "/k/" as one route; the page uses relative links, so it needs the slash
        app.MapGet($"/{k}", (HttpContext c) => c.Request.Path.Value!.EndsWith('/')
            ? Results.Content(home, "text/html; charset=utf-8") : Results.Redirect($"/{k}/"));
        app.MapGet($"/{k}/hub.js", () => Results.Content(hubJs, "text/javascript; charset=utf-8"));

        // stream deck: page, per-game button layout, OS-level key presses
        string deck = Resource("deck.html");
        app.MapGet($"/{k}/deck", (HttpContext c) => c.Request.Path.Value!.EndsWith('/')
            ? Results.Content(deck, "text/html; charset=utf-8") : Results.Redirect($"/{k}/deck/" + c.Request.QueryString));
        app.MapGet($"/{k}/deck/layout/{{game}}", (string game) =>
            File.Exists(DeckFile(game)) ? Results.Text(File.ReadAllText(DeckFile(game)), "application/json") : Results.Text("null", "application/json"));
        app.MapPut($"/{k}/deck/layout/{{game}}", async (string game, HttpRequest r) =>
        {
            using var doc = await JsonDocument.ParseAsync(r.Body);  // validate before saving
            if (doc.RootElement.ValueKind != JsonValueKind.Array) return Results.BadRequest();
            File.WriteAllText(DeckFile(game), doc.RootElement.GetRawText());
            return Results.NoContent();
        });
        app.MapPost($"/{k}/deck/key", (string combo) => Keys2.Press(combo) ? Results.NoContent() : Results.BadRequest("unknown key"));
        app.MapGet($"/{k}/sources", () => Results.Json(LiveSources().Select(s => new { s.Id, s.Name })));
        app.Map($"/{k}/g/{{id}}/{{**rest}}", Proxy);
        app.MapFallback(() => Results.NotFound("not found"));
        app.StartAsync().Wait();
        return app;
    }

    static string InjectTag = "";

    static string DeckFile(string game) =>
        Path.Combine(Dir, "deck-" + System.Text.RegularExpressions.Regex.Replace(game, "[^a-z0-9_-]", "_") + ".json");

    static string Resource(string name) =>
        new StreamReader(typeof(Program).Assembly.GetManifestResourceStream("SideDash.Hub." + name)!).ReadToEnd();

    /// <summary>Streams the request to the adapter and the response back (works for SSE too).
    /// HTML pages get the hub's shared script (keep-awake, alerts) injected.</summary>
    static async Task Proxy(HttpContext ctx, string id, string? rest)
    {
        var src = LiveSources().FirstOrDefault(s => s.Id == id);
        if (src == null) { ctx.Response.StatusCode = 404; await ctx.Response.WriteAsync("game is not running"); return; }

        var req = new HttpRequestMessage(new HttpMethod(ctx.Request.Method), src.Url.TrimEnd('/') + "/" + rest + ctx.Request.QueryString);
        if (ctx.Request.ContentLength > 0) req.Content = new StreamContent(ctx.Request.Body);
        try
        {
            using var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ctx.RequestAborted);
            ctx.Response.StatusCode = (int)resp.StatusCode;
            if (resp.Content.Headers.ContentType?.MediaType == "text/html")
            {
                var html = await resp.Content.ReadAsStringAsync(ctx.RequestAborted);
                int at = html.LastIndexOf("</body>", StringComparison.OrdinalIgnoreCase);
                html = at < 0 ? html + InjectTag : html.Insert(at, InjectTag);
                ctx.Response.Headers.CacheControl = "no-cache";
                await Results.Content(html, "text/html; charset=utf-8").ExecuteAsync(ctx);
                return;
            }
            foreach (var h in resp.Headers.Concat(resp.Content.Headers))
                if (h.Key is not ("Transfer-Encoding" or "Connection")) ctx.Response.Headers[h.Key] = h.Value.ToArray();
            await using var body = await resp.Content.ReadAsStreamAsync(ctx.RequestAborted);
            var buf = new byte[16384];
            for (int n; (n = await body.ReadAsync(buf, ctx.RequestAborted)) > 0;)
            {
                await ctx.Response.Body.WriteAsync(buf.AsMemory(0, n), ctx.RequestAborted);
                await ctx.Response.Body.FlushAsync(ctx.RequestAborted);  // SSE needs every chunk right away
            }
        }
        catch (OperationCanceledException) { }
        catch (HttpRequestException) { if (!ctx.Response.HasStarted) { ctx.Response.StatusCode = 502; await ctx.Response.WriteAsync("game not responding"); } }
    }

    public static Bitmap Qr(string text)
    {
        using var data = new QRCodeGenerator().CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        using var ms = new MemoryStream(new PngByteQRCode(data).GetGraphic(8));
        return new Bitmap(ms);
    }
}

static class Autostart
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static bool Enabled
    {
        get { using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey); return k?.GetValue("SideDashHub") != null; }
    }
    public static void Set(bool on)
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) k.SetValue("SideDashHub", $"\"{Environment.ProcessPath}\" --tray"); else k.DeleteValue("SideDashHub", false);
    }
}

/// <summary>Small window: QR code for the tablet + which games are connected.</summary>
sealed class HubWindow : Form
{
    readonly Func<List<string>> urls;
    readonly Func<List<Program.Source>> sources;
    readonly PictureBox qr = new() { SizeMode = PictureBoxSizeMode.Zoom, Dock = DockStyle.Top, Height = 300 };
    readonly Label url = new() { Dock = DockStyle.Top, Height = 48, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 11) };
    readonly Label games = new() { Dock = DockStyle.Fill, TextAlign = ContentAlignment.TopCenter, Font = new Font("Segoe UI", 10), ForeColor = Color.Gainsboro };

    public HubWindow(Func<List<string>> urls, Func<List<Program.Source>> sources)
    {
        this.urls = urls; this.sources = sources;
        Text = "SideDash Hub";
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application;
        ClientSize = new Size(360, 470);
        FormBorderStyle = FormBorderStyle.FixedSingle; MaximizeBox = false;
        BackColor = Color.FromArgb(14, 18, 21); ForeColor = Color.White;
        var hint = new Label { Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter,
            Text = "Наведи камеру планшета на QR-код\n(планшет в той же Wi-Fi сети)", ForeColor = Color.Silver };
        Controls.Add(games); Controls.Add(url); Controls.Add(qr); Controls.Add(hint);
        url.Click += (_, _) => Clipboard.SetText(url.Text);
        var timer = new System.Windows.Forms.Timer { Interval = 2000 };
        timer.Tick += (_, _) => Refresh2();
        timer.Start();
        FormClosing += (_, e) => { if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };  // close = back to tray
    }

    public void ShowAndFocus() { Refresh2(); Show(); WindowState = FormWindowState.Normal; Activate(); }

    void Refresh2()
    {
        if (!Visible && qr.Image != null) return;
        var u = urls()[0];
        if (url.Text != u) { qr.Image?.Dispose(); qr.Image = Program.Qr(u); url.Text = u; }
        var live = sources();
        games.Text = live.Count == 0 ? "Игр пока не подключено.\nЗапусти игру с адаптером SideDash." : "Подключено: " + string.Join(", ", live.Select(s => s.Name));
    }
}
