package com.miku.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide, cross-thread scan status for [rescan]'s library scan. Plain @Volatile/Atomic
 * fields rather than Compose State — the walk phase can tick [visited] tens of thousands of
 * times a second across several worker threads, and writing Compose State that fast from
 * multiple background threads is both wasteful (a recomposition per tick) and not what it's
 * built for. The UI polls this on a timer instead (see ScannerPill), which naturally throttles
 * to a smooth update rate no matter how fast the scan itself is running.
 */
object ScanProgress {
    @Volatile var active: Boolean = false
    @Volatile var phase: String = ""          // "Scanning folders…" / "Reading tags…" / ""
    val visited = AtomicInteger(0)            // files looked at during the directory walk
    val newFound = AtomicInteger(0)           // of those, ones not already in the library
    val tagsScanned = AtomicInteger(0)        // new files the system tag-reader has finished
    @Volatile var tagsTotal: Int = 0
    @Volatile var resultDelta: Int = 0        // net new tracks from the last completed scan
    @Volatile var resultTotal: Int = 0
    @Volatile var resultAlbums: Int = 0       // distinct albums/artists in the library as of this scan
    @Volatile var resultArtists: Int = 0

    // Bumped by LibraryScanService whenever the UI should re-query MediaStore (live progress
    // refreshes + on finish). The Activity polls this instead of the service calling back into a
    // specific Activity closure directly — a Service driving a scan that must keep running whether
    // or not any Activity is even in the foreground can't assume one is listening synchronously.
    val generation = AtomicInteger(0)
    fun bump() { generation.incrementAndGet() }

    fun reset() {
        active = true
        phase = "Scanning folders…"
        visited.set(0); newFound.set(0); tagsScanned.set(0)
        tagsTotal = 0
    }

    fun finish(delta: Int, total: Int, albums: Int = -1, artists: Int = -1) {
        resultDelta = delta
        resultTotal = total
        if (albums >= 0) resultAlbums = albums
        if (artists >= 0) resultArtists = artists
        phase = ""
        active = false
    }
}
