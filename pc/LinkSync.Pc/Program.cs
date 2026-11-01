using System.Diagnostics;
using System.Windows.Forms;

namespace LinkSync;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var activate = new EventWaitHandle(false, EventResetMode.AutoReset, "LinkSync.PC.Activate");
        using var mutex = new Mutex(true, "LinkSync.PC.SingleInstance", out var created);
        if (!created)
        {
            try { activate.Set(); } catch { OpenUi(); }
            return;
        }

        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        var state = new AppState();
        var clipboardService = new ClipboardService();
        var client = new PeerClient();
        var clipboard = new ClipboardSync(state, clipboardService, client);
        var text = new TextService(state, client);
        text.Start();

        var server = new ApiServer(state, clipboard, text);
        try
        {
            server.Start(state.Settings.Port);
        }
        catch (Exception ex)
        {
            Log.Write("server start: " + ex);
            MessageBox.Show(
                $"LinkSync 启动失败：端口 {state.Settings.Port} 可能被占用。\n{ex.Message}",
                "LinkSync", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        using var discovery = new DiscoveryService(state);
        try { discovery.Start(); }
        catch (Exception ex) { Log.Write("discovery: " + ex.Message); }

        using var presence = new PresenceService(state, client);
        presence.Start();

        using var tray = new TrayApp(state, clipboard, server);
        var activation = new Thread(() =>
        {
            while (true)
            {
                try { activate.WaitOne(); } catch { break; }
                tray.PostOpen();
            }
        })
        { IsBackground = true, Name = "LinkSync.Activate" };
        activation.Start();

        Application.Run(tray);

        text.Stop();
        try { server.DisposeAsync().AsTask().Wait(3000); } catch { }
    }

    private static void OpenUi()
    {
        try
        {
            Process.Start(new ProcessStartInfo("http://127.0.0.1:47821") { UseShellExecute = true });
        }
        catch { }
    }
}
