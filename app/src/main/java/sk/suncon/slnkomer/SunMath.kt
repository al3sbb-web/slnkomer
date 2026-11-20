package sk.suncon.slnkomer

import kotlin.math.*

/** Poloha slnka podľa algoritmu NOAA (presnosť cca 0,05°) vrátane atmosférickej refrakcie. */
object SunMath {
    data class SunPos(val azimuth: Double, val elevation: Double)

    private const val D2R = PI / 180.0
    private const val R2D = 180.0 / PI

    fun norm360(x: Double): Double { val r = x % 360.0; return if (r < 0) r + 360.0 else r }
    fun wrap180(x: Double): Double { val r = norm360(x); return if (r > 180.0) r - 360.0 else r }

    /** @param timeMillis UTC čas v ms, @return azimut (od severu v smere hodín) a výška nad obzorom v stupňoch */
    fun position(timeMillis: Long, lat: Double, lon: Double): SunPos {
        val jd = timeMillis / 86400000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = m * D2R
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * mr) * (0.019993 - 0.000101 * t) +
                sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(omega * D2R)
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega * D2R)
        val decl = asin(sin(eps * D2R) * sin(lambda * D2R))
        val y = tan(eps * D2R / 2).pow(2)
        val l0r = l0 * D2R
        val eot = 4 * R2D * (y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr))
        val dayMs = ((timeMillis % 86400000L) + 86400000L) % 86400000L
        val utcMin = dayMs / 60000.0
        val tst = ((utcMin + eot + 4 * lon) % 1440.0 + 1440.0) % 1440.0
        val ha = (tst / 4.0 - 180.0) * D2R
        val latr = lat * D2R
        val cosZ = (sin(latr) * sin(decl) + cos(latr) * cos(decl) * cos(ha)).coerceIn(-1.0, 1.0)
        val zen = acos(cosZ)
        val az = norm360(atan2(sin(ha), cos(ha) * sin(latr) - tan(decl) * cos(latr)) * R2D + 180.0)
        var el = 90.0 - zen * R2D
        var refr = 0.0
        if (el <= 85.0) {
            val te = tan(el * D2R)
            refr = (when {
                el > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / te.pow(5)
                el > -0.575 -> 1735.0 + el * (-518.2 + el * (103.4 + el * (-12.79 + el * 0.711)))
                else -> -20.774 / te
            }) / 3600.0
        }
        el += refr
        return SunPos(az, el)
    }

    /** Jednotkový vektor v súradniciach východ/sever/hore. */
    fun azElToVec(azDeg: Double, elDeg: Double): DoubleArray {
        val a = azDeg * D2R
        val h = elDeg * D2R
        return doubleArrayOf(sin(a) * cos(h), cos(a) * cos(h), sin(h))
    }
}
