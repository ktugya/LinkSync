namespace LinkSync;

public sealed class PresenceService : IDisposable
{
    private readonly AppState _state;
    private readonly PeerClient _client;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    public PresenceService(AppState state, PeerClient client)
    {
        _state = state;
        _client = client;
    }

    public void Start()
    {
        _loop = Task.Run(LoopAsync);
    }

    private async Task LoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                foreach (var peer in _state.Peers())
                {
                    if (now - peer.LastSeen < 8000) continue;
                    var info = await _client.FetchInfoAsync(peer.Ip, peer.Port);
                    if (info != null)
                        _state.TouchPeer(info.Id, info.Name, info.Platform, peer.Ip, peer.Port);
                }
            }
            catch (Exception ex) { Log.Write("presence: " + ex.Message); }

            try { await Task.Delay(6000, _cts.Token); }
            catch (OperationCanceledException) { break; }
        }
    }

    public void Dispose()
    {
        try { _cts.Cancel(); } catch { }
        try { _loop?.Wait(1000); } catch { }
        _cts.Dispose();
    }
}
