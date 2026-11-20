package sk.suncon.slnkomer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val C_BG = 0xFF0F172A.toInt()
private val C_CARD = 0xFF1E293B.toInt()
private val C_LINE = 0xFF334155.toInt()
private val C_TEXT = 0xFFE2E8F0.toInt()
private val C_MUTED = 0xFF94A3B8.toInt()
private val C_SUN = 0xFFFBBF24.toInt()
private val C_OK = 0xFF22C55E.toInt()
private val C_WARN = 0xFFF59E0B.toInt()
private val C_BAD = 0xFFEF4444.toInt()
private val C_ACCENT = 0xFF38BDF8.toInt()

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val R2D = 180.0 / PI
private const val D2R = PI / 180.0

class MainActivity : Activity(), SensorEventListener {

    private enum class Mode { PANEL, AIM }

    private class Cell(val root: LinearLayout, val label: TextView, val value: TextView, val sub: TextView)

    private val skLocale: Locale = Locale.forLanguageTag("sk-SK")

    // --- senzory ---
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private val rot = FloatArray(9)
    private var haveRotation = false
    private var lastSensorTime = 0L
    private var magAccuracy = -1

    // --- poloha ---
    private lateinit var locationManager: LocationManager
    private var lat = 48.1486
    private var lon = 17.1077
    private var alt = 150.0
    private var hasFix = false
    private var fixAccuracy = 0f
    private var lastFixTime = 0L
    private var manualLocation = false
    private var permissionAsked = false

