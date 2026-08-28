package com.miku.systemui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * MikuOS navigation layer (this device's stock SystemUI draws NO nav bar / status bar, so
 * this accessibility service IS the navigation). Pixel-10 gesture model, Miku-themed:
 *
 *  • Edge back (both edges, 24dp strips): indicator emerges after 16dp of inward travel,
 *    follows the finger's Y, rubber-bands to 36dp, commits at 32dp (haptic + pop),
 *    un-commits below 22dp, cancels beyond ~55° vertical. Touches that turn out to be
 *    taps / vertical scrolls / long-presses are REPLAYED into the app on release via
 *    dispatchGesture (the a11y MotionEventInjector cancels injected input while a real
 *    finger is still moving, so live pass-through is impossible — replay-on-lift is the
 *    closest achievable behaviour; the strip is toggled NOT_TOUCHABLE during the replay).
 *  • Home pill (132x24dp zone, bottom centre): swipe up 24dp / fling = HOME; swipe up
 *    48dp then pause 150ms = Miku Recents; horizontal 32dp = quick switch (right = previous
 *    app, left = forward again). Pill stretches/lifts with the drag and pops on trigger.
 *  • Top strip (20dp, fully transparent — the status bar lives in the launcher): 24dp
 *    downward drag opens MikuShadeActivity with the drag offset; anything else is replayed.
 *  • Power: framework assistant path owns long-press (see frameworkOwnsPowerLongPress).
 */
class MikuNotificationShadeService : AccessibilityService() {

