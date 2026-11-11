# MandoPC

Usa tu teléfono Android como mando de juegos. Tiene dos formas de conectarse:

| | Bluetooth directo | WiFi |
|---|---|---|
| Qué hace falta en el otro equipo | Nada | El programa de la carpeta `pc/` |
| Cómo lo ve el otro equipo | Mando Bluetooth genérico | Mando de Xbox 360 |
| Latencia | Mayor (la del Bluetooth) | La menor: 1 a 3 ms medidos |
| Sirve en | PC, tablets, televisores | PC con Windows |

- `MandoPC.apk`: la app (Android 8.0 o superior; el modo Bluetooth directo pide Android 9).
- `pc/`: el programa para Windows, solo necesario para el modo WiFi.
- `android/`: el código fuente de la app (se abre con Android Studio).

## Conectar por Bluetooth directo

1. En la app toca el botón de menú y entra en **Bluetooth directo**. Acepta el permiso de Bluetooth.
2. Toca **Hacer visible**.
3. En la PC abre Configuración, Bluetooth y dispositivos, Agregar dispositivo, y elige tu teléfono.
4. Acepta el código en los dos. La PC se conecta sola y el primer LED de la app queda fijo.

Si el teléfono ya estaba emparejado con la PC de antes, quítalo de la lista de Bluetooth en ambos y emparéjalo de
nuevo con la app abierta en este modo: la PC solo pregunta qué tipo de dispositivo es en el momento de emparejar.

La próxima vez basta con abrir la app: reconecta con el último equipo. Para cambiar de equipo, tócalo en la lista
**Equipos emparejados**.

## Conectar por WiFi

1. En la PC abre **MandoPC** (`pc/MandoPC.exe`) y déjalo abierto. La primera vez, permite el acceso en el firewall.
2. En la app toca el botón de menú, entra en **WiFi** y toca **Buscar la PC**.
   Si no la encuentra, escribe la dirección que muestra la ventana del programa.

La ventana de MandoPC dibuja el mando que Windows está recibiendo: lo que pulses en el teléfono se ve ahí al momento.
Si la conexión se corta (WiFi inestable, programa reiniciado), la app vuelve a intentarlo sola cada 3 segundos.

Requisitos de la PC: Windows 10/11 de 64 bits, .NET 10 Desktop Runtime y el driver **ViGEmBus**.
`MandoPC.exe` necesita tener `ViGEmClient.dll` en su misma carpeta.

## Personalizar el mando

- **Distribución**: Xbox, PlayStation o Switch. Es solo apariencia: cambia cómo se llama cada botón (y, en PlayStation,
  dónde van los sticks y la cruceta). Cada posición envía siempre lo mismo: el botón de abajo envía A aunque en
  Switch se llame B, así que los juegos no notan la diferencia.
- **Editar diseño**: arrastra un control para moverlo; con − y + (o pellizcando) cambias su tamaño.
- **Teclas turbo**: una tecla extra que pulsa un botón varias veces con un solo toque, o sin parar mientras la mantengas.
- **Combinaciones**: toca **Grabar una combinación**, haz la secuencia en el mando (botones, varios a la vez, sticks)
  y toca **Guardar**. Queda en una tecla nueva (C1, C2…) que la repite con los mismos tiempos.
- **Restablecer diseño**: vuelve a la distribución de fábrica y quita las teclas turbo y las combinaciones.

## Comprobar que funciona

En Windows, `control joy.cpl` abre el panel de mandos: ahí aparece el mando y se ven los botones al pulsarlos.
En modo WiFi basta con mirar la ventana de MandoPC.

Para medir el modo WiFi, con MandoPC abierto y el teléfono desconectado (necesita Python):

```
python pc\prueba.py
```

## Recompilar la app

Abre `android` en Android Studio y pulsa Run, o desde una terminal con `ANDROID_HOME` y un JDK 17 o superior:

```
android\gradlew.bat -p android testDebugUnitTest assembleDebug
```

## Recompilar el programa de PC

El código está en `pc/src` (C# con WPF). Con el SDK de .NET 10:

```
dotnet publish pc\src -c Release -o pc
```

Si el proyecto está dentro de OneDrive conviene copiar `android` o `pc\src` fuera antes de compilar: las
compilaciones generan muchos archivos temporales.

## Antes de publicarla

Pendiente, a propósito, hasta que decidas publicarla:

- **Firma de publicación**: el APK actual va firmado con la clave de depuración de esta PC. Play Store exige una clave propia.
- **Identificador**: `com.mandopc` no se puede cambiar después de publicar; elige el definitivo antes.
- **Código de emparejamiento en WiFi**: hoy cualquier dispositivo de la misma red puede manejar el mando virtual si no hay un teléfono conectado.
- **Programa de PC instalable**: `MandoPC.exe` necesita .NET 10 y el driver ViGEmBus ya instalados, y no está firmado
  (Windows mostrará un aviso al descargarlo). Para el público convendría un instalador firmado que lo incluya todo.
- **Idiomas**: los textos están en `res/values/strings.xml`, solo en español.
- **Marcas**: Xbox, PlayStation y Switch son marcas de sus dueños; aquí solo nombran distribuciones de botones.

## Licencias de terceros

- Tipografía **Nunito** (SIL Open Font License 1.1): `android/app/src/main/assets/Nunito-OFL.txt` y `pc/Nunito-OFL.txt`.
- `pc/ViGEmClient.dll` (licencia MIT), tomada del paquete `vgamepad` 0.2.0 de PyPI.
