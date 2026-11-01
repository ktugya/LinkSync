using System.Text;

namespace LinkSync;

public static class Log
{
    private static readonly object Gate = new();
    private static readonly string PathFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LinkSync", "log.txt");

    public static void Write(string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(PathFile)!);
                if (File.Exists(PathFile) && new FileInfo(PathFile).Length > 2 * 1024 * 1024)
                    File.Delete(PathFile);
                File.AppendAllText(PathFile,
                    $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}", Encoding.UTF8);
            }
        }
        catch { }
    }
}

public static class FileUtil
{
    private static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    public static string Sanitize(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "file";
        var s = name.Replace('\\', '/');
        var idx = s.LastIndexOf('/');
        if (idx >= 0) s = s[(idx + 1)..];
        var invalid = Path.GetInvalidFileNameChars();
        var sb = new StringBuilder(s.Length);
        foreach (var ch in s)
            sb.Append(invalid.Contains(ch) || ch < 32 ? '_' : ch);
        s = sb.ToString().Trim().TrimEnd('.', ' ');
        if (s is "" or "." or "..") return "file";
        var stem = Path.GetFileNameWithoutExtension(s);
        var ext = Path.GetExtension(s);
        if (Reserved.Contains(stem)) s = "_" + s;
        if (s.Length > 180)
            s = s[..Math.Max(1, 180 - ext.Length)] + ext;
        return s;
    }

    public static string SanitizeFolder(string? name)
    {
        var s = Sanitize(name);
        return s == "file" ? "device" : s;
    }

    public static string Unique(string dir, string name)
    {
        var path = Path.Combine(dir, name);
        if (!File.Exists(path)) return path;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 1; i < 10000; i++)
        {
            path = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(path)) return path;
        }
        return Path.Combine(dir, $"{stem}-{DateTime.Now:HHmmss}{ext}");
    }
}

public sealed class ProgressStream : Stream
{
    private readonly Stream _inner;
    private readonly Action<long> _progress;
    private long _total;

    public ProgressStream(Stream inner, Action<long> progress)
    {
        _inner = inner;
        _progress = progress;
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => _total; set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override int Read(byte[] buffer, int offset, int count)
    {
        var n = _inner.Read(buffer, offset, count);
        if (n > 0) { _total += n; _progress(_total); }
        return n;
    }
    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        var n = await _inner.ReadAsync(buffer, cancellationToken);
        if (n > 0) { _total += n; _progress(_total); }
        return n;
    }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    protected override void Dispose(bool disposing)
    {
        if (disposing) _inner.Dispose();
        base.Dispose(disposing);
    }
}
