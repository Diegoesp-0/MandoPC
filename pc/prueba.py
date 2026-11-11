"""Prueba del programa de PC. Con MandoPC.exe abierto y SIN teléfono conectado: envía paquetes por UDP,
comprueba con XInput que el mando virtual los refleja y mide la latencia dentro de la PC."""
import ctypes, socket, statistics, struct, time

ADDR = ("127.0.0.1", 27500)

class XINPUT_STATE(ctypes.Structure):
    _fields_ = [("packet", ctypes.c_uint), ("buttons", ctypes.c_ushort), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]

xinput = ctypes.windll.xinput1_4
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(1)

def send_and_wait(packet: bytes, expect: tuple) -> float:
    """Envía el paquete y espera a que algún mando XInput muestre `expect`. Devuelve los ms que tardó."""
    st = XINPUT_STATE()
    t0 = time.perf_counter()
    s.sendto(packet, ADDR)
    while time.perf_counter() - t0 < 1:
        for i in range(4):
            if xinput.XInputGetState(i, ctypes.byref(st)) == 0 and \
                    (st.buttons, st.lt, st.rt, st.lx, st.ly, st.rx, st.ry) == expect:
                return (time.perf_counter() - t0) * 1000
    raise SystemExit(f"FALLO: el mando virtual no reflejó {expect}")

s.sendto(b"MPAD?", ADDR)
try:
    assert s.recvfrom(64)[0].startswith(b"MPAD!")
except (OSError, AssertionError):
    raise SystemExit("FALLO: MandoPC no responde. Ábrelo primero.")
time.sleep(0.3)  # a que el mando recién creado aparezca en XInput

# mismo vector que LayoutTest.kt en la app: A + LT, stick izq. (32767, -32767), stick der. Y = 1
golden = bytes.fromhex("010000000010ff00ff7f018000000100")
send_and_wait(golden, (0x1000, 255, 0, 32767, -32767, 0, 1))

times = []
for seq in range(2, 402):
    buttons = 0x1000 if seq % 2 else 0
    state = (buttons, 0, 0, 0, 0, 0, 0)
    times.append(send_and_wait(struct.pack("<IHBB4h", seq, *state), state))
    time.sleep(0.002)
print(f"OK · formato correcto · latencia PC (UDP -> mando XInput): "
      f"mediana {statistics.median(times):.2f} ms, máxima {max(times):.2f} ms")