    companion object {
        private const val TAG = "MikuNav"
        const val ACTION_TRIGGER_BACK = "com.miku.systemui.action.TRIGGER_BACK"
        const val ACTION_DEBUG_QUICK_SWITCH = "com.miku.systemui.action.DEBUG_QUICK_SWITCH"
        const val ACTION_DEBUG_RECENTS = "com.miku.systemui.action.DEBUG_RECENTS"
        const val ACTION_DEBUG_HOME = "com.miku.systemui.action.DEBUG_HOME"
        const val ACTION_DEBUG_BACK = "com.miku.systemui.action.DEBUG_BACK"

        // Gesture geometry (dp)
        const val POWER_HOLD_MS = 450L
        const val EDGE_STRIP_DP = 24
        const val EDGE_CLAIM_DP = 16f
        const val EDGE_COMMIT_DP = 32f
        const val EDGE_UNCOMMIT_DP = 22f
        const val EDGE_MAX_DP = 36f
        const val EDGE_VERTICAL_INTENT_PX = 20f
        const val EDGE_MAX_ANGLE_TAN = 1.428f      // tan(55°)
        const val TOP_STRIP_DP = 20
        const val SHADE_PULL_DP = 24f
        const val PILL_ZONE_W_DP = 132
        const val PILL_ZONE_H_DP = 30
        const val PILL_W_DP = 104f
        const val PILL_H_DP = 4f
        const val HOME_DP = 24f
        const val HOME_FLING_PX_S = 900f
        const val RECENTS_DP = 48f
        const val RECENTS_HOLD_MS = 150L
        const val RECENTS_HOLD_MAX_PX_S = 150f
        const val QUICK_SWITCH_DP = 32f
        const val QUICK_SWITCH_MAX_DY_DP = 16f
        const val QUICK_SWITCH_SESSION_MS = 2500L
        const val REPLAY_MAX_MS = 600L
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workThread = HandlerThread("miku-nav-work").apply { start() }
    private val workHandler = Handler(workThread.looper)
    private var overlaysAdded = false

    private var leftEdgeView: EdgeBackView? = null
    private var rightEdgeView: EdgeBackView? = null
    private var topStripView: ShadePullView? = null
    private var pillView: HomePillView? = null
    private var leftParams: WindowManager.LayoutParams? = null
    private var rightParams: WindowManager.LayoutParams? = null
    private var topParams: WindowManager.LayoutParams? = null

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // ------------------------------------------------------------------ broadcasts

    /** Now-Playing HUD (track-change pop-over drawn in our overlay layer). */
    private var trackHud: MikuTrackHud? = null
    /** Right-edge volume bar overlay — visible over 3rd-party apps (launcher HUD is launcher-only). */
    private var volumeHud: MikuVolumeHud? = null

    /** Album accent bled ≈25% into the nav chrome (pill glow, back capsule), animated 400ms. */
    @Volatile private var navTeal = MikuAccent.TEAL
    @Volatile private var navTealBright = MikuAccent.TEAL_BRIGHT
    private var accentAnim: ValueAnimator? = null
    private var accentJob: kotlinx.coroutines.Job? = null
    private val accentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private fun startAccentObserver() {
        MikuAccent.observe(this)
        accentJob?.cancel()
        accentJob = accentScope.launch {
            MikuAccent.accent.collect { a ->
                val toTeal = MikuAccent.tealTinted(a, 0.25f); val toBright = MikuAccent.tealBrightTinted(a, 0.25f)
                val fromTeal = navTeal; val fromBright = navTealBright
                accentAnim?.cancel()
                accentAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = MikuPowerProfile.ms(400).toLong()
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        navTeal = MikuAccent.mix(fromTeal, toTeal, f); navTealBright = MikuAccent.mix(fromBright, toBright, f)
                        pillView?.invalidate(); leftEdgeView?.invalidate(); rightEdgeView?.invalidate()
                    }
                    start()
                }
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                MikuTrackHud.ACTION_TRACK_CHANGED, MikuTrackHud.ACTION_DEBUG -> {
                    val a = intent.getIntExtra("accent", 0); val a2 = intent.getIntExtra("accent2", 0)
                    if (a != 0) MikuAccent.push(a, a2)
                    trackHud?.show(MikuTrackHud.Payload.from(intent))
                }
                "android.media.VOLUME_CHANGED_ACTION", "android.media.RINGER_MODE_CHANGED" ->
                    volumeHud?.onVolumeChanged()
                ACTION_TRIGGER_BACK, ACTION_DEBUG_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_DEBUG_HOME -> triggerHome()
                ACTION_DEBUG_RECENTS -> openRecents()
                ACTION_DEBUG_QUICK_SWITCH -> {
                    val dir = if (intent.getStringExtra("dir") == "next") -1 else 1
                    quickSwitch(dir)
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        trackHud = MikuTrackHud(this, windowManager) { dragPx -> openShadeActivity(dragPx) }
        volumeHud = MikuVolumeHud(this, windowManager)
        MikuPowerProfile.observe(this)
        startAccentObserver()
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_TRIGGER_BACK); addAction(ACTION_DEBUG_BACK)
                addAction(ACTION_DEBUG_HOME); addAction(ACTION_DEBUG_RECENTS)
                addAction(ACTION_DEBUG_QUICK_SWITCH)
                addAction(MikuTrackHud.ACTION_TRACK_CHANGED); addAction(MikuTrackHud.ACTION_DEBUG)
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.RINGER_MODE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) { Log.w(TAG, "receiver register failed: $t") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addOverlays()
        MikuNotificationStore.ensureEnabled(this)
        suppressStockShade()
        Log.i(TAG, "MikuNav connected; overlays=$overlaysAdded canGestures=" +
            (serviceInfo?.capabilities?.and(android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0))
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        trackHud?.destroy(); trackHud = null
        volumeHud?.destroy(); volumeHud = null
        accentJob?.cancel(); accentAnim?.cancel()
        removeOverlays()
        try { workThread.quitSafely() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /**
     * The AOSP-stock SystemUI we ship still draws its own status bar + pull-down shade, which
     * competed with the Miku shade ("both stock and our swipe-from-top"). Block the STOCK shade
     * expansion + notification chrome via StatusBarManager so ONLY the Miku a11y top-strip shade
     * responds. Our shade is a separate overlay, unaffected by DISABLE_EXPAND.
     */
    private fun suppressStockShade() {
        runCatching {
            val sb = getSystemService(Context.STATUS_BAR_SERVICE)
            // DISABLE_EXPAND(0x00010000) | DISABLE_NOTIFICATION_ICONS(0x00020000) |
            // DISABLE_NOTIFICATION_ALERTS(0x00040000)
            val flags = 0x00010000 or 0x00020000 or 0x00040000
            sb.javaClass.getMethod("disable", Int::class.javaPrimitiveType).invoke(sb, flags)
            Log.i(TAG, "stock shade suppressed (DISABLE_EXPAND)")
        }.onFailure { Log.w(TAG, "suppressStockShade failed: $it") }
    }

    override fun onInterrupt() {}

    private fun overlayParams(w: Int, h: Int, gravity: Int, noLimits: Boolean = true) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (noLimits) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    /**
     * Adds the four navigation overlays. There must be exactly ONE of each on screen — most of
     * all the home pill — so any previous set is torn down first (onServiceConnected can fire
     * again after a rebind on the SAME instance, which would otherwise stack a second pill).
     */
    private fun addOverlays() {
        if (overlaysAdded || leftEdgeView != null || rightEdgeView != null ||
            topStripView != null || pillView != null) {
            Log.i(TAG, "addOverlays: tearing down previous overlay set first")
            removeOverlays()
        }
        val dm = resources.displayMetrics
        val topPx = dp(TOP_STRIP_DP.toFloat()).toInt()
        val pillZoneH = dp(PILL_ZONE_H_DP.toFloat()).toInt()
        val edgeW = dp(EDGE_STRIP_DP.toFloat()).toInt()
        val edgeH = (dm.heightPixels - topPx - pillZoneH).coerceAtLeast(dp(200f).toInt())

        leftEdgeView = EdgeBackView(this, isLeft = true).also { v ->
            leftParams = overlayParams(edgeW, edgeH, Gravity.TOP or Gravity.START).apply { y = topPx }
            runCatching { windowManager.addView(v, leftParams) }.onFailure { Log.w(TAG, "left edge add: $it") }
        }
        rightEdgeView = EdgeBackView(this, isLeft = false).also { v ->
            rightParams = overlayParams(edgeW, edgeH, Gravity.TOP or Gravity.END).apply { y = topPx }
            runCatching { windowManager.addView(v, rightParams) }.onFailure { Log.w(TAG, "right edge add: $it") }
        }
        topStripView = ShadePullView(this).also { v ->
            topParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, topPx, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            runCatching { windowManager.addView(v, topParams) }.onFailure { Log.w(TAG, "top strip add: $it") }
        }
        pillView = HomePillView(this).also { v ->
            // Full-width bottom strip so the home swipe is catchable across the whole bottom edge,
            // not only the centre 132dp (3rd-party apps like Spotify own the centre-bottom). The pill
            // is still DRAWN centred (onDraw uses width/2), so it looks identical.
            val p = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, pillZoneH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            runCatching { windowManager.addView(v, p) }.onFailure { Log.w(TAG, "pill add: $it") }
        }
        overlaysAdded = true
    }

    private fun removeOverlays() {
        listOf(leftEdgeView, rightEdgeView, topStripView, pillView).forEach { v ->
            v?.let { runCatching { windowManager.removeView(it) } }
        }
        leftEdgeView = null; rightEdgeView = null; topStripView = null; pillView = null
        overlaysAdded = false
    }

    // ------------------------------------------------------------------ actions

    private fun launchOwnActivity(cls: Class<*>, requestCode: Int, extras: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, cls).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            extras?.invoke(this)
        }
        try {
            // PendingIntent.send() keeps the launch on OUR background-activity-launch grant.
            PendingIntent.getActivity(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).send()
        } catch (_: Throwable) {
            try { startActivity(intent) } catch (t: Throwable) { Log.w(TAG, "launch ${cls.simpleName}: $t") }
        }
    }

    private fun openShadeActivity(dragOffsetPx: Int = -1) =
        launchOwnActivity(MikuShadeActivity::class.java, 0) {
            if (dragOffsetPx >= 0) putExtra(MikuShadeActivity.EXTRA_DRAG_OFFSET_PX, dragOffsetPx)
        }

    private var lastPowerMenuOpenMs = 0L
    private fun openPowerMenuActivity() {
        // Debounce: the framework assist path (if it ever fires) and our hold timer may both
        // land within the same hold — one launch per second is plenty.
        val now = SystemClock.uptimeMillis()
        if (now - lastPowerMenuOpenMs < 1000L) return
        lastPowerMenuOpenMs = now
        launchOwnActivity(MikuPowerMenuActivity::class.java, 1)
    }

    fun openRecents() = launchOwnActivity(MikuRecentsActivity::class.java, 2)

    fun triggerHome() {
        // Pixel: leaving an app for home flashes the handle; going home from home does nothing.
        workHandler.post {
            val fromApp = runCatching { !MikuTaskStack.isHomeOnTop(this) }.getOrDefault(false)
            if (fromApp) mainHandler.post { pillView?.flashGlow() }
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // Quick-switch session: the MRU list is captured on the first swipe and walked for 2.5 s.
    private var qsList: List<MikuTaskStack.Entry> = emptyList()
    private var qsIndex = -1          // index of the task we are standing on (-1 = home/unknown)
    private var qsFromHome = false
    private var qsTime = 0L

    /** dir = +1 → previous app (swipe right); dir = -1 → forward again (swipe left). */
    fun quickSwitch(dir: Int) {
        workHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (now - qsTime > QUICK_SWITCH_SESSION_MS || qsList.isEmpty()) {
                qsList = MikuTaskStack.recents(this, 8)
                val top = MikuTaskStack.topTask(this)
                qsFromHome = MikuTaskStack.isHomeOnTop(this)
                qsIndex = qsList.indexOfFirst { it.taskId == top?.first }
            }
            val target = qsIndex + dir
            val ok = when {
                target in qsList.indices -> MikuTaskStack.switchTo(this, qsList[target].taskId).also { if (it) qsIndex = target }
                target < 0 && qsFromHome && qsIndex >= 0 -> { mainHandler.post { triggerHome() }; qsIndex = -1; true }
                else -> false
            }
            qsTime = now
            Log.i(TAG, "quickSwitch dir=$dir target=$target ok=$ok list=${qsList.map { it.pkg }}")
            if (!ok) mainHandler.post { pillView?.rejectBounce() }
        }
    }

    // ------------------------------------------------------------------ replay-on-lift

    data class TouchPt(val x: Float, val y: Float, val t: Long)

    /**
     * True while a replayed touch is being injected. The injected stroke is a REAL screen touch,
     * so once the strip goes touchable again it can land back on us and replay itself — the
     * strips ignore new ACTION_DOWNs while this is set (observed: one tap replayed 3x).
     */
    @Volatile private var replayInFlight = false

    private fun setTouchable(view: View?, params: WindowManager.LayoutParams?, touchable: Boolean) {
        if (view == null || params == null) return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val next = if (touchable) params.flags and f.inv() else params.flags or f
        if (next == params.flags) return
        params.flags = next
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * Re-inject a touch that a strip swallowed but that was NOT a gesture (tap, vertical
     * scroll, long press) so the app underneath still receives it. Real timing is kept up to
     * REPLAY_MAX_MS; longer drags are time-compressed.
     */
    private fun replayTouch(points: List<TouchPt>, view: View?, params: WindowManager.LayoutParams?) {
        if (points.isEmpty()) return
        val first = points.first(); val last = points.last()
        val path = Path().apply { moveTo(first.x, first.y) }
        var travelled = 0f
        var px = first.x; var py = first.y
        for (p in points.drop(1)) {
            val d = abs(p.x - px) + abs(p.y - py)
            if (d < 1f) continue
            path.lineTo(p.x, p.y); travelled += d; px = p.x; py = p.y
        }
        val realDur = (last.t - first.t).coerceAtLeast(1L)
        val dur = if (travelled < 8f) realDur.coerceIn(20L, REPLAY_MAX_MS) else realDur.coerceIn(1L, REPLAY_MAX_MS)
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()
        } catch (t: Throwable) { Log.w(TAG, "replay build: $t"); return }
        Log.i(TAG, "replay: pts=${points.size} travelled=${travelled.toInt()} dur=$dur")
        replayInFlight = true
        setTouchable(view, params, false)
        val restore = Runnable { setTouchable(view, params, true); replayInFlight = false }
        val ok = try {
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { mainHandler.post(restore) }
                override fun onCancelled(g: GestureDescription?) { mainHandler.post(restore) }
            }, mainHandler)
        } catch (t: Throwable) { Log.w(TAG, "replay dispatch: $t"); false }
        if (!ok) restore.run() else mainHandler.postDelayed(restore, dur + 400L)   // safety net
    }

    // ------------------------------------------------------------------ keys / events

    private var powerDownTimestamp = 0L

    /**
     * True when the framework itself routes long-press-power to MikuPowerMenuActivity
     * (Settings.Global power_button_long_press = 5 = LONG_PRESS_POWER_ASSISTANT with
     * Settings.Secure assistant = our component — seeded by the ROM rc). In that mode this
     * accessibility key filter must stand down, otherwise one hold fires the modal twice.
     */
    private fun frameworkOwnsPowerLongPress(): Boolean = try {
        android.provider.Settings.Global.getInt(contentResolver, "power_button_long_press", 0) == 5 &&
            (android.provider.Settings.Secure.getString(contentResolver, "assistant") ?: "")
                .startsWith("com.miku.systemui/")
    } catch (_: Throwable) { false }

    /** Hold timer for the power key: fires the modal at [POWER_HOLD_MS] unless an UP cancels it. */
    private val powerHoldRunnable = Runnable {
        powerDownTimestamp = 0L
        // Fire only while the display is still interactive: from the Miku lockscreen a power
        // DOWN turns the panel off immediately, and a modal over a dark panel is worse than none.
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return@Runnable
        Log.i(TAG, "power hold ${POWER_HOLD_MS}ms → power menu")
        openPowerMenuActivity()
    }

    /**
     * Power long-press → MikuPowerMenuActivity, owned HERE regardless of the framework path.
     * Settings power_button_long_press=5 + assistant=our component is still kept: it makes the
     * framework mark the key handled (no sleep on release) and play the long-press haptic. But
     * on this HiBy SystemUI the assist hand-off (StatusBar.startAssist → AssistManager) dies
     * silently — the user gets the vibration and no modal (measured 2026-08-25: powerLongPress
     * behavior=5 logged, no activity start ever follows). So the a11y key filter times the hold
     * itself. Timer-based on DOWN (not "second DOWN > 450ms"): key repeats are synthesized inside
     * InputDispatcher AFTER the a11y input filter, so this service only ever sees DOWN and UP.
     * Double-fire is harmless: the activity is singleInstance and [openPowerMenuActivity] has a
     * debounce.
     */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode == android.view.KeyEvent.KEYCODE_POWER) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || powerDownTimestamp == 0L) {
                    powerDownTimestamp = System.currentTimeMillis()
                    mainHandler.removeCallbacks(powerHoldRunnable)
                    mainHandler.postDelayed(powerHoldRunnable, POWER_HOLD_MS)
                }
            } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                powerDownTimestamp = 0L
                mainHandler.removeCallbacks(powerHoldRunnable)
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = event.className?.toString() ?: ""
            val pkg = event.packageName?.toString() ?: ""
            if (cls.contains("GlobalActions", ignoreCase = true) ||
                cls.contains("PowerDialog", ignoreCase = true) ||
                cls.contains("ShutdownActivity", ignoreCase = true) ||
                (pkg.contains("android") && cls.contains("GlobalActionsDialog", ignoreCase = true))) {
                try {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Throwable) {}
                openPowerMenuActivity()
                return
            }
            if (cls.contains("NotificationShade", ignoreCase = true) ||
                cls.contains("QuickSettings", ignoreCase = true) ||
                cls.contains("StatusBarWindow", ignoreCase = true)) {
                openShadeActivity()
            }
        }
    }

    // ================================================================== EDGE BACK

    private enum class Mode { UNDECIDED, BACK, FORWARD, CANCELLED }

    inner class EdgeBackView(context: Context, private val isLeft: Boolean) : View(context) {
        private var mode = Mode.UNDECIDED
        private var startX = 0f; private var startY = 0f
        private var curX = 0f; private var curY = 0f
        private var committed = false
        private var down = false
        private val points = ArrayList<TouchPt>(64)

        // Visual state
        private var visProgress = 0f          // 0..1 extension of the indicator
        private var visY = 0f
        private var pop = 1f
        private var retractAnim: ValueAnimator? = null
        private var popAnim: ValueAnimator? = null
        private var commitFade = 0f          // chevron fade-in on commit (120ms), independent of travel
        private var fadeAnim: ValueAnimator? = null

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF39C5BB.toInt() }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x5500F5D4 }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(12f)
            setShadowLayer(dp(4f), 0f, 0f, 0xAA00F5D4.toInt())
        }
        private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = 0xFFF0FDFB.toInt()
        }
        private val rect = RectF()
        private val chevron = Path()

        private fun inward(x: Float) = if (isLeft) (x - startX) else (startX - x)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (visProgress <= 0.01f) return
            val h = dp(34f)
            val maxW = dp(EDGE_MAX_DP)
            val w = dp(8f) + maxW * visProgress
            val cy = visY.coerceIn(h / 2f, height - h / 2f)
            val cx = if (isLeft) w / 2f - dp(6f) else width - w / 2f + dp(6f)
            canvas.save()
            canvas.scale(pop, pop, cx, cy)
            // Capsule that emerges from the edge ("D" shape)
            val left = if (isLeft) -dp(20f) else width - w
            val right = if (isLeft) w else width + dp(20f)
            // soft Miku glow (radial shader — stays hardware-accelerated)
            val gcx = if (isLeft) right - dp(12f) else left + dp(12f)
            val gb = navTealBright and 0x00FFFFFF
            glowPaint.shader = android.graphics.RadialGradient(gcx, cy, h * 1.6f,
                intArrayOf(gb or ((0x80 * visProgress).toInt() shl 24), gb), null, android.graphics.Shader.TileMode.CLAMP)
            rect.set(gcx - h * 1.6f, cy - h * 1.6f, gcx + h * 1.6f, cy + h * 1.6f)
            canvas.drawOval(rect, glowPaint)
            rect.set(left, cy - h / 2f, right, cy + h / 2f)
            fillPaint.color = navTeal
            fillPaint.alpha = (0xF2 * (0.55f + 0.45f * visProgress)).toInt()
            canvas.drawRoundRect(rect, h, h, fillPaint)
            // Heart glyph + chevron pointing inward (chevron fades in as we approach commit)
            val gx = if (isLeft) (right - dp(12f)) else (left + dp(12f))
            glyphPaint.alpha = (255 * visProgress).toInt()
            canvas.drawText("♥", gx, cy + dp(4.5f), glyphPaint)
            val chevAlpha = maxOf(((visProgress - 0.55f) / 0.45f).coerceIn(0f, 1f), commitFade)
            if (chevAlpha > 0f) {
                chevronPaint.alpha = (255 * chevAlpha).toInt()
                val s = dp(4f)
                val ax = if (isLeft) gx + dp(9f) else gx - dp(9f)
                chevron.reset()
                if (isLeft) { chevron.moveTo(ax - s, cy - s); chevron.lineTo(ax, cy); chevron.lineTo(ax - s, cy + s) }
                else { chevron.moveTo(ax + s, cy - s); chevron.lineTo(ax, cy); chevron.lineTo(ax + s, cy + s) }
                canvas.drawPath(chevron, chevronPaint)
            }
            canvas.restore()
        }

        private fun animateRetract() {
            retractAnim?.cancel()
            fadeAnim?.cancel()
            retractAnim = ValueAnimator.ofFloat(visProgress, 0f).apply {
                duration = MikuMotion.ms(160).toLong(); interpolator = MikuMotion.decel()
                addUpdateListener { visProgress = it.animatedValue as Float; commitFade *= 0.85f; invalidate() }
                start()
            }
        }

        /** Commit: 1.2x pop with overshoot (Pixel back-arrow "click"). */
        private fun animatePop() {
            popAnim?.cancel()
            popAnim = ValueAnimator.ofFloat(1f, 1.2f, 1f).apply {
                duration = MikuMotion.ms(160).toLong(); interpolator = MikuMotion.overshoot(1.5f)
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        /** Chevron fades in over 120ms at commit and out over 100ms on un-commit. */
        private fun animateCommitFade(on: Boolean) {
            fadeAnim?.cancel()
            fadeAnim = ValueAnimator.ofFloat(commitFade, if (on) 1f else 0f).apply {
                duration = MikuMotion.ms(if (on) 120 else 100).toLong(); interpolator = MikuMotion.decel()
                addUpdateListener { commitFade = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (replayInFlight) return false          // our own injected touch
                    retractAnim?.cancel()
                    down = true; committed = false; mode = Mode.UNDECIDED
                    startX = event.rawX; startY = event.rawY; curX = startX; curY = startY
                    points.clear(); points += TouchPt(event.rawX, event.rawY, now)
                    visY = event.y; visProgress = 0f; pop = 1f; commitFade = 0f
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return false
                    curX = event.rawX; curY = event.rawY
                    if (points.size < 400) points += TouchPt(curX, curY, now)
                    val dxIn = inward(curX)
                    val dy = abs(curY - startY)
                    when (mode) {
                        Mode.UNDECIDED -> {
                            if (dxIn >= dp(EDGE_CLAIM_DP)) {
                                mode = if (dy <= dxIn * EDGE_MAX_ANGLE_TAN) Mode.BACK else Mode.FORWARD
                                if (mode == Mode.BACK) MikuHaptics.tick(this)     // light: gesture claimed
                            } else if (dy >= EDGE_VERTICAL_INTENT_PX) {
                                mode = Mode.FORWARD          // vertical intent first → belongs to the app
                            }
                        }
                        Mode.BACK -> {
                            if (!committed && dy > dxIn * EDGE_MAX_ANGLE_TAN) {
                                mode = Mode.CANCELLED; animateRetract(); return true
                            }
                            val wasCommitted = committed
                            if (!committed && dxIn >= dp(EDGE_COMMIT_DP)) committed = true
                            else if (committed && dxIn < dp(EDGE_UNCOMMIT_DP)) committed = false
                            if (committed && !wasCommitted) {
                                MikuHaptics.confirm(this)                          // stronger: commit
                                animatePop(); animateCommitFade(true)
                            } else if (!committed && wasCommitted) {
                                MikuHaptics.tick(this)
                                animateCommitFade(false)
                            }
                        }
                        else -> {}
                    }
                    if (mode == Mode.BACK) {
                        val max = dp(EDGE_MAX_DP)
                        val raw = dxIn.coerceAtLeast(0f)
                        // rubber-band past the max: only 15% of the extra travel shows
                        val eff = if (raw <= max) raw else max + (raw - max) * 0.15f
                        visProgress = (eff / max).coerceIn(0f, 1.12f)
                        visY += (event.y - visY) * 0.3f                 // Pixel: loose 0.3 lerp follow
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!down) return false
                    down = false
                    points += TouchPt(event.rawX, event.rawY, now)
                    Log.i(TAG, "edge up: left=$isLeft mode=$mode committed=$committed dxIn=${inward(event.rawX).toInt()} pts=${points.size}")
                    when (mode) {
                        Mode.BACK -> {
                            if (committed) {
                                MikuHaptics.confirm(this)
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                            animateRetract()
                        }
                        Mode.UNDECIDED, Mode.FORWARD -> {
                            val (v, p) = if (isLeft) leftEdgeView to leftParams else rightEdgeView to rightParams
                            replayTouch(ArrayList(points), v, p)
                        }
                        Mode.CANCELLED -> {}
                    }
                    committed = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    down = false; committed = false; mode = Mode.CANCELLED
                    animateRetract()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    // ================================================================== TOP SHADE PULL

    inner class ShadePullView(context: Context) : View(context) {
        private var down = false
        private var opened = false
        private var startX = 0f; private var startY = 0f
        private var velocity: VelocityTracker? = null
        private val points = ArrayList<TouchPt>(64)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (replayInFlight) return false          // our own injected touch
                    down = true; opened = false
                    startX = event.rawX; startY = event.rawY
                    velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    points.clear(); points += TouchPt(startX, startY, now)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return false
                    velocity?.addMovement(event)
                    if (points.size < 400) points += TouchPt(event.rawX, event.rawY, now)
                    val dy = event.rawY - startY
                    if (!opened) {
                        val dx = abs(event.rawX - startX)
                        if (dy >= dp(SHADE_PULL_DP) && dx < dy) {
                            opened = true
                            MikuHaptics.tick(this)
                            MikuShadeDrag.begin(dy)                  // the shade follows this finger 1:1
                            openShadeActivity(dy.toInt())
                        }
                    } else {
                        MikuShadeDrag.move(dy)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!down) return false
                    down = false
                    points += TouchPt(event.rawX, event.rawY, now)
                    velocity?.addMovement(event); velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle(); velocity = null
                    if (opened) MikuShadeDrag.release(vy)
                    else replayTouch(ArrayList(points), topStripView, topParams)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    down = false
                    velocity?.recycle(); velocity = null
                    if (opened) MikuShadeDrag.release(0f)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    // ================================================================== HOME PILL

    inner class HomePillView(context: Context) : View(context) {
        private var down = false
        private var fired = false
        private var startX = 0f; private var startY = 0f
        private var curX = 0f; private var curY = 0f
        private var velocity: VelocityTracker? = null
        private var stretch = 0f       // 0..1 upward drag progress
        private var shiftX = 0f        // horizontal follow
        private var armed = false      // recents hold armed (pill turns teal)
        private var pop = 1f
        private var flash = 0f         // 90ms glow flash when leaving an app for home
        private var flashAnim: ValueAnimator? = null
        private var claimed = false    // first light tick once the drag is clearly upward
        private var relaxAnim: ValueAnimator? = null
        private var popAnim: ValueAnimator? = null

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xD9FFFFFF.toInt() }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x3800F5D4 }
        private val rect = RectF()

        private val holdCheck = object : Runnable {
            override fun run() {
                if (!down || fired) return
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f; val vy = velocity?.yVelocity ?: 0f
                val speed = abs(vx) + abs(vy)
                val dyUp = startY - curY
                if (dyUp >= dp(RECENTS_DP) && speed < RECENTS_HOLD_MAX_PX_S) {
                    Log.i(TAG, "pill hold -> recents (dyUp=${dyUp.toInt()} speed=${speed.toInt()})")
                    fired = true; armed = true
                    MikuHaptics.pop(this@HomePillView)                      // strong: hold → recents
                    animatePop()
                    openRecents()
                } else {
                    mainHandler.postDelayed(this, 60L)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val pw = dp(PILL_W_DP) * (1f + 0.18f * stretch)
            val ph = dp(PILL_H_DP)
            val lift = dp(12f) * stretch
            val cx = w / 2f + shiftX
            val bottom = h - dp(8f) - lift
            canvas.save()
            canvas.scale(pop, pop, cx, bottom - ph / 2f)
            val glowA = ((if (armed) 0x90 else (0x30 + (0x50 * stretch).toInt())) + (0x60 * flash).toInt()).coerceAtMost(0xF0)
            val gb = navTealBright and 0x00FFFFFF
            glowPaint.shader = android.graphics.LinearGradient(cx - pw / 2f - dp(10f), 0f, cx + pw / 2f + dp(10f), 0f,
                intArrayOf(gb, (glowA shl 24) or gb, gb), null, android.graphics.Shader.TileMode.CLAMP)
            rect.set(cx - pw / 2f - dp(10f), bottom - ph - dp(6f), cx + pw / 2f + dp(10f), bottom + dp(6f))
            canvas.drawRoundRect(rect, ph + dp(6f), ph + dp(6f), glowPaint)
            rect.set(cx - pw / 2f, bottom - ph, cx + pw / 2f, bottom)
            pillPaint.color = if (armed) navTealBright else 0xB3FFFFFF.toInt()
            canvas.drawRoundRect(rect, ph / 2f, ph / 2f, pillPaint)
            canvas.restore()
        }

        private fun animateRelax() {
            relaxAnim?.cancel()
            val s0 = stretch; val x0 = shiftX
            relaxAnim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = MikuMotion.ms(220).toLong(); interpolator = MikuMotion.overshoot(1.2f)
                addUpdateListener { val f = it.animatedValue as Float; stretch = s0 * f; shiftX = x0 * f; invalidate() }
                start()
            }
        }

        private fun animatePop() {
            popAnim?.cancel()
            popAnim = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
                duration = MikuMotion.ms(160).toLong(); interpolator = MikuMotion.overshoot(1.4f)
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        /** Quick 90ms glow flash — played when a HOME gesture leaves a foreground app. */
        fun flashGlow() {
            if (MikuMotion.quiet) return
            flashAnim?.cancel()
            flashAnim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 90
                addUpdateListener { flash = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun rejectBounce() {
            MikuHaptics.reject(this)
            relaxAnim?.cancel()
            relaxAnim = ValueAnimator.ofFloat(0f, 1f, -1f, 0f).apply {
                duration = 260
                addUpdateListener { shiftX = dp(6f) * (it.animatedValue as Float); invalidate() }
                start()
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    relaxAnim?.cancel()
                    down = true; fired = false; armed = false; claimed = false
                    startX = event.rawX; startY = event.rawY; curX = startX; curY = startY
                    velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!down) return false
                    velocity?.addMovement(event)
                    curX = event.rawX; curY = event.rawY
                    val dyUp = (startY - curY).coerceAtLeast(0f)
                    val dx = curX - startX
                    val max = dp(RECENTS_DP)
                    stretch = if (dyUp <= max) dyUp / max else 1f + (dyUp - max) / max * 0.12f
                    shiftX = (dx * 0.5f).coerceIn(-dp(24f), dp(24f))
                    if (!claimed && (dyUp >= dp(8f) || abs(dx) >= dp(8f))) { claimed = true; MikuHaptics.tick(this) }   // light: claimed
                    if (!fired) {
                        mainHandler.removeCallbacks(holdCheck)
                        if (dyUp >= max) mainHandler.postDelayed(holdCheck, RECENTS_HOLD_MS)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!down) return false
                    down = false
                    mainHandler.removeCallbacks(holdCheck)
                    velocity?.addMovement(event)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle(); velocity = null
                    val dyUp = startY - curY
                    val dx = curX - startX
                    val dy = abs(curY - startY)
                    Log.i(TAG, "pill up: dyUp=${dyUp.toInt()} dx=${dx.toInt()} vy=${vy.toInt()} fired=$fired")
                    if (event.actionMasked == MotionEvent.ACTION_UP && !fired) {
                        if (dyUp >= dp(HOME_DP) || (vy < -HOME_FLING_PX_S && dyUp >= dp(12f))) {
                            fired = true
                            MikuHaptics.confirm(this)
                            animatePop()
                            triggerHome()
                        } else if (abs(dx) >= dp(QUICK_SWITCH_DP) && dy < dp(QUICK_SWITCH_MAX_DY_DP)) {
                            fired = true
                            MikuHaptics.confirm(this)
                            animatePop()
                            quickSwitch(if (dx > 0) 1 else -1)
                        }
                    }
                    armed = false
                    animateRelax()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
