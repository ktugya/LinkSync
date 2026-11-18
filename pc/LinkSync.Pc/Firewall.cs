using System.Collections;

namespace LinkSync;

/// <summary>
/// 只读检查：当前网络配置下，Windows 防火墙是否放行了本程序的入站连接。
/// 不需要管理员权限；规则缺失时返回给用户的提示文案。
/// </summary>
public static class Firewall
{
    private const int Inbound = 1;
    private const int Allow = 1;

    public static string? Check(int port)
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return null;

            var policyType = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if (policyType == null)
            {
                Log.Write("firewall: 防火墙 COM 不可用，跳过检测");
                return null;
            }

            dynamic policy = Activator.CreateInstance(policyType)!;
            var profile = (int)policy.CurrentProfileTypes;
            var hasRule = false;

            foreach (var item in (IEnumerable)policy.Rules)
            {
                try
                {
                    dynamic rule = item;
                    if (!(bool)rule.Enabled) continue;
                    if ((int)rule.Direction != Inbound) continue;
                    if ((int)rule.Action != Allow) continue;

                    var app = rule.ApplicationName as string;
                    if (string.IsNullOrEmpty(app) ||
                        !string.Equals(app, exe, StringComparison.OrdinalIgnoreCase)) continue;

                    // 必须是"覆盖当前所有网络类别"的规则：
                    // 只放行公用网络的规则，在专用网络下是不生效的（反之亦然）。
                    if (((int)rule.Profiles & profile) != profile) continue;

                    hasRule = true;
                    break;
                }
                catch
                {
                    // 端口规则等未必有 ApplicationName，读属性可能抛异常，跳过即可
                }
            }

            if (hasRule) return null;

            Log.Write($"firewall: 当前网络类别(0x{profile:x})未被完整放行，端口 {port}");
            return "当前网络未被 Windows 防火墙放行，手机可能连不上（" + port +
                   "/TCP）。请到「Windows 安全中心 → 防火墙和网络保护 → 允许应用通过防火墙」，" +
                   "勾选 LinkSync 的专用/公用网络。";
        }
        catch (Exception ex)
        {
            Log.Write("firewall check: " + ex.Message);
            return null;
        }
    }
}
