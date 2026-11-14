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
        builder.WebHost.ConfigureKestrel(o =>
        {
            // 局域网工具，文件走流式上传；Kestrel 默认 30,000,000 字节上限会把大文件挡死。
            o.Limits.MaxRequestBodySize = null;
            // 手机在弱 Wi-Fi 下可能短暂低速，关掉最小数据率限制，避免被误判超时中断。
            o.Limits.MinRequestBodyDataRate = null;
            o.Limits.MinResponseDataRate = null;
            o.Limits.KeepAliveTimeout = TimeSpan.FromMinutes(5);
        });
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
            if (size <= 0)
                return Results.Json(new { ok = false, error = "无法确定文件大小（缺少 X-File-Size）" },
                    Json.Options, statusCode: StatusCodes.Status400BadRequest);
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
            var declared = ParseLong(ctx.Request.Headers["X-File-Size"].ToString(), -1);
            var contentLength = ctx.Request.ContentLength ?? -1;

            // 只接受带 Content-Length 的请求：NanoHTTPD 风格的接收端不做 chunked 解码，
            // 两端保持一致，避免"看起来在传、其实收到 0 字节"。
            if (contentLength < 0)
                return Results.Json(new { ok = false, error = "缺少 Content-Length，不支持分块上传" },
                    Json.Options, statusCode: StatusCodes.Status411LengthRequired);

            if (declared > 0 && declared != contentLength)
                return Results.Json(new { ok = false, error = $"X-File-Size({declared}) 与 Content-Length({contentLength}) 不一致" },
                    Json.Options, statusCode: StatusCodes.Status400BadRequest);

            var size = declared > 0 ? declared : contentLength;
            var dir = Path.Combine(_state.Settings.DownloadDir, fromName);
            Directory.CreateDirectory(dir);
            var path = FileUtil.Unique(dir, name);
            var peer = _state.GetPeer(fromId) ?? new Peer { Id = fromId, Name = fromName, Platform = "android" };
            var transfer = _state.AddTransfer("in", peer, name, size);
            try
            {
                long written;
                await using (var fs = new FileStream(FileUtil.Extended(path), FileMode.CreateNew, FileAccess.Write,
                                 FileShare.None, 1024 * 1024, useAsync: true))
                {
                    var body = new ProgressStream(ctx.Request.Body, n => _state.UpdateTransfer(transfer.Id, n));
                    await body.CopyToAsync(fs, 1024 * 1024, ctx.RequestAborted);
                    await fs.FlushAsync(ctx.RequestAborted);
                    written = fs.Length;
                }

                if (written != contentLength)
                {
                    TryDelete(path);
                    var msg = $"传输不完整：{written}/{contentLength} 字节";
                    _state.FinishTransfer(transfer.Id, null, msg);
                    return Results.Json(new { ok = false, error = msg }, Json.Options,
                        statusCode: StatusCodes.Status400BadRequest);
                }

                _state.FinishTransfer(transfer.Id, path, null);
                _state.NotifyUser($"已接收 {name}");
                return Results.Json(new { ok = true, path }, Json.Options);
            }
            catch (BadHttpRequestException ex)
            {
                // 真正的语义由异常携带（例如 413），不要一律伪装成 500。
                TryDelete(path);
                var status = ex.StatusCode is >= 400 and <= 599 ? ex.StatusCode : StatusCodes.Status500InternalServerError;
                Log.Write($"upload rejected ({status}): {ex.Message}");
                _state.FinishTransfer(transfer.Id, null,
                    status == StatusCodes.Status413PayloadTooLarge ? "文件超过服务器允许的大小" : ex.Message);
                return Results.Json(new { ok = false, error = ex.Message }, Json.Options, statusCode: status);
            }
            catch (OperationCanceledException)
            {
                TryDelete(path);
                _state.FinishTransfer(transfer.Id, null, "客户端已取消");
                return Results.Json(new { ok = false, error = "客户端已取消" }, Json.Options,
                    statusCode: StatusCodes.Status400BadRequest);
            }
            catch (Exception ex)
            {
                Log.Write("upload: " + ex);
                TryDelete(path);
                _state.FinishTransfer(transfer.Id, null, ex.Message);
                return Results.Json(new { ok = false, error = ex.Message }, Json.Options,
                    statusCode: StatusCodes.Status500InternalServerError);
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

    private static void TryDelete(string path)
    {
        try { if (File.Exists(FileUtil.Extended(path))) File.Delete(FileUtil.Extended(path)); } catch { }
    }

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
