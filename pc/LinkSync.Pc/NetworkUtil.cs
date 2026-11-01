using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace LinkSync;

public static class NetworkUtil
{
    private static readonly object Gate = new();
    private static List<string>? _cache;
    private static DateTime _cacheAt;

    public static List<string> LocalIPv4()
    {
        lock (Gate)
        {
            if (_cache != null && (DateTime.UtcNow - _cacheAt).TotalSeconds < 10) return _cache;
        }

        var primary = new List<string>();
        var secondary = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

                var props = ni.GetIPProperties();
                var hasGateway = props.GatewayAddresses.Any(g =>
                    g.Address.AddressFamily == AddressFamily.InterNetwork &&
                    !g.Address.Equals(IPAddress.Any));

                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(ua.Address)) continue;
                    var ip = ua.Address.ToString();
                    if (hasGateway) primary.Add(ip);
                    else secondary.Add(ip);
                }
            }
        }
        catch (Exception ex) { Log.Write("net: " + ex.Message); }

        var result = primary.Concat(secondary).Distinct().ToList();
        lock (Gate)
        {
            _cache = result;
            _cacheAt = DateTime.UtcNow;
        }
        return result;
    }
}
