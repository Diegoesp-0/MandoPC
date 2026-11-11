using System.Diagnostics;
using System.Windows;

namespace MandoPC;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // prioridad alta: que un juego usando todo el CPU no retrase la entrada del mando
        Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.High;
        base.OnStartup(e);
    }
}
