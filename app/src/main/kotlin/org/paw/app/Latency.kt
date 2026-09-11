package org.paw.app

import android.content.Context

// Round-trip latency compensation: recorded takes are shifted left on the
// timeline by this many frames.
//
// The interface path uses the loopback rig's measurement, a real acoustic
// round trip and more trustworthy than what the streams estimate about
// themselves. Every other route uses what the open streams report, learned on
// first use and remembered per route: Bluetooth output alone runs 150-300 ms
// against USB's 10, so one global constant would place a Bluetooth overdub a
// fifth of a second late.
object Latency {

    /** Frames to shift a take left by, for the route it was recorded through. */
    fun frames(context: Context, routeKey: String, reported: Int): Long {
        val prefs = AppPrefs.of(context)
        if (isMeasuredPath(routeKey)) return prefs.latencyFrames.toLong()
        prefs.routeLatencyFrames(routeKey)?.let { return it.toLong() }
        if (reported >= 0) return reported.toLong()
        return prefs.latencyFrames.toLong()
    }

    /**
     * Remember what a route reports, once it reports anything. Called off the
     * record path so a take finalized before the stream had a timestamp still
     * leaves the next one a usable number.
     */
    fun learn(context: Context, routeKey: String, reported: Int) {
        if (reported < 0 || routeKey.isEmpty() || isMeasuredPath(routeKey)) return
        AppPrefs.of(context).setRouteLatencyFrames(routeKey, reported)
    }

    /** The loopback rig only ever measured an interface round trip. */
    private fun isMeasuredPath(routeKey: String) = routeKey.startsWith("usb:")
}