    // --- stav ---
    private var mode = Mode.PANEL
    private var frozen = false
    private var customTime: Long? = null
    private var azOffset = 0.0
    private var smN: DoubleArray? = null
    private var smS: DoubleArray? = null
    private val smoothK = 0.8

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 100)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // --- UI ---
    private lateinit var tvStatus: TextView
    private lateinit var btnPanel: Button
    private lateinit var btnAim: Button
    private lateinit var target: TargetView
    private lateinit var tvTotal: TextView
    private lateinit var tvHint: TextView
    private lateinit var cDAz: Cell
    private lateinit var cDTilt: Cell
    private lateinit var cEff: Cell
    private lateinit var tvAdvice: TextView
    private lateinit var cSunAz: Cell
    private lateinit var cSunEl: Cell
    private lateinit var cPAz: Cell
    private lateinit var cPTilt: Cell
    private lateinit var cOpt: Cell
    private lateinit var cTime: Cell
    private lateinit var btnFreeze: Button
    private lateinit var tvInfo: TextView

    // ================= životný cyklus =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val prefs = getSharedPreferences("slnkomer", Context.MODE_PRIVATE)
        azOffset = prefs.getFloat("azOffset", 0f).toDouble()
        mode = if (prefs.getString("mode", "PANEL") == "AIM") Mode.AIM else Mode.PANEL

        setContentView(buildUi())
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startLocation()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        try { locationManager.removeUpdates(locationListener) } catch (e: Exception) {}
        handler.removeCallbacks(ticker)
    }

    private fun savePrefs() {
        getSharedPreferences("slnkomer", Context.MODE_PRIVATE).edit()
            .putFloat("azOffset", azOffset.toFloat())
            .putString("mode", mode.name)
            .apply()
    }

    // ================= senzory =================

    override fun onSensorChanged(event: SensorEvent?) {
        val ev = event ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (frozen) return
                val v = if (ev.values.size > 4) ev.values.copyOf(4) else ev.values
                SensorManager.getRotationMatrixFromVector(rot, v)
                haveRotation = true
                lastSensorTime = SystemClock.elapsedRealtime()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> magAccuracy = ev.accuracy
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }

    // ================= poloha =================

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (manualLocation) return
        if (!hasLocationPermission()) {
            if (!permissionAsked) {
                permissionAsked = true
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1
                )
            }
            return
        }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.getLastKnownLocation(p)?.let { if (!hasFix) onLocation(it) }
                    locationManager.requestLocationUpdates(p, 2000L, 0f, locationListener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasLocationPermission()) startLocation()
    }

    private fun onLocation(l: Location) {
        if (manualLocation) return
        val now = System.currentTimeMillis()
        if (hasFix && l.accuracy > fixAccuracy * 1.5f && now - lastFixTime < 15000) return
        lat = l.latitude
        lon = l.longitude
        if (l.hasAltitude()) alt = l.altitude
        fixAccuracy = l.accuracy
        lastFixTime = now
        hasFix = true
    }

    // ================= výpočet a zobrazenie =================

    private fun f1(x: Double) = String.format(skLocale, "%.1f°", x)
    private fun signed(x: Double): String {
        val s = String.format(skLocale, "%.1f°", abs(x))
        return when {
            x > 0.05 -> "+$s"
            x < -0.05 -> "−$s"
            else -> s
        }
    }

    private val dirs = arrayOf("S", "SSV", "SV", "VSV", "V", "VJV", "JV", "JJV", "J", "JJZ", "JZ", "ZJZ", "Z", "ZSZ", "SZ", "SSZ")
    private fun dirName(az: Double) = dirs[((SunMath.norm360(az) / 22.5).roundToInt()) % 16]

    private fun smooth(prev: DoubleArray?, v: DoubleArray): DoubleArray {
        if (prev == null) return v
        val k = smoothK
        val r = doubleArrayOf(prev[0] * k + v[0] * (1 - k), prev[1] * k + v[1] * (1 - k), prev[2] * k + v[2] * (1 - k))
        val n = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).let { if (it < 1e-9) 1.0 else it }
        return doubleArrayOf(r[0] / n, r[1] / n, r[2] / n)
    }

    private fun update() {
        val t = customTime ?: System.currentTimeMillis()
        val sun = SunMath.position(t, lat, lon)
        val decl = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), t).declination.toDouble()
        val corr = decl + azOffset
        val sunUp = sun.elevation > 0

        cSunAz.value.text = f1(sun.azimuth)
        cSunAz.sub.text = dirName(sun.azimuth)
        cSunEl.value.text = f1(sun.elevation)
        cSunEl.sub.text = if (sunUp) "nad obzorom" else "pod obzorom"
        cSunEl.sub.setTextColor(if (sunUp) C_MUTED else C_BAD)
        cOpt.value.text = if (sunUp) f1(90 - sun.elevation) else "–"
        val fmt = SimpleDateFormat("d.M. HH:mm:ss", skLocale)
        cTime.value.text = fmt.format(Date(t)) + if (customTime != null) " (zvolený)" else ""
        cTime.sub.text = String.format(skLocale, "%.4f, %.4f", lat, lon) + when {
            manualLocation -> " (ručne)"
            hasFix -> " (±${fixAccuracy.roundToInt()} m)"
            else -> " (čakám na GPS)"
        }
        tvInfo.text = String.format(
            skLocale,
            "Magnetická deklinácia pre túto polohu: %+.1f° (prepočítaná automaticky)%s",
            decl,
            if (azOffset != 0.0) String.format(skLocale, ", ručná korekcia azimutu: %+.1f°", azOffset) else ""
        )

        // stav senzorov
        val live = haveRotation && SystemClock.elapsedRealtime() - lastSensorTime < 2000
        when {
            rotationSensor == null -> { tvStatus.text = "Telefón nemá senzor orientácie"; tvStatus.setTextColor(C_BAD) }
            frozen -> { tvStatus.text = "Zmrazené"; tvStatus.setTextColor(C_ACCENT) }
            !live -> { tvStatus.text = "Čakám na senzory…"; tvStatus.setTextColor(C_WARN) }
            magAccuracy in 0..1 -> { tvStatus.text = "● Kompas nepresný – kalibruj (osmička)"; tvStatus.setTextColor(C_WARN) }
            else -> { tvStatus.text = "● Senzory OK"; tvStatus.setTextColor(C_OK) }
        }

        if (!haveRotation) {
            target.clearSun()
            return
        }

        // Rotačná matica R: svet (východ, magnetický sever, hore) = R · zariadenie
        val sMag = SunMath.azElToVec(sun.azimuth - corr, sun.elevation)
        val sgn = if (mode == Mode.AIM) -1.0 else 1.0
        val nRaw = doubleArrayOf(sgn * rot[2], sgn * rot[5], sgn * rot[8])
        val sDevRaw = doubleArrayOf(
            rot[0] * sMag[0] + rot[3] * sMag[1] + rot[6] * sMag[2],
            rot[1] * sMag[0] + rot[4] * sMag[1] + rot[7] * sMag[2],
            rot[2] * sMag[0] + rot[5] * sMag[1] + rot[8] * sMag[2]
        )
        if (!frozen || smN == null) {
            smN = smooth(smN, nRaw)
            smS = smooth(smS, sDevRaw)
        }
        val n = smN ?: nRaw
        val sd = smS ?: sDevRaw

        val dotNS = (n[0] * sMag[0] + n[1] * sMag[1] + n[2] * sMag[2]).coerceIn(-1.0, 1.0)
        val total = acos(dotNS) * R2D
        val tilt = acos(n[2].coerceIn(-1.0, 1.0)) * R2D
        val azTrue = SunMath.norm360(atan2(n[0], n[1]) * R2D + corr)
        val dAz = SunMath.wrap180(azTrue - sun.azimuth)
        val optTilt = 90.0 - sun.elevation
        val dTilt = tilt - optTilt
        val flat = tilt < 2.0

        target.setSun(sd[0], sd[1], acos((sgn * sd[2]).coerceIn(-1.0, 1.0)) * R2D)

        val col = when {
            total < 5 -> C_OK
            total < 15 -> C_WARN
            else -> C_BAD
        }
        tvTotal.text = f1(total)
        tvTotal.setTextColor(col)

        cPTilt.value.text = f1(tilt)
        cPAz.value.text = if (flat) "–" else f1(azTrue)
        cPAz.sub.text = if (flat) "plocha je vodorovná" else dirName(azTrue)

        cDAz.value.text = if (flat) "–" else signed(dAz)
        cDAz.sub.text = when {
            flat -> " "
            abs(dAz) < 1 -> "presne"
            dAz > 0 -> "stočené viac na západ"
            else -> "stočené viac na východ"
        }
        cDTilt.value.text = if (sunUp) signed(dTilt) else "–"
        cDTilt.sub.text = when {
            !sunUp -> " "
            abs(dTilt) < 1 -> "presne"
            dTilt > 0 -> "príliš strmo"
            else -> "príliš naplocho"
        }
        val eff = max(0.0, cos(total * D2R))
        if (sunUp) {
            cEff.value.text = String.format(skLocale, "%.1f %%", eff * 100)
            cEff.value.setTextColor(col)
        } else {
            cEff.value.text = "slnko je pod obzorom"
            cEff.value.setTextColor(C_BAD)
        }

        tvAdvice.text = when {
            !sunUp -> "Slnko je pod obzorom. Cez tlačidlo Čas môžeš zvoliť iný čas."
            total < 2 -> "Plocha mieri presne na slnko."
            else -> {
                val parts = mutableListOf<String>()
                if (!flat && abs(dAz) >= 1) parts.add(
                    "otoč o ${abs(dAz).roundToInt()}° " +
                        if (dAz > 0) "na východ (doľava pri pohľade k slnku)" else "na západ (doprava pri pohľade k slnku)"
                )
                if (abs(dTilt) >= 1) parts.add(
                    (if (dTilt > 0) "zmenši" else "zväčši") + " sklon o ${abs(dTilt).roundToInt()}°"
                )
                if (parts.isEmpty()) "" else "Na kolmý dopad: " + parts.joinToString(" a ") + "."
            }
        }
    }

    // ================= akcie =================

    private fun applyMode() {
        val panel = mode == Mode.PANEL
        styleSeg(btnPanel, panel)
        styleSeg(btnAim, !panel)
        cPAz.label.text = if (panel) "PLOCHA – AZIMUT" else "ZADNÁ STRANA – AZIMUT"
        cPTilt.label.text = if (panel) "PLOCHA – SKLON" else "MIERENIE – SKLON"
        tvHint.text = if (panel)
            "Bodka ukazuje smer k slnku. Nakláňaj obrazovku k bodke, kým nebude v strede."
        else
            "Ako hľadáčik: posúvaj telefón, kým nebude slnko (bodka) v strede."
        smN = null
        smS = null
        savePrefs()
    }

    private fun toggleFreeze() {
        frozen = !frozen
        btnFreeze.text = if (frozen) "Pokračovať v meraní" else "Zmraziť hodnoty"
    }

    private fun chooseTime() {
        AlertDialog.Builder(this)
            .setTitle("Čas výpočtu")
            .setItems(arrayOf("Aktuálny čas", "Zvoliť dátum a čas…")) { _, which ->
                if (which == 0) customTime = null else pickDateTime()
            }
            .show()
    }

    private fun pickDateTime() {
        val cal = Calendar.getInstance()
        customTime?.let { cal.timeInMillis = it }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val c = Calendar.getInstance()
                c.set(y, m, d, h, min, 0)
                c.set(Calendar.MILLISECOND, 0)
                customTime = c.timeInMillis
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun numberInput(value: String, hint: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(value)
        this.hint = hint
    }

    private fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    private fun chooseLocation() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etLat = numberInput(String.format(Locale.US, "%.5f", lat), "Zemepisná šírka (napr. 48.1486)")
        val etLon = numberInput(String.format(Locale.US, "%.5f", lon), "Zemepisná dĺžka (napr. 17.1077)")
        box.addView(etLat)
        box.addView(etLon)
        AlertDialog.Builder(this)
            .setTitle("Poloha")
            .setView(box)
            .setPositiveButton("Použiť zadanú") { _, _ ->
                val la = parseNum(etLat.text.toString())
                val lo = parseNum(etLon.text.toString())
                if (la != null && lo != null && abs(la) <= 90 && abs(lo) <= 180) {
                    manualLocation = true
                    lat = la
                    lon = lo
                    try { locationManager.removeUpdates(locationListener) } catch (e: Exception) {}
                }
            }
            .setNeutralButton("Použiť GPS") { _, _ ->
                manualLocation = false
                hasFix = false
                permissionAsked = false
                startLocation()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun chooseAzOffset() {
        val et = numberInput(String.format(Locale.US, "%.1f", azOffset), "Korekcia v stupňoch, napr. -3.5")
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("Korekcia azimutu")
            .setMessage("Ak poznáš presný smer (napr. hranu strechy z katastrálnej mapy), polož telefón pozdĺž nej a zadaj rozdiel. Kladná hodnota posúva azimut v smere hodinových ručičiek.")
            .setView(box)
            .setPositiveButton("Uložiť") { _, _ ->
                parseNum(et.text.toString())?.let { azOffset = it; savePrefs() }
            }
            .setNeutralButton("Vynulovať") { _, _ -> azOffset = 0.0; savePrefs() }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // ================= stavba UI =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun text(size: Float, color: Int, bold: Boolean = false, value: String = ""): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            text = value
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C_CARD, 14, C_LINE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun heading(t: String) = text(12f, C_MUTED, true, t).apply { setPadding(0, 0, 0, dp(8)) }

    private fun cell(label: String, sub: String = " ", valueSize: Float = 22f): Cell {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C_BG, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val l = text(11f, C_MUTED, false, label)
        val v = text(valueSize, C_TEXT, true, "–")
        val s = text(12f, C_MUTED, false, sub)
        root.addView(l); root.addView(v); root.addView(s)
        return Cell(root, l, v, s)
    }

    private fun row(vararg cells: Cell): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        cells.forEachIndexed { i, c ->
            addView(c.root, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) C_BG else C_TEXT)
        background = rounded(if (primary) C_SUN else C_LINE, 12)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun styleSeg(b: Button, on: Boolean) {
        b.background = rounded(if (on) C_ACCENT else C_BG, 8)
        b.setTextColor(if (on) C_BG else C_MUTED)
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // hlavička
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(text(20f, C_TEXT, true, "☀ Slnkomer").apply { setTextColor(C_TEXT) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        tvStatus = text(12f, C_MUTED, false, "Čakám na senzory…").apply { gravity = Gravity.END }
        header.addView(tvStatus, LinearLayout.LayoutParams(0, WRAP, 1.2f))
        col.addView(header)

        // režim
        val segCard = card()
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C_BG, 10)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        btnPanel = button("Telefón na paneli") { mode = Mode.PANEL; applyMode() }
        btnAim = button("Zadnou stranou k slnku") { mode = Mode.AIM; applyMode() }
        seg.addView(btnPanel, LinearLayout.LayoutParams(0, WRAP, 1f))
        seg.addView(btnAim, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(3) })
        segCard.addView(seg)
        col.addView(segCard)

        // zameriavač
        val tCard = card()
        val frame = FrameLayout(this)
        target = TargetView(this)
        frame.addView(target, FrameLayout.LayoutParams(MATCH, WRAP))
        val totalBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        tvTotal = text(30f, C_TEXT, true, "–").apply { gravity = Gravity.END }
        totalBox.addView(tvTotal)
        totalBox.addView(text(11f, C_MUTED, false, "celková odchýlka").apply { gravity = Gravity.END })
        frame.addView(totalBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))
        tCard.addView(frame)
        tvHint = text(13f, C_MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        tCard.addView(tvHint)
        col.addView(tCard)

        // odchýlka
        val dCard = card()
        dCard.addView(heading("ODCHÝLKA OD SLNKA"))
        cDAz = cell("AZIMUT")
        cDTilt = cell("SKLON")
        dCard.addView(row(cDAz, cDTilt))
        cEff = cell("VYUŽITIE PRIAMEHO ŽIARENIA (cos θ)", "Podiel priameho žiarenia dopadajúceho kolmo na plochu")
        dCard.addView(row(cEff))
        tvAdvice = text(15f, C_TEXT).apply { setPadding(0, dp(4), 0, 0) }
        dCard.addView(tvAdvice)
        col.addView(dCard)

        // hodnoty
        val vCard = card()
        vCard.addView(heading("HODNOTY"))
        cSunAz = cell("SLNKO – AZIMUT")
        cSunEl = cell("SLNKO – VÝŠKA")
        vCard.addView(row(cSunAz, cSunEl))
        cPAz = cell("PLOCHA – AZIMUT")
        cPTilt = cell("PLOCHA – SKLON", "od vodorovnej roviny")
        vCard.addView(row(cPAz, cPTilt))
        cOpt = cell("IDEÁLNY SKLON TERAZ", "kolmo na slnko")
        cTime = cell("ČAS VÝPOČTU", " ", 16f)
        vCard.addView(row(cOpt, cTime))
        btnFreeze = button("Zmraziť hodnoty", primary = true) { toggleFreeze() }
        vCard.addView(btnFreeze, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        col.addView(vCard)

        // nastavenia
        val sCard = card()
        sCard.addView(heading("NASTAVENIA"))
        val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sRow.addView(button("Čas") { chooseTime() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        sRow.addView(button("Poloha") { chooseLocation() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        sRow.addView(button("Korekcia") { chooseAzOffset() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        sCard.addView(sRow)
        tvInfo = text(12f, C_MUTED).apply { setPadding(0, dp(10), 0, 0) }
        sCard.addView(tvInfo)
        sCard.addView(text(12f, C_MUTED, false,
            "Sklon je presný vždy. Azimut závisí od kompasu: kovové rámy a konštrukcie ho skresľujú. " +
                "Ak sa ukáže varovanie, pohybuj telefónom do tvaru osmičky.").apply { setPadding(0, dp(6), 0, 0) })
        col.addView(sCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(C_BG)
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        val pad = dp(14)
        scroll.setPadding(pad, pad, pad, pad)
        scroll.clipToPadding = false
        // Android 15+ kreslí pod systémové lišty – doplníme odsadenie
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    pad + insets.systemWindowInsetLeft, pad + insets.systemWindowInsetTop,
                    pad + insets.systemWindowInsetRight, pad + insets.systemWindowInsetBottom
                )
            }
            insets
        }
        return scroll
    }
}
