using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace LinkSync;

public sealed class MainForm : Form
{
    private readonly string _url;
    private readonly WebView2 _web = new();
    private bool _allowClose;
    private bool _initialized;

    public MainForm(string url)
    {
        _url = url;
        Text = "LinkSync";
        Icon = AppIcon.Value;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(880, 600);
        ClientSize = new Size(1080, 680);
        BackColor = Color.FromArgb(14, 16, 20);
        ShowInTaskbar = true;

        _web.Dock = DockStyle.Fill;
        _web.DefaultBackgroundColor = Color.FromArgb(14, 16, 20);
        Controls.Add(_web);

        Load += async (_, _) => await InitializeAsync();
    }

    private async Task InitializeAsync()
    {
        if (_initialized) return;
        _initialized = true;
        try
        {
            var dataDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "LinkSync", "WebView2");
            Directory.CreateDirectory(dataDir);
            var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
            await _web.EnsureCoreWebView2Async(env);

            if (_web.CoreWebView2 != null)
            {
                var s = _web.CoreWebView2.Settings;
                s.AreDevToolsEnabled = false;
                s.AreDefaultContextMenusEnabled = false;
                s.IsStatusBarEnabled = false;
                s.IsZoomControlEnabled = false;
                _web.CoreWebView2.Navigate(_url);
            }
        }
        catch (Exception ex)
        {
            Log.Write("webview2: " + ex);
            MessageBox.Show(
                "无法启动内嵌界面（可能未安装 Microsoft Edge WebView2 Runtime）。\n" +
                "将改用系统浏览器打开。\n\n" + ex.Message,
                "LinkSync", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            try
            {
                Process.Start(new ProcessStartInfo(_url) { UseShellExecute = true });
            }
            catch { }
            Hide();
        }
    }

    public void ShowFromTray()
    {
        if (IsDisposed) return;
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
    }

    public void ForceClose()
    {
        _allowClose = true;
        Close();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_allowClose && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnFormClosing(e);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try
        {
            var dark = 1;
            if (DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int)) != 0)
                DwmSetWindowAttribute(Handle, 19, ref dark, sizeof(int));
        }
        catch { }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try { _web.Dispose(); } catch { }
        }
        base.Dispose(disposing);
    }
}
