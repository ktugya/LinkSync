using System.Net;
using System.Net.Http.Json;
using System.Net.Http.Headers;
using System.Text.Json;

namespace LinkSync;

public sealed record PeerInfo(string Id, string Name, string Platform, int Port);
public sealed record UploadResult(bool Ok, string Path, string Error);

public sealed class PeerClient
{
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            ConnectTimeout = TimeSpan.FromSeconds(3),
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
            AutomaticDecompression = DecompressionMethods.None
        };
        return new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    private static string Host(Peer peer) => peer.Ip.Contains(':') ? $"[{peer.Ip}]" : peer.Ip;

    public async Task<bool> PushClipboardAsync(Peer peer, string text, string origin, string originName)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            var url = $"http://{Host(peer)}:{peer.Port}/api/clipboard";
            var dto = new { text, hash = Hash(text), origin, originName, ts = Now() };
            using var resp = await Http.PostAsJsonAsync(url, dto, Json.Options, cts.Token);
            return resp.IsSuccessStatusCode;
        }
        catch (Exception ex)
        {
            Log.Write($"push clipboard {peer.Name}: {ex.Message}");
            return false;
        }
    }

    public async Task<bool> PushTextAsync(Peer peer, MessageEntry msg)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            var url = $"http://{Host(peer)}:{peer.Port}/api/text";
            var dto = new { id = msg.Id, from = msg.From, fromName = msg.FromName, text = msg.Text, ts = msg.Ts };
            using var resp = await Http.PostAsJsonAsync(url, dto, Json.Options, cts.Token);
            return resp.IsSuccessStatusCode;
        }
        catch (Exception ex)
        {
            Log.Write($"push text {peer.Name}: {ex.Message}");
            return false;
        }
    }

    public async Task<UploadResult> PushFileAsync(Peer peer, string name, long size,
        string fromId, string fromName, Stream body, Action<long> progress, CancellationToken ct)
    {
        try
        {
            var url = $"http://{Host(peer)}:{peer.Port}/api/upload";
            using var req = new HttpRequestMessage(HttpMethod.Post, url);
            req.Headers.TryAddWithoutValidation("X-File-Name", Uri.EscapeDataString(name));
            req.Headers.TryAddWithoutValidation("X-File-Size", size.ToString());
            req.Headers.TryAddWithoutValidation("X-From-Id", fromId);
            req.Headers.TryAddWithoutValidation("X-From-Name", Uri.EscapeDataString(fromName));

            var content = new StreamContent(new ProgressStream(body, progress));
            content.Headers.ContentLength = size;
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            req.Content = content;

            using var resp = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
            var text = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode)
                return new UploadResult(false, "", $"HTTP {(int)resp.StatusCode}: {text}");
            try
            {
                using var doc = JsonDocument.Parse(text);
                var path = doc.RootElement.TryGetProperty("path", out var p) ? p.GetString() ?? "" : "";
                return new UploadResult(true, path, "");
            }
            catch
            {
                return new UploadResult(true, "", "");
            }
        }
        catch (Exception ex)
        {
            return new UploadResult(false, "", ex.Message);
        }
    }

    public async Task<PeerInfo?> FetchInfoAsync(string ip, int port)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            var url = $"http://{(ip.Contains(':') ? "[" + ip + "]" : ip)}:{port}/api/info";
            return await Http.GetFromJsonAsync<PeerInfo>(url, Json.Options, cts.Token);
        }
        catch (Exception ex)
        {
            Log.Write($"fetch info {ip}:{port}: {ex.Message}");
            return null;
        }
    }

    public static string Hash(string text)
    {
        var bytes = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}
