using System.Collections.Concurrent;
using System.Windows.Forms;

namespace LinkSync;

public sealed class ClipboardService
{
    private readonly Control _ui = new();

    public ClipboardService()
    {
        _ = _ui.Handle;
    }

    public string? GetText()
    {
        try
        {
            if (_ui.InvokeRequired)
                return (string?)_ui.Invoke(new Func<string?>(Read));
            return Read();
        }
        catch (Exception ex)
        {
            Log.Write("clipboard get: " + ex.Message);
            return null;
        }
    }

    private static string? Read()
    {
        try { return Clipboard.ContainsText() ? Clipboard.GetText() : null; }
        catch (Exception ex) { Log.Write("clipboard read: " + ex.Message); return null; }
    }

    public void SetText(string text)
    {
        try
        {
            if (_ui.InvokeRequired)
                _ui.BeginInvoke(new Action(() => Write(text)));
            else
                Write(text);
        }
        catch (Exception ex) { Log.Write("clipboard set: " + ex.Message); }
    }

    private static void Write(string text)
    {
        try { Clipboard.SetText(text); }
        catch (Exception ex) { Log.Write("clipboard write: " + ex.Message); }
    }
}

public sealed class ClipboardSync
{
    private readonly AppState _state;
    private readonly ClipboardService _clipboard;
    private readonly PeerClient _client;
    private readonly object _gate = new();
    private string _lastText = "";

    public ClipboardSync(AppState state, ClipboardService clipboard, PeerClient client)
    {
        _state = state;
        _clipboard = clipboard;
        _client = client;
    }

    public void Poll()
    {
        var text = _clipboard.GetText();
        if (string.IsNullOrEmpty(text)) return;

        lock (_gate)
        {
            if (text == _lastText) return;
        }
        if (_state.IsRecentlyApplied(text))
        {
            lock (_gate) { _lastText = text; }
            return;
        }

        lock (_gate) { _lastText = text; }
        _state.AddClipboard(text, _state.Settings.Id, _state.Settings.Name);
        if (_state.Settings.AutoClipboard)
            _ = BroadcastAsync(text);
    }

    public async Task BroadcastAsync(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        var peers = _state.OnlinePeers();
        foreach (var peer in peers)
        {
            try { await _client.PushClipboardAsync(peer, text, _state.Settings.Id, _state.Settings.Name); }
            catch (Exception ex) { Log.Write("broadcast clipboard: " + ex.Message); }
        }
    }

    public void ApplyRemote(string text, string from, string fromName)
    {
        if (string.IsNullOrEmpty(text)) return;
        if (_state.IsRecentlyApplied(text)) return;
        _state.MarkApplied(text, from, fromName);
        lock (_gate) { _lastText = text; }
        _clipboard.SetText(text);
    }

    public string? ReadLocal() => _clipboard.GetText();

    public void CopyLocal(string text)
    {
        lock (_gate) { _lastText = text; }
        _clipboard.SetText(text);
    }
}

public sealed class TextService
{
    private sealed record Pending(string PeerId, MessageEntry Msg);

    private readonly AppState _state;
    private readonly PeerClient _client;
    private readonly ConcurrentQueue<Pending> _pending = new();
    private CancellationTokenSource? _cts;

    public TextService(AppState state, PeerClient client)
    {
        _state = state;
        _client = client;
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => RetryLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        _cts?.Dispose();
    }

    public async Task SendAsync(IEnumerable<string> to, string text)
    {
        var msg = new MessageEntry
        {
            Text = text,
            From = _state.Settings.Id,
            FromName = _state.Settings.Name
        };
        _state.AddMessage(msg);

        foreach (var id in to.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct())
        {
            var peer = _state.GetPeer(id);
            if (peer == null) continue;
            var ok = false;
            if (peer.Online)
            {
                try { ok = await _client.PushTextAsync(peer, msg); }
                catch (Exception ex) { Log.Write("send text: " + ex.Message); }
            }
            if (!ok) _pending.Enqueue(new Pending(id, msg));
        }
    }

    private async Task RetryLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var batch = new List<Pending>();
                while (_pending.TryDequeue(out var item)) batch.Add(item);
                foreach (var item in batch)
                {
                    var peer = _state.GetPeer(item.PeerId);
                    var ok = false;
                    if (peer != null && peer.Online)
                    {
                        try { ok = await _client.PushTextAsync(peer, item.Msg); }
                        catch { }
                    }
                    if (!ok) _pending.Enqueue(item);
                }
            }
            catch (Exception ex) { Log.Write("text retry: " + ex.Message); }

            try { await Task.Delay(5000, ct); } catch { break; }
        }
    }
}
