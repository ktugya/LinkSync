using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace LinkSync;

public sealed class ApiServer : IAsyncDisposable
{
    private readonly AppState _state;
    private readonly ClipboardSync _clipboard;
    private readonly TextService _text;
    private readonly PeerClient _client = new();
    private WebApplication? _app;

    public string Url { get; private set; } = "http://127.0.0.1:47821";

    public ApiServer(AppState state, ClipboardSync clipboard, TextService text)
    {
        _state = state;
        _clipboard = clipboard;
        _text = text;
    }

    public void Start(int port)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = AppContext.BaseDirectory
        });
        builder.Logging.ClearProviders();
        builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
        builder.Services.ConfigureHttpJsonOptions(o =>
        {
            o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
        });

        var app = builder.Build();
        app.UseDefaultFiles();
        app.UseStaticFiles();
        Map(app);
        app.Start();
        _app = app;
        Url = $"http://127.0.0.1:{port}";
        Log.Write($"http server on 0.0.0.0:{port}, ui {Url}");
    }

    private void Map(WebApplication app)
    {
        app.MapGet("/api/health", () => Results.Text("ok"));

        app.MapGet("/api/info", () => Results.Json(new
        {
            id = _state.Settings.Id,
            name = _state.Settings.Name,
            platform = "windows",
            port = _state.Settings.Port,
            version = "1.0"
        }, Json.Options));

        app.MapGet("/api/state", () => Results.Text(_state.SnapshotJson(), "application/json; charset=utf-8"));

        app.MapPost("/api/clipboard", (ClipboardDto dto) =>
        {
            if (string.IsNullOrEmpty(dto.Text)) return Results.BadRequest(new { error = "empty" });
            _clipboard.ApplyRemote(dto.Text, dto.Origin ?? "", dto.OriginName ?? "");
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/text", (TextDto dto) =>
        {
            if (string.IsNullOrEmpty(dto.Text)) return Results.BadRequest(new { error = "empty" });
            var id = string.IsNullOrWhiteSpace(dto.Id) ? Guid.NewGuid().ToString("N") : dto.Id!;
            if (!_state.HasMessage(id))
            {
                _state.AddMessage(new MessageEntry
                {
                    Id = id,
                    Text = dto.Text,
                    From = dto.From ?? "",
                    FromName = dto.FromName ?? ""
                });
                _state.NotifyUser($"收到来自 {dto.FromName ?? "设备"} 的文本");
            }
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/text/send", async (TextSendDto dto) =>
        {
            if (string.IsNullOrWhiteSpace(dto.Text)) return Results.BadRequest(new { error = "empty" });
            await _text.SendAsync(dto.To ?? Array.Empty<string>(), dto.Text);
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/settings", (SettingsDto dto) =>
        {
            _state.UpdateSettings(dto);
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/peers/add", async (AddPeerDto dto) =>
        {
            if (string.IsNullOrWhiteSpace(dto.Addr)) return Results.BadRequest(new { error = "empty" });
            if (!TryParseAddress(dto.Addr.Trim(), out var host, out var port))
                return Results.BadRequest(new { error = "bad address" });
            var info = await _client.FetchInfoAsync(host, port);
            if (info == null) return Results.Json(new { ok = false, error = "无法连接" }, Json.Options, statusCode: 502);
            _state.TouchPeer(info.Id, info.Name, info.Platform, host, info.Port > 0 ? info.Port : port);
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/local/clipboard", () =>
        {
            var text = _clipboard.ReadLocal();
            if (string.IsNullOrEmpty(text)) return Results.Json(new { ok = false, text = "" }, Json.Options);
            _state.AddClipboard(text, _state.Settings.Id, _state.Settings.Name);
            _ = _clipboard.BroadcastAsync(text);
            return Results.Json(new { ok = true, text }, Json.Options);
        });

        app.MapPost("/api/local/copy", (CopyDto dto) =>
        {
            if (!string.IsNullOrEmpty(dto.Text)) _clipboard.CopyLocal(dto.Text);
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/transfers/clear", () =>
        {
            _state.ClearFinishedTransfers();
            return Results.Json(new { ok = true }, Json.Options);
        });

        app.MapPost("/api/send", async (HttpContext ctx, string peer) =>
        {
            var target = _state.GetPeer(peer);
            if (target == null) return Results.NotFound(new { error = "peer not found" });
            var name = HeaderName(ctx, "X-File-Name", "file");
            var size = ParseLong(ctx.Request.Headers["X-File-Size"].ToString(), ctx.Request.ContentLength ?? 0);
            var transfer = _state.AddTransfer("out", target, name, size);
            var result = await _client.PushFileAsync(target, name, size,
                _state.Settings.Id, _state.Settings.Name,
                ctx.Request.Body,
                bytes => _state.UpdateTransfer(transfer.Id, bytes),
                ctx.RequestAborted);
            if (result.Ok)
            {
                _state.FinishTransfer(transfer.Id, result.Path, null);
                return Results.Json(new { ok = true, path = result.Path }, Json.Options);
            }
            _state.FinishTransfer(transfer.Id, null, result.Error);
            return Results.Json(new { ok = false, error = result.Error }, Json.Options, statusCode: 502);
        });

        app.MapPost("/api/upload", async (HttpContext ctx) =>
        {
            var name = HeaderName(ctx, "X-File-Name", "file");
            var fromId = ctx.Request.Headers["X-From-Id"].ToString();
            var fromName = HeaderFolder(ctx, "X-From-Name");
            var size = ParseLong(ctx.Request.Headers["X-File-Size"].ToString(), ctx.Request.ContentLength ?? 0);
            var dir = Path.Combine(_state.Settings.DownloadDir, fromName);
            Directory.CreateDirectory(dir);
            var path = FileUtil.Unique(dir, name);
            var peer = _state.GetPeer(fromId) ?? new Peer { Id = fromId, Name = fromName, Platform = "android" };
            var transfer = _state.AddTransfer("in", peer, name, size);
            try
            {
                await using (var fs = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                                 1024 * 1024, useAsync: true))
                {
                    await ctx.Request.Body.CopyToAsync(fs, 1024 * 1024, ctx.RequestAborted);
                }
                _state.FinishTransfer(transfer.Id, path, null);
                _state.NotifyUser($"已接收 {name}");
                return Results.Json(new { ok = true, path }, Json.Options);
            }
            catch (Exception ex)
            {
                Log.Write("upload: " + ex);
                try { if (File.Exists(path)) File.Delete(path); } catch { }
                _state.FinishTransfer(transfer.Id, null, ex.Message);
                return Results.Json(new { ok = false, error = ex.Message }, Json.Options, statusCode: 500);
            }
        });
    }

    private static string HeaderName(HttpContext ctx, string key, string fallback)
    {
        try
        {
            var raw = ctx.Request.Headers[key].ToString();
            if (string.IsNullOrWhiteSpace(raw)) return fallback;
            return FileUtil.Sanitize(Uri.UnescapeDataString(raw));
        }
        catch { return fallback; }
    }

    private static string HeaderFolder(HttpContext ctx, string key)
    {
        try
        {
            var raw = ctx.Request.Headers[key].ToString();
            if (string.IsNullOrWhiteSpace(raw)) return "device";
            return FileUtil.SanitizeFolder(Uri.UnescapeDataString(raw));
        }
        catch { return "device"; }
    }

    private static long ParseLong(string? text, long fallback)
        => long.TryParse(text, out var value) ? value : fallback;

    private static bool TryParseAddress(string addr, out string host, out int port)
    {
        host = addr;
        port = 47821;
        var idx = addr.LastIndexOf(':');
        if (idx <= 0) return addr.Length > 0;
        host = addr[..idx];
        return int.TryParse(addr[(idx + 1)..], out port) && port > 0 && port < 65536;
    }

    public async ValueTask DisposeAsync()
    {
        if (_app == null) return;
        try { await _app.StopAsync(TimeSpan.FromSeconds(2)); } catch { }
        try { await _app.DisposeAsync(); } catch { }
        _app = null;
    }
}
