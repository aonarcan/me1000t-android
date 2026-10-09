# Magneti Marelli ME1000T — USB-HID Protocol Spec

Reverse-engineered from `MM1919_WEB.exe` (Magneti Marelli Elaborazioni 1.3.1)
and **confirmed against a live USB capture** (see §8 — the authoritative part for
the read/switch path). Purpose: enough detail to talk to the box from a custom
app (e.g. Android) with no Windows and no server.

**Read + map-switch path: fully verified. Write path: NOT yet captured — treat as unconfirmed.**

---

## 1. Device identity

| Field | Value |
|---|---|
| VendorID  | `0x26D6` (9942 dec) |
| ProductID | `0x0E19` (3609 dec) |
| Class | USB-HID, vendor-defined (no COM port) |
| Serial (example) | `ME1000T11160163APR18` (in the USB string / InstanceId) |

The app finds the device by enumerating HID devices and matching VID+PID.
Report sizes are **read at runtime** from the HID capabilities
(`InputReportByteLength` / `OutputReportByteLength`) — do the same; don't hardcode.

---

## 2. Transport framing

The app wraps GenericHid (standard Jan Axelson HID sample). On the wire:

**Output report (host → box):** a byte buffer of length `OutputReportByteLength`:
```
[0]           = 0x00            ; report ID
[1 .. n]      = ASCII bytes of the command string (one byte per char)
[n+1]         = 0x00            ; null terminator
[n+2 .. end]  = 0x00            ; zero padding
```
Written as a single HID output report. **Command string max length = 32 chars.**

**Input report (box → host):** a buffer of length `InputReportByteLength`.
Byte [0] is the report ID; the remainder is the ASCII response. The app reads it
asynchronously and parses it (see §5).

Everything is ASCII text. Numeric values in both directions are ASCII-hex
(e.g. the two characters `"A","F"` mean the byte 0xAF), decoded nibble-by-nibble.

---

## 3. Command syntax

```
<prefix><opcode><data...>
```
- Prefix `!` = write / set / action
- Prefix `?` = read / query
- `opcode` = 2 hex chars (a subsystem id)
- `data` = hex, format depends on command

---

## 4. Command dictionary (confirmed from code)

### Status & maps
| Command | Meaning |
|---|---|
| `?03` | Read status → sets `ready`, `valBlocco` (lock state), `strCurrentMappa` (active map), `strCurrentSettings` |
| `!030` | Select **Map 0** (original, read-only) |
| `!031` | Select **Map 1** |
| `!032` | Select **Map 2** |

### Read the tuning data
| Command | Meaning |
|---|---|
| `?04` | Read **map parameters**: Gain/Sensibilità, Medi/Coppia, Alti/Inizio, Bassi/Fine, Totale, Attesa, Limite |
| `?05` | Read **settings**: Telecomando, FiloBluGas, Pulsante, IndicatoreLED, CodiceTelecomando, IdVersioneAuto |

### Toggles (off = …0, on = …1)
| Off | On | Meaning |
|---|---|---|
| `!070` | `!071` | Telecomando (remote) |
| `!090` | `!091` | Pulsante (button) |
| `!0A0` | `!0A1` | Indicatore LED |
| `!0B0` | `!0B1` | Filo Blu Gas |

### Map-cell access (graph/curve editing)
| Command | Meaning |
|---|---|
| `?06<X1><X2>` | Read map cell. `X1`,`X2` = Int16 coords, `String.Format("{0:X}{1:X}")` |
| `!06<X1><X2><VV>` | Write map cell. `VV` = value byte, `{0:X2}`. Full format: `"!06" + X1:X + X2:X + val:X2` |

### Write / save sequences (authenticated — handle with care)
| Command | Meaning |
|---|---|
| `!04<nnnn>` | Begin map write; payload formatted `{0:0000}` |
| `!10<XXXX>` | Authenticated write; `{0:X4}`. Uses installer codes `E1919CR1` / `E1919PA1` (box-model variants) |
| `!05` | Write settings area |
| `!02CA` | **Commit / save to flash** (the `CA` is a fixed confirm code) |
| `!01AF` | **Reset module** (`AF` = fixed confirm code) |

### Lock  ⚠️
| Command | Meaning |
|---|---|
| `!0F` | **LOCK the control unit (BLOCCA).** Preceded by codes `E1919CR1` / `E1919PA1`. **Do not send** — with the activation server dead, unlocking may be impossible. |

### Parser opcode map (response dispatch)
The response's 2-char opcode is switched: `01,02,03,04,05,06,07,08,09,0A,0B,0F,10`
→ internal indices 0–12. `FA` appears as a special value (likely an error/ack
marker; sets an internal max-index of 100).

---

## 5. Response parsing

`GetInputReportData` reads the input report, takes the leading opcode (2 ASCII
chars) to decide what kind of reply it is, then walks the ASCII-hex payload with
two helpers:

```
HexDec1(c):  '0'–'9' -> c-'0' ;  'A'–'F' -> c-'0'-7      (single nibble)
HexDec2(hi,lo): (HexDec1(hi) << 4) | HexDec1(lo)          (one byte)
```

Fields populated into the status record `MM100_Internal_Status`:
```
bool  ready
i16   valStato            i16  valBlocco
str   strCurrentMappa     str  strCurrentSettings
i32   valTelecomando      i32  valCodiceTelecomando
i32   valPulsante         i32  valIndicatoreLED        i32 valFiloBluGas
i16   valGain_Sensibilita i16  valMedi_Coppia          i16 valAlti_Inizio
i16   valBassi_Fine       i16  valTotale               i16 valStrategia
i16   valAttesa           i16  valLimite
i64   valIdVersioneAuto
```
(`TxEn` is a parallel set of booleans = which of these fields are editable.)

