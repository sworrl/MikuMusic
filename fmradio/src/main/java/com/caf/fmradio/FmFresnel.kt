package com.caf.fmradio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether the hill between you and a transmitter is actually in the way.
 *
 * The station catalogue can say a 50 kW transmitter is 12 km off, and in this terrain that
 * tells you very little: the ranking it produces is an upper bound that assumes a clear path,
 * and a clear path is the exception in a hollow. This is the part that looks at the ground.
 *
 * It samples real elevations along the great circle to the transmitter, draws the line of
 * sight with the usual 4/3-earth refraction allowance, and measures how much of the first
 * Fresnel zone survives. The 60% rule is the convention: clearance below 0.6 of the first
 * Fresnel radius starts costing signal, and an obstruction that crosses the line of sight
 * itself is diffraction loss rather than attenuation.
 *
 * NOTHING HERE IS SYNTHESISED. With no network and no cached profile it returns null and the
 * UI says it does not know, because a terrain plot invented from a straight line between two
 * points would look exactly as convincing as a real one and be worth nothing.
 */
object FmFresnel {

    private const val TAG = "FmFresnel"
    private const val C = 299_792_458.0
    private const val EARTH_R = 6_371_000.0
    /** Refraction allowance. 4/3 is the standard temperate-atmosphere k-factor. */
    private const val K_FACTOR = 4.0 / 3.0
    /** Where a listener's ear is, above the ground they are standing on. */
    private const val RX_HEIGHT_M = 1.5
    /** Clearance below this fraction of the first Fresnel radius is where loss starts. */
    const val FRESNEL_RULE = 0.6

    /** Open-Meteo takes a batch of coordinates and needs no key, same as the weather tile. */
    private const val ELEVATION_API = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_PER_REQUEST = 100
    private const val TIMEOUT_MS = 12_000

    data class Point(
        /** Metres along the path from the listener. */
        val distM: Double,
        val lat: Double,
        val lon: Double,
        /** Ground elevation, metres above sea level. Real, from the elevation service. */
        val groundM: Double,
        /** Straight line from the listener's antenna to the transmitter's, at this distance. */
        val sightM: Double,
        /** First Fresnel zone radius here. */
        val fresnelM: Double,
        /** sightM - groundM. Negative means the ground is above the line of sight. */
        val clearanceM: Double,
    ) {
        /** Clearance as a fraction of the first Fresnel radius; 1.0 is a fully clear zone. */
        val fresnelFraction: Double get() = if (fresnelM <= 0.0) 1.0 else clearanceM / fresnelM
    }

