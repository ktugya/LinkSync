using System.Drawing;
using System.Reflection;

namespace LinkSync;

public static class AppIcon
{
    public static Icon Value { get; } = Load();

    private static Icon Load()
    {
        try
        {
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("LinkSync.app.ico");
            if (stream != null) return new Icon(stream);
        }
        catch (Exception ex) { Log.Write("icon: " + ex.Message); }
        return SystemIcons.Application;
    }
}