---

## 6. Android implementation notes

- Use the **USB Host API** (`UsbManager`, `UsbDeviceConnection`) — **no root needed**.
  Match VID 0x26D6 / PID 0x0E19, request permission, claim the HID interface.
- Read the HID report descriptor to get input/output report lengths (or claim the
  interrupt IN/OUT endpoints and use their `maxPacketSize`).
- **Send** a command: build the `[0x00][ASCII...][0x00][pad]` buffer and send it as
  an output report — via the interrupt OUT endpoint (`bulkTransfer`) or a SET_REPORT
  control transfer. **Receive**: read the interrupt IN endpoint, parse ASCII.
- Reimplement `HexDec1/HexDec2` as above.
- Note on report ID: byte 0 is `0x00`. If the descriptor declares **no** report IDs,
  Android interrupt transfers should **omit** that leading byte — the capture will
  settle whether to include it.

---

## 7. Must-confirm before writing (USB capture)

Static analysis is solid for the command *vocabulary* but a few things should be
verified on real traffic (Wireshark + USBPcap) before any write/lock is trusted:

1. Exact character **offsets** of each field inside `?04` / `?05` responses.
2. The precise **payload bytes** of `!04` / `!10` writes and how the
   `E1919CR1` / `E1919PA1` codes are applied for the **ME1000T** specifically.
3. Whether the leading report-ID byte is on the wire or stripped.
4. What `FA` responses mean.

**Safe build order:** (1) read status `?03`, read `?04`/`?05`, (2) switch maps
`!030/!031/!032` — these are low-risk and give a working phone tool. Only attempt
map *writes* and never the `!0F` lock until the capture confirms everything.

---

## 8. VERIFIED on the wire (USB capture, 2026-10-09)

Device enumerated as **dev addr 4**, VID 0x26D6 / PID 0x0E19. All app traffic is
HID **interrupt** transfers (not control/feature):

| Direction | Endpoint | Report size | Contents |
|---|---|---|---|
| Host → box (commands) | **0x03 (OUT)** | **32 bytes** | ASCII command, zero-padded. **No report-ID byte on the wire** (the leading 0x00 is stripped). |
| Box → host (responses) | **0x81 (IN)** | **32 bytes** | ASCII response, zero-padded. Echoes the 2-char opcode, then data. |

So on Android: write the ASCII command to the **interrupt OUT** endpoint padded to
32 bytes (no report-ID prefix), read 32 bytes from the **interrupt IN** endpoint.

### Confirmed exchanges

**Status poll** (sent continuously by the app):
```
?03  ->  03 <map> <s1 s2 s3 s4>
```
Observed: `?03 -> 0321000` (map 2), after `!030 -> 0301000` (map 0),
after `!031 -> 0311000` (map 1). **3rd char = active map number.** The trailing
`1 0 0 0` carries ready/lock/settings state (lock bit = "Centralina bloccata";
was 0 = unlocked throughout). Map digit is certain; exact meaning of the last
four is probable, not critical for read/switch.

**Switch active map** — confirmed working & reversible:
```
!030 -> select map 0     !031 -> select map 1     !032 -> select map 2
```
(reply is the new `03` status reflecting the change)

**Read a map cell** — confirmed:
```
?06<X1><X2>  ->  06<X1><X2><VV>
```
`X1`,`X2` = grid coords (hex), `VV` = cell value (hex byte). Examples captured:
```
?0610 -> 061029   (cell 1,0 = 0x29)
?0601 -> 060128   (cell 0,1 = 0x28)
?0620 -> 06202F   (cell 2,0 = 0x2F)   ; map 2 value > map 1 value, as expected
```
The app walks the grid cell-by-cell to read a whole map. Row index appears to be
the map/axis (0,1,2…) and the column the point along the curve. Full ranges can be
discovered by probing read-only.

### Still not captured (do not implement blindly)
- **Writing** a cell (`!06<X1><X2><VV>`) and the save/commit sequence
  (`!04`/`!10`/`!05`/`!02CA`) — no write was performed, so these remain
  code-derived only. Capture a real write before trusting them.
- `?04` / `?05` summary reads did not fire in this session (the app used `?06`
  cell reads instead); harmless, just not needed for a working tool.

---

## 9. Settings read `?05`: warm-up delay (verified, 2026-10-09)

```
?05  ->  05 <s1 s2> <WW> 00 00 …
```
**WW = engine warm-up delay in seconds (hex).** Confirmed against the desktop app
with two independent values:

| Desktop setting | `?05` reply | WW |
|---|---|---|
| 40 s | `0500280000…` | 0x28 = 40 |
| 50 s | `0500320000…` | 0x32 = 50 |

The other `?05` fields (remote, Filo Blu, button, LED toggles) were all off/zero
in both readings, so their positions aren't decoded yet.

`?04` did **not** change when the active map changed (map 1 vs map 2), and it holds
`29` and `2F`, the same values as the first cells of Map 1 and Map 2. So it's a
map-data summary, not live status.

Status `?03` tail observed: `21000` (map 2) and `11000` (map 1). The 4th char was
`0` throughout with the box unlocked, consistent with it being the lock flag.
