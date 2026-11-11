using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

namespace MandoPC;

/// <summary>Estado de un mando Xbox 360 (XUSB_REPORT). Son los 12 bytes que envía el teléfono tras el número de secuencia.</summary>
[StructLayout(LayoutKind.Sequential)]
public record struct Report
{
    public ushort Buttons;
    public byte LT, RT;
    public short LX, LY, RX, RY;
}

/// <summary>
/// Recibe por WiFi (UDP) el estado del mando que envía la app y lo vuelca en un mando Xbox 360 virtual (driver ViGEmBus).
/// </summary>
public sealed class Server
{
    const int Port = 27500;
    const int Size = 16;            // <uint seq> + Report
    const uint Ok = 0x20000000;     // VIGEM_ERROR_NONE

    [DllImport("ViGEmClient.dll")] static extern IntPtr vigem_alloc();
    [DllImport("ViGEmClient.dll")] static extern uint vigem_connect(IntPtr client);
    [DllImport("ViGEmClient.dll")] static extern IntPtr vigem_target_x360_alloc();
    [DllImport("ViGEmClient.dll")] static extern uint vigem_target_add(IntPtr client, IntPtr target);
    [DllImport("ViGEmClient.dll")] static extern uint vigem_target_x360_update(IntPtr client, IntPtr target, Report report);

    readonly object gate = new();
    Report report;

    /// <summary>Lo último que recibió el mando virtual.</summary>
    public Report Latest { get { lock (gate) return report; } }

    /// <summary>Dirección del teléfono conectado, o null si no hay ninguno.</summary>
    public volatile string? Phone;

    /// <summary>Por qué no se pudo arrancar, en palabras para el usuario; null si todo va bien.</summary>
    public volatile string? Error;

    public void Start()
    {
        try
        {
            IntPtr bus = vigem_alloc(), pad = vigem_target_x360_alloc();
            if (vigem_connect(bus) != Ok || vigem_target_add(bus, pad) != Ok)
            {
                Error = "Falta el driver ViGEmBus. Instálalo y vuelve a abrir MandoPC.";
                return;
            }
            var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.Bind(new IPEndPoint(IPAddress.Any, Port));
            new Thread(() => Loop(socket, bus, pad)) { IsBackground = true, Priority = ThreadPriority.Highest }.Start();
        }
        catch (DllNotFoundException) { Error = "Falta el archivo ViGEmClient.dll junto a MandoPC.exe."; }
        catch (SocketException) { Error = "MandoPC ya está abierto en esta PC."; }
    }

    void Loop(Socket socket, IntPtr bus, IntPtr pad)
    {
        void Apply(Report r)
        {
            lock (gate) report = r;
            vigem_target_x360_update(bus, pad, r);
        }

        var buffer = new byte[64];
        var hello = Encoding.UTF8.GetBytes("MPAD!" + Environment.MachineName);
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        EndPoint? client = null;
        uint lastSeq = 0;
        long seen = 0;
        while (true)
        {
            if (client != null && Environment.TickCount64 - seen > 500)   // sin latido: suelta todo para no dejar botones pegados
            {
                client = null;
                Phone = null;
                Apply(default);
            }
            try
            {
                if (!socket.Poll(250_000, SelectMode.SelectRead)) continue;
                int n = socket.ReceiveFrom(buffer, ref from);
                if (n == 5 && buffer.AsSpan(0, 5).SequenceEqual("MPAD?"u8)) socket.SendTo(hello, from);
                else if (n == Size && (client == null || client.Equals(from)))
                {
                    // shortcut: sin teléfono conectado, cualquiera en la red local puede manejar el mando; añadir un código de emparejamiento antes de publicar
                    if (client == null)
                    {
                        client = from;
                        lastSeq = 0;
                        Phone = ((IPEndPoint)from).Address.ToString();
                    }
                    seen = Environment.TickCount64;
                    uint seq = BitConverter.ToUInt32(buffer, 0);
                    if (seq > lastSeq)                                     // descarta paquetes UDP repetidos o fuera de orden
                    {
                        lastSeq = seq;
                        Apply(MemoryMarshal.Read<Report>(buffer.AsSpan(4, 12)));
                    }
                }
            }
            catch (SocketException) { }   // Windows avisa así de un ICMP ajeno, p. ej. si el teléfono cerró su puerto
        }
    }
}
