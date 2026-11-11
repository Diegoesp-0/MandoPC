using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using Path = System.Windows.Shapes.Path;

namespace MandoPC;

/// <summary>
/// Ventana única: dibuja el mando de Xbox que Windows está recibiendo, con las mismas teclas en relieve de la app,
/// y debajo el estado de la conexión y la dirección de esta PC.
/// </summary>
public partial class MainWindow : Window
{
    // La paleta de la app (Ui.kt): tinta para la carcasa, hueso para las leyendas, color solo en A, B, X, Y.
    static readonly Brush Shell = Hex("#131A2C"), Cap = Hex("#2B3757"), CapHi = Hex("#3A4870"), Side = Hex("#090D18"),
        Well = Hex("#0D1322"), Edge = Hex("#42507A"), Bone = Hex("#EEF1F7"), Muted = Hex("#96A1BD"), Red = Hex("#F45D62");

    const double W = 1000, H = 450;   // lienzo del mando, con la proporción de la pantalla de un teléfono

    readonly Server server = new();
    readonly List<Action<Report>> parts = [];   // cada control sabe cómo pintarse a partir del estado
    readonly Ellipse[] leds = new Ellipse[4];
    Report shown;
    int tick = -1;

    static SolidColorBrush Hex(string color)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color));
        brush.Freeze();
        return brush;
    }

    public MainWindow()
    {
        InitializeComponent();
        BuildPad();
        for (int i = 0; i < 4; i++)
            Leds.Children.Add(leds[i] = new Ellipse { Width = 10, Height = 10, Margin = new Thickness(0, 0, 9, 0), Fill = Edge });
        server.Start();
        Refresh();
        new DispatcherTimer(TimeSpan.FromMilliseconds(16), DispatcherPriority.Render, (_, _) => Refresh(), Dispatcher).Start();
    }

    [DllImport("dwmapi.dll")]
    static extern int DwmSetWindowAttribute(IntPtr window, int attribute, ref int value, int size);

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Barra de título del color de la carcasa (Windows 11; en Windows 10 se queda la del sistema). Colores en 0x00BBGGRR.
        var window = new WindowInteropHelper(this).Handle;
        int dark = 1, caption = 0x002C1A13, text = 0x00F7F1EE;
        DwmSetWindowAttribute(window, 20, ref dark, 4);      // DWMWA_USE_IMMERSIVE_DARK_MODE
        DwmSetWindowAttribute(window, 35, ref caption, 4);   // DWMWA_CAPTION_COLOR
        DwmSetWindowAttribute(window, 36, ref text, 4);      // DWMWA_TEXT_COLOR
    }

    // ---------- estado ----------

    void Refresh()
    {
        tick++;
        var report = server.Latest;
        if (tick == 0 || report != shown)
        {
            shown = report;
            foreach (var paint in parts) paint(report);
        }
        string? phone = server.Phone, error = server.Error;
        // LED como los de un mando: recorren mientras espera, uno fijo al conectar, rojo si algo falló
        int lit = error != null || phone != null ? 1 : SystemParameters.ClientAreaAnimation ? 1 << (tick / 11 % 4) : 15;
        for (int i = 0; i < 4; i++) leds[i].Fill = (lit >> i & 1) == 0 ? Edge : error != null ? Red : Bone;
        Status.Text = error ?? (phone != null ? "Teléfono conectado" : "Esperando al teléfono");
        Hint.Text = error != null ? ""
            : phone != null ? $"Recibiendo desde {phone}. Windows lo ve como un mando de Xbox 360."
            : "En la app del teléfono abre el menú, entra en WiFi y toca Buscar la PC.";
        if (tick % 120 == 0) Address.Text = Addresses();   // la red puede cambiar con el programa abierto
    }

    /// <summary>Direcciones de red local de esta PC, una por línea: lo que se escribe en la app si no la encuentra sola.</summary>
    static string Addresses()
    {
        var found = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address.GetAddressBytes())
            .Where(b => b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168))
            .Select(b => string.Join('.', b)).Distinct().ToList();
        return found.Count == 0 ? "Sin red" : string.Join("\n", found);
    }

    // ---------- el mando ----------

    /// <summary>La distribución Xbox de la app (DEFAULT_LAYOUT en Layout.kt): centro y radio en fracciones del lienzo.</summary>
    void BuildPad()
    {
        Stick(0.16, 0.46, 0.19, r => (r.LX, r.LY), 0x40);
        Dpad(0.31, 0.78, 0.15);
        Stick(0.67, 0.76, 0.16, r => (r.RX, r.RY), 0x80);
        Key("A", 0.86, 0.63, 0.075, r => (r.Buttons & 0x1000) != 0, Hex("#52D273"));
        Key("B", 0.93, 0.47, 0.075, r => (r.Buttons & 0x2000) != 0, Red);
        Key("X", 0.79, 0.47, 0.075, r => (r.Buttons & 0x4000) != 0, Hex("#5AA8FF"));
        Key("Y", 0.86, 0.31, 0.075, r => (r.Buttons & 0x8000) != 0, Hex("#F7CA4A"));
        Key("LT", 0.075, 0.115, 0.075, r => r.LT != 0, pill: true);
        Key("LB", 0.205, 0.115, 0.075, r => (r.Buttons & 0x100) != 0, pill: true);
        Key("RB", 0.795, 0.115, 0.075, r => (r.Buttons & 0x200) != 0, pill: true);
        Key("RT", 0.925, 0.115, 0.075, r => r.RT != 0, pill: true);
        Key("Back", 0.42, 0.12, 0.055, r => (r.Buttons & 0x20) != 0, pill: true);
        Key("", 0.50, 0.12, 0.055, r => (r.Buttons & 0x400) != 0);   // Home: un aro, como el botón guía
        Key("Start", 0.58, 0.12, 0.055, r => (r.Buttons & 0x10) != 0, pill: true);
    }

    T Add<T>(T shape, double left, double top) where T : UIElement
    {
        Canvas.SetLeft(shape, left);
        Canvas.SetTop(shape, top);
        Pad.Children.Add(shape);
        return shape;
    }

    /// <summary>Tecla en relieve: una pared oscura y, encima, la cara, que baja y se llena de color al pulsarla.</summary>
    void Key(string text, double x, double y, double r, Func<Report, bool> down, Brush? tint = null, bool pill = false)
    {
        double cx = x * W, cy = y * H, radius = r * H, depth = Math.Max(4, radius * 0.13);
        double hw = pill ? radius * 1.3 : radius, hh = pill ? radius * 0.72 : radius;
        var color = tint ?? Bone;
        var sink = new TranslateTransform();
        Add(new Rectangle { Width = 2 * hw, Height = 2 * hh, RadiusX = hh, RadiusY = hh, Fill = Side }, cx - hw, cy - hh + depth);
        var face = Add(new Rectangle
        {
            Width = 2 * hw, Height = 2 * hh, RadiusX = hh, RadiusY = hh, Fill = Cap,
            Stroke = tint ?? Edge, StrokeThickness = Math.Max(2.5, radius * 0.055), RenderTransform = sink,
        }, cx - hw, cy - hh);
        Shape? ring = null;
        TextBlock? legend = null;
        if (text.Length == 0)
            ring = Add(new Ellipse { Width = radius * 0.8, Height = radius * 0.8, Stroke = color, StrokeThickness = radius * 0.14, RenderTransform = sink },
                cx - radius * 0.4, cy - radius * 0.4);
        else
        {
            double size = pill ? Math.Min(hh * 1.05, hw * 2.6 / text.Length) : radius * Math.Min(0.95, 2.3 / text.Length);
            legend = new TextBlock
            {
                Text = text, FontFamily = (FontFamily)FindResource("Nunito"), FontWeight = FontWeights.ExtraBold, FontSize = size, Foreground = color,
                Width = 2 * hw, TextAlignment = TextAlignment.Center, RenderTransform = sink,
            };
            legend.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            Add(legend, cx - hw, cy - legend.DesiredSize.Height / 2);
        }
        parts.Add(report =>
        {
            bool pressed = down(report);
            sink.Y = pressed ? depth : 0;
            face.Fill = pressed ? color : Cap;
            face.Stroke = pressed ? color : tint ?? Edge;
            if (legend != null) legend.Foreground = pressed ? Shell : color;
            if (ring != null) ring.Stroke = pressed ? Shell : color;
        });
    }

    /// <summary>Stick: un hueco y, dentro, la seta, que se desplaza con el eje y se ilumina si se pulsa (L3 o R3).</summary>
    void Stick(double x, double y, double r, Func<Report, (short X, short Y)> axis, int click)
    {
        double cx = x * W, cy = y * H, radius = r * H, depth = Math.Max(4, radius * 0.13), cap = radius * 0.56;
        double line = Math.Max(2.5, radius * 0.03);
        var move = new TranslateTransform();
        Add(new Ellipse { Width = 2 * radius, Height = 2 * radius, Fill = Well, Stroke = Edge, StrokeThickness = line }, cx - radius, cy - radius);
        Add(new Ellipse { Width = 2 * cap, Height = 2 * cap, Fill = Side, RenderTransform = move }, cx - cap, cy - cap + depth);
        var top = Add(new Ellipse { Width = 2 * cap, Height = 2 * cap, Fill = Cap, Stroke = Edge, StrokeThickness = line, RenderTransform = move },
            cx - cap, cy - cap);
        Add(new Ellipse   // anillo de agarre, como la goma de un stick
        {
            Width = cap * 1.16, Height = cap * 1.16, Stroke = new SolidColorBrush(Color.FromArgb(0x30, 255, 255, 255)),
            StrokeThickness = line, RenderTransform = move,
        }, cx - cap * 0.58, cy - cap * 0.58);
        parts.Add(report =>
        {
            var (ax, ay) = axis(report);
            bool clicked = (report.Buttons & click) != 0;
            move.X = ax / 32767.0 * radius * 0.42;
            move.Y = -ay / 32767.0 * radius * 0.42;   // XInput: Y positiva hacia arriba
            top.Fill = clicked ? Bone : ax != 0 || ay != 0 ? CapHi : Cap;
            top.Stroke = clicked || ax != 0 || ay != 0 ? Bone : Edge;
        });
    }

    void Dpad(double x, double y, double r)
    {
        double cx = x * W, cy = y * H, radius = r * H, depth = Math.Max(4, radius * 0.13), a = radius * 0.36, k = radius * 0.13;
        Geometry Cross(double dy) => new CombinedGeometry(GeometryCombineMode.Union,
            new RectangleGeometry(new Rect(cx - radius, cy + dy - a, 2 * radius, 2 * a), k, k),
            new RectangleGeometry(new Rect(cx - a, cy + dy - radius, 2 * a, 2 * radius), k, k));
        var sink = new TranslateTransform();
        Add(new Path { Data = Cross(depth), Fill = Side }, 0, 0);
        Add(new Path { Data = Cross(0), Fill = Cap, Stroke = Edge, StrokeThickness = Math.Max(2.5, radius * 0.03), RenderTransform = sink }, 0, 0);
        // flechas grabadas; la dirección pulsada se enciende (bits XInput: arriba 1, abajo 2, izquierda 4, derecha 8)
        var arrows = new (int Bit, double X, double Y, Polygon Shape)[] { (1, 0, -1, new()), (2, 0, 1, new()), (4, -1, 0, new()), (8, 1, 0, new()) };
        double t = radius * 0.13;
        foreach (var (_, ux, uy, shape) in arrows)
        {
            double ox = cx + ux * radius * 0.68, oy = cy + uy * radius * 0.68;
            shape.Points = [new(ox + ux * t, oy + uy * t), new(ox - ux * t * 0.6 - uy * t, oy - uy * t * 0.6 + ux * t), new(ox - ux * t * 0.6 + uy * t, oy - uy * t * 0.6 - ux * t)];
            shape.RenderTransform = sink;
            Add(shape, 0, 0);
        }
        parts.Add(report =>
        {
            int bits = report.Buttons & 0xF;
            sink.Y = bits != 0 ? depth * 0.6 : 0;
            foreach (var (bit, _, _, shape) in arrows) shape.Fill = (bits & bit) != 0 ? Bone : Muted;
        });
    }
}
