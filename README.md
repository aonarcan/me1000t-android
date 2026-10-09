# ME1000T Controller — Android app (v1)

A phone app that talks directly to the Magneti Marelli **ME1000T** tuning box over
USB-HID — no Windows, no server. This first version does the **confirmed-safe**
operations only:

- Connect to the box over USB (OTG), with permission prompt
- Live status poll (active map + raw status flags)
- Switch the active map: **Map 0 / Map 1 / Map 2**
- Read the maps' cell values (read-only)

**Writing maps and the LOCK command are deliberately NOT implemented** — that path
was never capture-verified and could risk the box.

---

## What you need

- **Android Studio** (any recent version). It will download the right Gradle on first sync.
- Your phone with **USB host / OTG** support (the Poco F8 Ultra has it).
- The **USB-C → USB-B** cable (phone = host, box = device).
- The ME1000T box. For full status it may need to be powered as in the car, but it
  enumerates and responds over USB-B alone.

## Build & install

1. Open Android Studio → **Open** → select the `me1000t-android` folder.
2. Let it **Gradle sync** (downloads Gradle 8.7 + the Android bits — needs internet once).
3. Easiest path: enable **USB debugging** on the phone, plug the phone into your
   computer, and press **Run ▶**. (You can tune over USB-B separately from the box.)
   - Or build an APK: **Build → Build Bundle(s)/APK(s) → Build APK(s)**, then copy
     the APK to the phone and install it (allow "install from unknown sources").

## Use it

1. Connect the box to the phone with the OTG cable.
2. Android may pop up "Open ME1000T Controller for this USB device?" → allow it;
   the app can launch automatically on plug-in.
3. Tap **Connect** if needed, approve the USB permission dialog.
4. The top line shows the connection + serial; **Active map** updates live.
5. Tap **Map 0 / 1 / 2** to switch; tap **Read map values** to dump the grid.

---

## How it works (so you can extend it)

The box speaks an **ASCII command protocol over HID interrupt transfers**:

| | |
|---|---|
| Commands | interrupt **OUT** endpoint, 32-byte report, ASCII + zero padding, no report-ID byte |
| Responses | interrupt **IN** endpoint, 32-byte report, ASCII |

Confirmed commands used here:
- `?03` → `03<map><flags>` (status; 3rd char = active map)
- `!030` / `!031` / `!032` → switch active map
- `?06<x1><x2>` → `06<x1><x2><VV>` (read one cell; `VV` = value byte)

All of it lives in **`Me1000tDevice.kt`** (protocol) and **`MainActivity.kt`** (UI +
USB setup). Full protocol notes are in `ME1000T_HID_protocol.md`.

## Troubleshooting

- **"Box not found"** — the phone isn't seeing the device. Check the OTG cable, re-plug,
  and confirm the phone does USB host (it should). Some phones need OTG enabled in settings.
- **"claimInterface() failed"** — the kernel HID driver grabbed the device. The app
  already calls `claimInterface(intf, true)` (force) which normally detaches it; if it
  still fails, re-plug and reopen.
- **No response / timeouts** — make sure the box is the one enumerating (VID 0x26D6 /
  PID 0x0E19) and, if status looks empty, try it powered as in the car.
- **Nothing on plug-in** — open the app manually and tap **Connect**.

## Safety

Read and map-switch are safe and reversible. Do **not** add write/commit/lock
commands until a real write has been captured and verified — a bad write to an
engine controller can do real damage, and the LOCK command (`!0F`) can brick the
box with the activation server gone.
