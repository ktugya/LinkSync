using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace LinkSync;

public sealed class TrayApp : ApplicationContext
{
    private readonly AppState _state;
    private readonly ClipboardSync _clipboard;
    private readonly ApiServer _server;
    private readonly NotifyIcon _icon;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly Control _ui = new();
    private readonly ToolStripMenuItem _clipItem;
    private readonly ToolStripMenuItem _startItem;
    private MainForm? _form;
    private bool _disposed;

    public TrayApp(AppState state, ClipboardSync clipboard, ApiServer server)
    {
        _state = state;
        _clipboard = clipboard;
        _server = server;
        _ = _ui.Handle;

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("打开界面", null, (_, _) => OpenUi()));
        menu.Items.Add(new ToolStripMenuItem("发送剪贴板到所有设备", null, (_, _) => SendClipboard()));
        menu.Items.Add(new ToolStripSeparator());

        _clipItem = new ToolStripMenuItem("自动同步剪贴板") { CheckOnClick = true, Checked = state.Settings.AutoClipboard };
        _clipItem.Click += (_, _) =>
        {
            state.Settings.AutoClipboard = _clipItem.Checked;
            state.SaveSettings();
        };
        menu.Items.Add(_clipItem);

        _startItem = new ToolStripMenuItem("开机自动启动") { CheckOnClick = true, Checked = state.Settings.StartWithWindows };
        _startItem.Click += (_, _) =>
        {
            state.Settings.StartWithWindows = _startItem.Checked;
            state.SaveSettings();
            StartupManager.Set(_startItem.Checked);
        };
        menu.Items.Add(_startItem);

        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("退出", null, (_, _) => ExitApp()));

        _icon = new NotifyIcon
        {
            Icon = AppIcon.Value,
            Visible = true,
            Text = "LinkSync",
            ContextMenuStrip = menu
        };
        _icon.DoubleClick += (_, _) => OpenUi();

        _state.Notify += message => Post(() =>
        {
            try { _icon.ShowBalloonTip(2500, "LinkSync", message, ToolTipIcon.Info); } catch { }
        });

        _state.SettingsChanged += () => Post(() =>
        {
            _clipItem.Checked = _state.Settings.AutoClipboard;
            _startItem.Checked = _state.Settings.StartWithWindows;
            StartupManager.Set(_state.Settings.StartWithWindows);
        });

        _timer = new System.Windows.Forms.Timer { Interval = 500 };
        _timer.Tick += (_, _) => _clipboard.Poll();
        _timer.Start();

        StartupManager.Set(_state.Settings.StartWithWindows);

        if (_state.FirstRun)
        {
            var first = new System.Windows.Forms.Timer { Interval = 900 };
            first.Tick += (_, _) =>
            {
                first.Stop();
                first.Dispose();
                OpenUi();
            };
            first.Start();
        }

        if (Environment.GetCommandLineArgs().Contains("--open", StringComparer.OrdinalIgnoreCase))
            OpenUi();
    }

    public void PostOpen() => Post(OpenUi);

    private void Post(Action action)
    {
        try
        {
            if (_ui.IsHandleCreated) _ui.BeginInvoke(action);
        }
        catch { }
    }

    private void OpenUi()
    {
        try
        {
            if (_form == null || _form.IsDisposed)
                _form = new MainForm(_server.Url);
            _form.ShowFromTray();
        }
        catch (Exception ex)
        {
            Log.Write("open ui: " + ex.Message);
            try { Process.Start(new ProcessStartInfo(_server.Url) { UseShellExecute = true }); } catch { }
        }
    }

    private void SendClipboard()
    {
        var text = _clipboard.ReadLocal();
        if (string.IsNullOrEmpty(text))
        {
            _icon.ShowBalloonTip(2000, "LinkSync", "剪贴板里没有文字", ToolTipIcon.Info);
            return;
        }
        _state.AddClipboard(text, _state.Settings.Id, _state.Settings.Name);
        _ = _clipboard.BroadcastAsync(text);
        _icon.ShowBalloonTip(1500, "LinkSync", "已发送剪贴板", ToolTipIcon.Info);
    }

    private void ExitApp()
    {
        _timer.Stop();
        try { _form?.ForceClose(); } catch { }
        _icon.Visible = false;
        ExitThread();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_disposed)
        {
            _disposed = true;
            try { _timer.Dispose(); } catch { }
            try { _form?.ForceClose(); _form?.Dispose(); } catch { }
            try { _icon.Visible = false; _icon.Dispose(); } catch { }
            try { _ui.Dispose(); } catch { }
        }
        base.Dispose(disposing);
    }
}

public static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "LinkSync";

    public static void Set(bool enabled)
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            if (key == null) return;
            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (!string.IsNullOrEmpty(exe))
                    key.SetValue(ValueName, $"\"{exe}\" --tray");
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex) { Log.Write("startup: " + ex.Message); }
    }
}
