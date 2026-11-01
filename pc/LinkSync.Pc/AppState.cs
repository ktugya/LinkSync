using System.Text.Json;

namespace LinkSync;

public sealed class AppState
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Peer> _peers = new();
    private readonly List<Transfer> _transfers = new();
    private readonly List<ClipEntry> _clipboard = new();
    private readonly List<MessageEntry> _messages = new();
    private readonly string _settingsPath;

    public Settings Settings { get; private set; }
    public bool FirstRun { get; private set; }
    public string LastAppliedText { get; private set; } = "";
    public long LastAppliedAt { get; private set; }

    public event Action<string>? Notify;

    public AppState()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LinkSync");
        Directory.CreateDirectory(dir);
        _settingsPath = Path.Combine(dir, "settings.json");
        FirstRun = !File.Exists(_settingsPath);
        Settings = LoadSettings();
        if (string.IsNullOrWhiteSpace(Settings.Id)) Settings.Id = Guid.NewGuid().ToString("N");
        if (string.IsNullOrWhiteSpace(Settings.Name)) Settings.Name = Environment.MachineName;
        if (string.IsNullOrWhiteSpace(Settings.DownloadDir))
            Settings.DownloadDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "LinkSync");
        if (Settings.Port <= 0) Settings.Port = 47821;
        if (FirstRun) SaveSettings();
    }

    private Settings LoadSettings()
    {
        try
        {
            if (File.Exists(_settingsPath))
            {
                var json = File.ReadAllText(_settingsPath);
                return JsonSerializer.Deserialize<Settings>(json, Json.Options) ?? new Settings();
            }
        }
        catch (Exception ex) { Log.Write("load settings: " + ex.Message); }
        return new Settings();
    }

    public void SaveSettings()
    {
        try
        {
            var json = JsonSerializer.Serialize(Settings, new JsonSerializerOptions(Json.Options) { WriteIndented = true });
            File.WriteAllText(_settingsPath, json);
        }
        catch (Exception ex) { Log.Write("save settings: " + ex.Message); }
    }

    public void NotifyUser(string message)
    {
        try { Notify?.Invoke(message); } catch { }
    }

    public void TouchPeer(string id, string name, string platform, string ip, int port)
    {
        if (string.IsNullOrWhiteSpace(id) || id == Settings.Id) return;
        lock (_gate)
        {
            if (!_peers.TryGetValue(id, out var peer))
            {
                peer = new Peer { Id = id };
                _peers[id] = peer;
            }
            peer.Name = string.IsNullOrWhiteSpace(name) ? id : name;
            peer.Platform = string.IsNullOrWhiteSpace(platform) ? "android" : platform;
            peer.Ip = ip;
            peer.Port = port;
            peer.LastSeen = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }
    }

    public Peer? GetPeer(string id)
    {
        lock (_gate) { return _peers.TryGetValue(id, out var p) ? p : null; }
    }

    public List<Peer> Peers()
    {
        lock (_gate) { return _peers.Values.ToList(); }
    }

    public List<Peer> OnlinePeers()
    {
        lock (_gate) { return _peers.Values.Where(p => p.Online).ToList(); }
    }

    public Transfer AddTransfer(string direction, Peer peer, string name, long size)
    {
        var t = new Transfer
        {
            Direction = direction,
            PeerId = peer.Id,
            PeerName = peer.Name,
            Name = name,
            Size = size
        };
        lock (_gate)
        {
            _transfers.Insert(0, t);
            if (_transfers.Count > 80) _transfers.RemoveRange(80, _transfers.Count - 80);
        }
        return t;
    }

    public void UpdateTransfer(string id, long bytes)
    {
        lock (_gate)
        {
            var t = _transfers.FirstOrDefault(x => x.Id == id);
            if (t != null) t.Bytes = bytes;
        }
    }

    public void FinishTransfer(string id, string? path, string? error)
    {
        lock (_gate)
        {
            var t = _transfers.FirstOrDefault(x => x.Id == id);
            if (t == null) return;
            t.Status = error == null ? "done" : "failed";
            t.Path = path ?? "";
            t.Error = error ?? "";
            if (error == null && t.Size > 0) t.Bytes = t.Size;
        }
    }

    public void ClearFinishedTransfers()
    {
        lock (_gate) { _transfers.RemoveAll(t => t.Status != "running"); }
    }

    public void AddClipboard(string text, string from, string fromName)
    {
        lock (_gate)
        {
            if (_clipboard.Count > 0 && _clipboard[0].Text == text) return;
            _clipboard.Insert(0, new ClipEntry { Text = text, From = from, FromName = fromName });
            if (_clipboard.Count > 50) _clipboard.RemoveRange(50, _clipboard.Count - 50);
        }
    }

    public bool IsRecentlyApplied(string text)
    {
        lock (_gate)
        {
            return text == LastAppliedText &&
                   DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - LastAppliedAt < 5000;
        }
    }

    public void MarkApplied(string text, string from, string fromName)
    {
        lock (_gate)
        {
            LastAppliedText = text;
            LastAppliedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }
        AddClipboard(text, from, fromName);
    }

    public void AddMessage(MessageEntry m)
    {
        lock (_gate)
        {
            if (_messages.Any(x => x.Id == m.Id)) return;
            _messages.Insert(0, m);
            if (_messages.Count > 50) _messages.RemoveRange(50, _messages.Count - 50);
        }
    }

    public bool HasMessage(string id)
    {
        lock (_gate) { return _messages.Any(x => x.Id == id); }
    }

    public void UpdateSettings(SettingsDto dto)
    {
        lock (_gate)
        {
            if (!string.IsNullOrWhiteSpace(dto.Name)) Settings.Name = dto.Name.Trim();
            if (dto.AutoClipboard.HasValue) Settings.AutoClipboard = dto.AutoClipboard.Value;
            if (dto.AutoReceive.HasValue) Settings.AutoReceive = dto.AutoReceive.Value;
            if (!string.IsNullOrWhiteSpace(dto.DownloadDir)) Settings.DownloadDir = dto.DownloadDir.Trim();
            if (dto.StartWithWindows.HasValue) Settings.StartWithWindows = dto.StartWithWindows.Value;
        }
        SaveSettings();
        SettingsChanged?.Invoke();
    }

    public event Action? SettingsChanged;

    public string SnapshotJson()
    {
        lock (_gate)
        {
            var peers = _peers.Values
                .OrderByDescending(p => p.Online)
                .ThenBy(p => p.Name)
                .Select(p => new
                {
                    id = p.Id,
                    name = p.Name,
                    platform = p.Platform,
                    ip = p.Ip,
                    port = p.Port,
                    online = p.Online,
                    lastSeen = p.LastSeen
                })
                .ToList();

            var transfers = _transfers.Take(50).Select(t => new
            {
                id = t.Id,
                peerId = t.PeerId,
                peerName = t.PeerName,
                direction = t.Direction,
                name = t.Name,
                size = t.Size,
                bytes = t.Bytes,
                status = t.Status,
                path = t.Path,
                error = t.Error,
                ts = t.Ts
            }).ToList();

            var clipboard = _clipboard.Take(50).Select(c => new
            {
                id = c.Id,
                text = c.Text,
                from = c.From,
                fromName = c.FromName,
                ts = c.Ts
            }).ToList();

            var messages = _messages.Take(50).Select(m => new
            {
                id = m.Id,
                text = m.Text,
                from = m.From,
                fromName = m.FromName,
                ts = m.Ts
            }).ToList();

            var payload = new
            {
                self = new
                {
                    id = Settings.Id,
                    name = Settings.Name,
                    platform = "windows",
                    port = Settings.Port,
                    ips = NetworkUtil.LocalIPv4()
                },
                peers,
                transfers,
                clipboard,
                messages,
                settings = new
                {
                    name = Settings.Name,
                    autoClipboard = Settings.AutoClipboard,
                    autoReceive = Settings.AutoReceive,
                    downloadDir = Settings.DownloadDir,
                    startWithWindows = Settings.StartWithWindows,
                    port = Settings.Port
                }
            };
            return JsonSerializer.Serialize(payload, Json.Options);
        }
    }
}
