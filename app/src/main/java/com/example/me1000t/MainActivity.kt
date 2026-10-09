package com.example.me1000t

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.example.me1000t.USB_PERMISSION"
    private val PREFS = "settings"
    private val KEY_THEME = "theme"   // 0 graphite, 1 red, 2 blue

    private lateinit var usbManager: UsbManager
    private var device: Me1000tDevice? = null
    private var usbDevice: UsbDevice? = null
    private var io: ScheduledExecutorService? = null
    private val ui = Handler(Looper.getMainLooper())

    private var failStreak = 0
    private var lastMap: Int? = null
    private var lastWarmup: Int? = null
    private var updatingFromDevice = false
    private var deviceLabel = "ME1000T"
    private var deviceSerial = "(sn n/a)"

    private lateinit var dot: android.view.View
    private lateinit var tvConnection: TextView
    private lateinit var tvSerial: TextView
    private lateinit var tvActiveMap: TextView
    private lateinit var tvMapPower: TextView
    private lateinit var tvInterpreted: TextView
    private lateinit var tvWarmup: TextView
    private lateinit var tvAdvancedRaw: TextView
    private lateinit var tvMapGrid: TextView
    private lateinit var tvLog: TextView
    private lateinit var mapToggle: MaterialButtonToggleGroup

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_USB_PERMISSION) return
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (granted && dev != null) openDevice(dev) else log("USB permission denied")
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev != null && dev == usbDevice) teardown("Device detached")
                }
            }
        }
    }

    private fun themeResId(idx: Int) = when (idx) {
        1 -> R.style.Theme_ME1000T_Red
        2 -> R.style.Theme_ME1000T_Blue
        else -> R.style.Theme_ME1000T_Graphite
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeIdx = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, 0)
        setTheme(themeResId(themeIdx))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        dot = findViewById(R.id.dot)
        tvConnection = findViewById(R.id.tvConnection)
        tvSerial = findViewById(R.id.tvSerial)
        tvActiveMap = findViewById(R.id.tvActiveMap)
        tvMapPower = findViewById(R.id.tvMapPower)
        tvInterpreted = findViewById(R.id.tvInterpreted)
        tvWarmup = findViewById(R.id.tvWarmup)
        tvAdvancedRaw = findViewById(R.id.tvAdvancedRaw)
        tvMapGrid = findViewById(R.id.tvMapGrid)
        tvLog = findViewById(R.id.tvLog)
        mapToggle = findViewById(R.id.mapToggle)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        findViewById<MaterialButton>(R.id.btnConnect).setOnClickListener { connect() }
        findViewById<MaterialButton>(R.id.btnReadMap).setOnClickListener { readMap() }
        findViewById<MaterialButton>(R.id.btnSnapshot).setOnClickListener { saveSnapshot() }
        findViewById<MaterialButton>(R.id.btnEditWarmup).setOnClickListener { editWarmup() }

        mapToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingFromDevice) return@addOnButtonCheckedListener
            val n = when (checkedId) {
                R.id.btnMap0 -> 0
                R.id.btnMap1 -> 1
                R.id.btnMap2 -> 2
                else -> return@addOnButtonCheckedListener
            }
            switchMap(n)
        }

        registerExported(permReceiver, IntentFilter(ACTION_USB_PERMISSION))
        registerExported(attachReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        setDot(R.color.dot_grey)
        connect()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val idx = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, 0)
        val id = when (idx) { 1 -> R.id.theme_red; 2 -> R.id.theme_blue; else -> R.id.theme_graphite }
        menu.findItem(id)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val idx = when (item.itemId) {
            R.id.theme_graphite -> 0
            R.id.theme_red -> 1
            R.id.theme_blue -> 2
            else -> return super.onOptionsItemSelected(item)
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, idx).apply()
        recreate()
        return true
    }

    private fun registerExported(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, f)
        }
    }

    private fun setDot(colorRes: Int) {
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun connect() {
        val dev = usbManager.deviceList.values.firstOrNull {
            it.vendorId == Me1000tDevice.VID && it.productId == Me1000tDevice.PID
        }
        if (dev == null) {
            tvConnection.text = "Not connected"
            tvSerial.text = "Box not found — plug the ME1000T into the phone (OTG)."
            setDot(R.color.dot_grey)
            return
        }
        usbDevice = dev
        if (usbManager.hasPermission(dev)) {
            openDevice(dev)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            usbManager.requestPermission(dev, pi)
            tvConnection.text = "Requesting USB permission…"
            setDot(R.color.dot_amber)
        }
    }

    private fun openDevice(dev: UsbDevice) {
        var chosenIntf: UsbInterface? = null
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null

        outer@ for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var tIn: UsbEndpoint? = null
            var tOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) tIn = ep else tOut = ep
                }
            }
            if (tIn != null && tOut != null) { chosenIntf = intf; epIn = tIn; epOut = tOut; break@outer }
        }

        if (chosenIntf == null || epIn == null || epOut == null) {
            tvConnection.text = "No interrupt IN/OUT endpoints found."
            setDot(R.color.dot_red)
            return
        }

        val conn = usbManager.openDevice(dev)
        if (conn == null) { tvConnection.text = "openDevice() failed."; setDot(R.color.dot_red); return }
        if (!conn.claimInterface(chosenIntf, true)) {
            tvConnection.text = "claimInterface() failed."
            setDot(R.color.dot_red); conn.close(); return
        }

        device = Me1000tDevice(conn, chosenIntf, epOut, epIn)
        failStreak = 0; lastMap = null
        deviceLabel = dev.productName ?: "ME1000T"
        deviceSerial = try { dev.serialNumber } catch (e: Exception) { null } ?: "(sn n/a)"
        tvConnection.text = "Connected — $deviceLabel"
        tvSerial.text = "SN $deviceSerial"
        setDot(R.color.dot_green)
        log("Connected VID=0x${Integer.toHexString(dev.vendorId)} PID=0x${Integer.toHexString(dev.productId)}")
        startPolling()
        fetchAdvanced()
    }

    private fun startPolling() {
        io?.shutdownNow()
        val ex = Executors.newSingleThreadScheduledExecutor()
        io = ex
        ex.scheduleWithFixedDelay({
            val d = device ?: return@scheduleWithFixedDelay
            val r = d.readStatus()
            ui.post { onStatus(r) }
        }, 400, 900, TimeUnit.MILLISECONDS)
    }

    private fun onStatus(r: String?) {
        val d = device
        val map = if (d != null) d.activeMapOf(r) else null
        if (map != null && r != null) {
            failStreak = 0; lastMap = map
            setDot(R.color.dot_green)
            tvActiveMap.text = map.toString()
            tvMapPower.text = mapPower(map)
            updateToggle(map)
            tvInterpreted.text = interpretStatus(r)
        } else {
            failStreak++
            if (failStreak >= 3) {
                setDot(R.color.dot_amber)
                tvInterpreted.text = "Link unstable — retrying…"
            }
        }
    }

    /** Best-effort decode of "03<map><f1><f2><f3><f4>". map is certain; flags interpreted. */
    private fun interpretStatus(r: String): String {
        if (r.length < 5) return ""
        val ready = r.getOrNull(3) == '1'
        val locked = r.getOrNull(4) == '1'
        val lockTxt = if (locked) "Locked" else "Unlocked"
        val readyTxt = if (ready) "Ready" else "Not ready"
        return "$readyTxt · $lockTxt   (flags ${r.substring(2)}, interpreted)"
    }

    private fun updateToggle(map: Int) {
        val id = when (map) { 0 -> R.id.btnMap0; 1 -> R.id.btnMap1; 2 -> R.id.btnMap2; else -> return }
        if (mapToggle.checkedButtonId == id) return
        updatingFromDevice = true
        mapToggle.check(id)
        updatingFromDevice = false
    }

    private fun switchMap(n: Int) {
        val ex = io ?: run { log("Not connected."); return }
        ex.execute {
            val d = device ?: return@execute
            var ok = false
            for (attempt in 1..5) {
                d.switchMap(n)
                val st = d.readStatus()
                if (d.activeMapOf(st) == n) { ok = true; break }
            }
            ui.post {
                if (ok) { lastMap = n; tvActiveMap.text = n.toString(); tvMapPower.text = mapPower(n) }
                log("Switch -> Map $n : ${if (ok) "OK" else "FAILED (retry)"}")
            }
        }
    }

    /**
     * Warm-up delay lives in the ?05 settings reply: "05" + 2 chars + VV, where VV is the
     * delay in seconds as hex. Verified against the desktop app: "0500280000…" -> 0x28 = 40 s.
     */
    private fun parseWarmup(r05: String?): Int? {
        if (r05 == null || !r05.startsWith("05") || r05.length < 6) return null
        return try { r05.substring(4, 6).toInt(16) } catch (e: Exception) { null }
    }

    private fun mapPower(m: Int): String = when (m) {
        0 -> getString(R.string.map0_desc)
        1 -> getString(R.string.map1_desc)
        2 -> getString(R.string.map2_desc)
        else -> ""
    }

    /** Read ?04 / ?05 (read-only, safe): decode the warm-up delay, keep raw replies visible. */
    private fun fetchAdvanced() {
        val ex = io ?: return
        ex.execute {
            val d = device ?: return@execute
            val r04 = d.sendCommand("?04") ?: "(no reply)"
            val r05 = d.sendCommand("?05")
            val warm = parseWarmup(r05)
            ui.post {
                lastWarmup = warm
                tvWarmup.text = if (warm != null) "$warm s" else "Unknown"
                tvAdvancedRaw.text = "?04 = $r04\n?05 = ${r05 ?: "(no reply)"}"
            }
        }
    }

    /** The app's one write: a bounded, confirmed, verified warm-up change. */
    private fun editWarmup() {
        if (device == null) { log("Not connected."); return }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((lastWarmup ?: 40).toString())
            setSelection(text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Warm-up delay (seconds)")
            .setMessage("Enter 0–255. This writes the setting to the box.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Write") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v < 0 || v > 255) { log("Invalid value (must be 0–255)."); return@setPositiveButton }
                writeWarmup(v)
            }
            .show()
    }

    private fun writeWarmup(v: Int) {
        val ex = io ?: run { log("Not connected."); return }
        log("Writing warm-up = $v s…")
        ex.execute {
            val d = device ?: return@execute
            val ok = d.setWarmup(v)
            val back = d.readCell(0, 1)
            ui.post {
                if (ok) { lastWarmup = v; tvWarmup.text = "$v s" }
                log("Warm-up -> $v s : ${if (ok) "OK" else "FAILED"}  (box reads ${back ?: "?"})")
            }
        }
    }

    private fun readMap() {
        val ex = io ?: run { log("Not connected."); return }
        ui.post { tvMapGrid.text = "Reading…" }
        ex.execute {
            val d = device ?: return@execute
            ui.post { tvMapGrid.text = buildGrid(d) }
        }
    }

    private fun buildGrid(d: Me1000tDevice): String {
        val sb = StringBuilder()
        sb.append("       c0 c1 c2 c3 c4 c5 c6 c7 c8 c9 cA cB cC cD cE cF\n")
        for (x1 in 0..2) {
            sb.append("row $x1: ")
            for (x2 in 0..15) {
                val v = d.readCell(x1, x2)
                sb.append(if (v == null) "-- " else String.format("%02X ", v))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun saveSnapshot() {
        val ex = io ?: run { log("Not connected."); return }
        ui.post { log("Building snapshot…") }
        ex.execute {
            val d = device ?: return@execute
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val fname = "me1000t_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val status = d.readStatus() ?: "(no reply)"
            val r04 = d.sendCommand("?04") ?: "(no reply)"
            val r05 = d.sendCommand("?05") ?: "(no reply)"
            val warm = parseWarmup(r05)
            val activeMap = d.activeMapOf(status)
            val grid = buildGrid(d)
            val text = buildString {
                append("ME1000T snapshot\n")
                append("Taken: $ts\n")
                append("Vehicle: ${getString(R.string.vehicle_name)} — ${getString(R.string.vehicle_spec)}\n")
                append("Device: $deviceLabel   SN=$deviceSerial\n\n")
                append("Status (?03): $status\n")
                append("  ${interpretStatus(status)}\n")
                if (activeMap != null) append("  Active map: $activeMap (${mapPower(activeMap)})\n")
                append("  Warm-up delay: ${warm?.let { "$it s" } ?: "unknown"}\n\n")
                append("Map params (?04): $r04\n")
                append("Settings   (?05): $r05\n\n")
                append("Map cell grid:\n$grid\n")
                append("Note: read-only snapshot. No values were written to the box.\n")
            }
            try {
                val dir = File(getExternalFilesDir(null), "snapshots").apply { mkdirs() }
                val f = File(dir, fname)
                f.writeText(text)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                ui.post {
                    log("Snapshot saved: $fname")
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share snapshot"))
                }
            } catch (e: Exception) {
                ui.post { log("Snapshot failed: ${e.message}") }
            }
        }
    }

    private fun log(s: String) {
        tvLog.text = "$s\n${tvLog.text}"
    }

    private fun teardown(why: String) {
        io?.shutdownNow(); io = null
        device?.close(); device = null
        usbDevice = null
        ui.post {
            tvConnection.text = why
            tvSerial.text = ""
            tvActiveMap.text = "—"
            tvMapPower.text = ""
            tvInterpreted.text = ""
            setDot(R.color.dot_grey)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(permReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(attachReceiver) } catch (_: Exception) {}
        teardown("Closed")
    }
}
