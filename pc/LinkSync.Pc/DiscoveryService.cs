using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;

namespace LinkSync;

public sealed class DiscoveryService : IDisposable
{
    private readonly AppState _state;
    private readonly int _udpPort;
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _receiveTask;
    private Task? _announceTask;

    public DiscoveryService(AppState state, int udpPort = 47822)
    {
        _state = state;
        _udpPort = udpPort;
    }

    public void Start()
    {
        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, _udpPort));
        _udp.EnableBroadcast = true;
        _cts = new CancellationTokenSource();
        _receiveTask = Task.Run(() => ReceiveLoopAsync(_cts.Token));
        _announceTask = Task.Run(() => AnnounceLoopAsync(_cts.Token));
        Log.Write($"discovery started on udp/{_udpPort}");
    }

    private async Task AnnounceLoopAsync(CancellationToken ct)
    {
        var payload = JsonSerializer.SerializeToUtf8Bytes(new
        {
            id = _state.Settings.Id,
            name = _state.Settings.Name,
            platform = "windows",
            port = _state.Settings.Port,
            v = 1
        }, Json.Options);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                foreach (var ep in BroadcastTargets())
                {
                    try { await _udp!.SendAsync(payload, payload.Length, ep); }
                    catch (Exception ex) { Log.Write("udp send: " + ex.Message); }
                }
            }
            catch (Exception ex) { Log.Write("announce: " + ex.Message); }
            try { await Task.Delay(3000, ct); } catch { break; }
        }
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try { result = await _udp!.ReceiveAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                Log.Write("udp receive: " + ex.Message);
                await Task.Delay(1000, ct);
                continue;
            }

            try
            {
                using var doc = JsonDocument.Parse(result.Buffer);
                var root = doc.RootElement;
                var id = root.GetProperty("id").GetString();
                if (string.IsNullOrWhiteSpace(id) || id == _state.Settings.Id) continue;
                var name = root.TryGetProperty("name", out var n) ? n.GetString() : id;
                var platform = root.TryGetProperty("platform", out var p) ? p.GetString() : "android";
                var port = root.TryGetProperty("port", out var po) ? po.GetInt32() : 47821;
                _state.TouchPeer(id, name ?? id, platform ?? "android",
                    result.RemoteEndPoint.Address.ToString(), port);
            }
            catch (Exception ex)
            {
                Log.Write("udp parse: " + ex.Message);
            }
        }
    }

    private IEnumerable<IPEndPoint> BroadcastTargets()
    {
        var seen = new HashSet<string>();
        var list = new List<IPEndPoint>();
        void Add(IPAddress address)
        {
            var key = address.ToString();
            if (!seen.Add(key)) return;
            list.Add(new IPEndPoint(address, _udpPort));
        }

        Add(IPAddress.Broadcast);
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ua.Address)) continue;
                    var mask = ua.IPv4Mask;
                    if (mask == null) continue;
                    var b = ToBroadcast(ua.Address, mask);
                    if (b != null) Add(b);
                }
            }
        }
        catch (Exception ex) { Log.Write("interfaces: " + ex.Message); }
        return list;
    }

    private static IPAddress? ToBroadcast(IPAddress address, IPAddress mask)
    {
        var a = address.GetAddressBytes();
        var m = mask.GetAddressBytes();
        if (a.Length != 4 || m.Length != 4) return null;
        for (var i = 0; i < 4; i++) a[i] = (byte)(a[i] | (byte)~m[i]);
        return new IPAddress(a);
    }

    public void Dispose()
    {
        try { _cts?.Cancel(); } catch { }
        try { _udp?.Close(); } catch { }
        try { _udp?.Dispose(); } catch { }
        try { Task.WaitAll(new[] { _receiveTask, _announceTask }.Where(t => t != null).ToArray()!, 1500); } catch { }
        _cts?.Dispose();
    }
}