    data class Profile(
        val station: FmStationCatalogue.Station,
        val listenerLat: Double,
        val listenerLon: Double,
        val points: List<Point>,
        /** Ground elevation under the listener. */
        val listenerGroundM: Double,
        /** Ground elevation under the transmitter, plus its HAAT. */
        val txAntennaM: Double,
        val fetchedAtMs: Long,
    ) {
        val distanceKm: Double get() = (points.lastOrNull()?.distM ?: 0.0) / 1000.0

        /** The worst point on the path, which is the one that decides the verdict. */
        val worst: Point? get() = points.minByOrNull { it.fresnelFraction }

        /** True when something actually crosses the straight line, not just the zone. */
        val blocked: Boolean get() = points.any { it.clearanceM < 0.0 }

        /**
         * Rough extra path loss from the obstruction, in dB, by the knife-edge approximation.
         *
         * Deliberately labelled rough. A single knife edge is a crude stand-in for a ridge
         * line, and this says nothing about ground reflections, foliage or the receiver. It is
         * here to rank "badly blocked" against "just grazing", not to predict a field strength.
         */
        val diffractionLossDb: Double get() {
            val w = worst ?: return 0.0
            val v = -w.fresnelFraction * sqrt(2.0)   // Fresnel-Kirchhoff parameter
            return when {
                v <= -0.78 -> 0.0
                else -> 6.9 + 20 * log10(sqrt((v - 0.1) * (v - 0.1) + 1.0) + v - 0.1)
            }.coerceAtLeast(0.0)
        }

        val verdict: String get() {
            val f = worst?.fresnelFraction ?: return "unknown"
            return when {
                f >= 1.0 -> "clear path"
                f >= FRESNEL_RULE -> "clear enough"
                f >= 0.0 -> "grazing the ridge"
                else -> "blocked by terrain"
            }
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun haversineM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * asin(min(1.0, sqrt(h)))
    }

    /** Great-circle interpolation, so a 130 km path does not drift off the real line. */
    private fun interpolate(
        aLat: Double, aLon: Double, bLat: Double, bLon: Double, f: Double,
    ): Pair<Double, Double> {
        val p1 = Math.toRadians(aLat); val l1 = Math.toRadians(aLon)
        val p2 = Math.toRadians(bLat); val l2 = Math.toRadians(bLon)
        val d = 2 * asin(sqrt(
            sin((p2 - p1) / 2).let { it * it } +
                cos(p1) * cos(p2) * sin((l2 - l1) / 2).let { it * it }))
        if (d == 0.0) return aLat to aLon
        val A = sin((1 - f) * d) / sin(d)
        val B = sin(f * d) / sin(d)
        val x = A * cos(p1) * cos(l1) + B * cos(p2) * cos(l2)
        val y = A * cos(p1) * sin(l1) + B * cos(p2) * sin(l2)
        val z = A * sin(p1) + B * sin(p2)
        return Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
    }

    // ---------------------------------------------------------------- elevation

    private fun cacheFile(ctx: Context, lat: Double, lon: Double, st: FmStationCatalogue.Station): File {
        // Rounded to about 100 m, so standing still reuses the profile and walking a block
        // does not invalidate a 60-sample fetch.
        val key = "%.3f_%.3f_%s_%d".format(java.util.Locale.US, lat, lon, st.call, st.khz)
        return File(File(ctx.cacheDir, "miku_fresnel").apply { mkdirs() }, "$key.json")
    }

    /** Elevations for a list of coordinates, in order. Null if the service cannot be reached. */
    private fun fetchElevations(coords: List<Pair<Double, Double>>): List<Double>? {
        val out = ArrayList<Double>(coords.size)
        for (chunk in coords.chunked(MAX_PER_REQUEST)) {
            val lats = chunk.joinToString(",") { "%.5f".format(java.util.Locale.US, it.first) }
            val lons = chunk.joinToString(",") { "%.5f".format(java.util.Locale.US, it.second) }
            val body = runCatching {
                val c = (URL("$ELEVATION_API?latitude=$lats&longitude=$lons").openConnection()
                    as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                }
                try {
                    if (c.responseCode != 200) {
                        Log.w(TAG, "elevation service returned HTTP ${c.responseCode}")
                        return null
                    }
                    c.inputStream.bufferedReader().readText()
                } finally { c.disconnect() }
            }.onFailure { Log.w(TAG, "elevation fetch failed: $it") }.getOrNull() ?: return null
            val arr = runCatching { JSONObject(body).getJSONArray("elevation") }
                .onFailure { Log.w(TAG, "elevation reply was not what we expected: $it") }
                .getOrNull() ?: return null
            for (i in 0 until arr.length()) out.add(arr.getDouble(i))
        }
        return if (out.size == coords.size) out else null.also {
            Log.w(TAG, "elevation service returned ${out.size} of ${coords.size} points")
        }
    }

    // ---------------------------------------------------------------- the profile

    /**
     * Terrain profile between the listener and a transmitter. Blocking; call it off the main
     * thread. Returns null when the elevations cannot be obtained and nothing is cached.
     */
    fun profile(
        ctx: Context,
        lat: Double, lon: Double,
        st: FmStationCatalogue.Station,
        samples: Int = 60,
    ): Profile? {
        val cache = cacheFile(ctx, lat, lon, st)
        readCache(cache, st, lat, lon)?.let { return it }

        val totalM = haversineM(lat, lon, st.lat, st.lon)
        if (totalM < 50.0) return null
        val n = samples.coerceIn(16, MAX_PER_REQUEST * 2)
        val coords = (0 until n).map { i -> interpolate(lat, lon, st.lat, st.lon, i.toDouble() / (n - 1)) }
        val ground = fetchElevations(coords) ?: return null

        val rxGround = ground.first()
        val txGround = ground.last()
        val rxAnt = rxGround + RX_HEIGHT_M
        // HAAT is height above AVERAGE terrain, not above the mast's own ground, so this is an
        // approximation; it is the only height the public records give and it is far better
        // than assuming the antenna sits on the dirt.
        val txAnt = txGround + (st.haatM ?: 30.0)
        val lambda = C / (st.khz * 1000.0)

        val points = ArrayList<Point>(n)
        for (i in 0 until n) {
            val f = i.toDouble() / (n - 1)
            val d1 = totalM * f
            val d2 = totalM - d1
            // Straight line between the two antennas, less the earth's bulge under it.
            val bulge = (d1 * d2) / (2 * K_FACTOR * EARTH_R)
            val sight = rxAnt + (txAnt - rxAnt) * f - bulge
            val r1 = if (d1 <= 0.0 || d2 <= 0.0) 0.0 else sqrt(lambda * d1 * d2 / totalM)
            points.add(Point(d1, coords[i].first, coords[i].second, ground[i], sight, r1, sight - ground[i]))
        }
        val p = Profile(st, lat, lon, points, rxGround, txAnt, System.currentTimeMillis())
        writeCache(cache, p)
        Log.i(TAG, "${st.call} at ${"%.1f".format(p.distanceKm)} km: ${p.verdict}, " +
            "worst clearance ${"%.0f".format(p.worst?.clearanceM ?: 0.0)} m " +
            "(${"%.0f".format((p.worst?.fresnelFraction ?: 0.0) * 100)}% of the first zone)")
        return p
    }

    // ---------------------------------------------------------------- caching

    private fun writeCache(f: File, p: Profile) {
        runCatching {
            val o = JSONObject()
            o.put("at", p.fetchedAtMs)
            o.put("rxGround", p.listenerGroundM)
            o.put("txAnt", p.txAntennaM)
            o.put("ground", org.json.JSONArray(p.points.map { it.groundM }))
            o.put("lat", org.json.JSONArray(p.points.map { it.lat }))
            o.put("lon", org.json.JSONArray(p.points.map { it.lon }))
            o.put("dist", org.json.JSONArray(p.points.map { it.distM }))
            o.put("sight", org.json.JSONArray(p.points.map { it.sightM }))
            o.put("fresnel", org.json.JSONArray(p.points.map { it.fresnelM }))
            f.writeText(o.toString())
        }.onFailure { Log.w(TAG, "could not cache the profile: $it") }
    }

    private fun readCache(
        f: File, st: FmStationCatalogue.Station, lat: Double, lon: Double,
    ): Profile? = runCatching {
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val g = o.getJSONArray("ground"); val la = o.getJSONArray("lat")
        val lo = o.getJSONArray("lon"); val d = o.getJSONArray("dist")
        val s = o.getJSONArray("sight"); val fr = o.getJSONArray("fresnel")
        val pts = (0 until g.length()).map { i ->
            Point(d.getDouble(i), la.getDouble(i), lo.getDouble(i), g.getDouble(i),
                  s.getDouble(i), fr.getDouble(i), s.getDouble(i) - g.getDouble(i))
        }
        if (pts.isEmpty()) null
        else Profile(st, lat, lon, pts, o.getDouble("rxGround"), o.getDouble("txAnt"), o.getLong("at"))
    }.getOrNull()

    /** How much of the cache is on disk, for a diagnostics screen. */
    fun cacheBytes(ctx: Context): Long =
        File(ctx.cacheDir, "miku_fresnel").listFiles()?.sumOf { it.length() } ?: 0L
}
