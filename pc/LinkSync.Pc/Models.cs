using System.Text.Json;
using System.Text.Json.Serialization;

namespace LinkSync;

public sealed class Settings
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = Environment.MachineName;
    public bool AutoClipboard { get; set; } = true;
    public bool AutoReceive { get; set; } = true;
    public string DownloadDir { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LinkSync");
    public bool StartWithWindows { get; set; }
    public int Port { get; set; } = 47821;
}

public sealed class Peer
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Platform { get; set; } = "windows";
    public string Ip { get; set; } = "";
    public int Port { get; set; }
    public long LastSeen { get; set; }

    public bool Online => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - LastSeen < 15000;
}

public sealed class Transfer
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string PeerId { get; set; } = "";
    public string PeerName { get; set; } = "";
    public string Direction { get; set; } = "out";
    public string Name { get; set; } = "";
    public long Size { get; set; }
    public long Bytes { get; set; }
    public string Status { get; set; } = "running";
    public string Path { get; set; } = "";
    public string Error { get; set; } = "";
    public long Ts { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

public sealed class ClipEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Text { get; set; } = "";
    public string From { get; set; } = "";
    public string FromName { get; set; } = "";
    public long Ts { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

public sealed class MessageEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Text { get; set; } = "";
    public string From { get; set; } = "";
    public string FromName { get; set; } = "";
    public long Ts { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

public sealed record ClipboardDto(string? Text, string? Hash, string? Origin, string? OriginName, long Ts);
public sealed record TextDto(string? Id, string? From, string? FromName, string? Text, long Ts);
public sealed record TextSendDto(string[]? To, string? Text);
public sealed record SettingsDto(string? Name, bool? AutoClipboard, bool? AutoReceive, string? DownloadDir, bool? StartWithWindows);
public sealed record AddPeerDto(string? Addr);
public sealed record CopyDto(string? Text);

public static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = false,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };
}
