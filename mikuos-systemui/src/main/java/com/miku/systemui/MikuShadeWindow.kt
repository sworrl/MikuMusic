package com.miku.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The notification shade, as a PERSISTENT WINDOW instead of an Activity.
 *
 * WHY. The shade used to be [MikuShadeActivity], created fresh on every pull off the top strip. A
 * warm launch measured 524 to 861ms on this device, with the first frame of the drag landing in the
 * middle of activity creation and first composition. A Pixel's shade is a window that already
 * exists; the pull only makes it visible. This is that.
 *
 * The window is added once when the navigation service connects, composed once, and then only
 * shown and hidden. A pull costs a visibility change and a layout, not a process-level launch.
 *
 * COMPOSE OUTSIDE AN ACTIVITY. A ComposeView needs three ViewTree owners that an Activity normally
 * supplies: a LifecycleOwner, a SavedStateRegistryOwner and a ViewModelStoreOwner. This class is all
 * three. The lifecycle is driven RESUMED while shown and STARTED while hidden, so composition stays
 * alive between pulls (that is the entire point) but animations and LaunchedEffect loops still know
 * when nobody is looking.
 *
 * FOCUS. The window is FLAG_NOT_FOCUSABLE while hidden so it never eats input meant for the app
 * underneath. Opening clears that flag so the back key reaches the shade; closing sets it again.
 */
class MikuShadeWindow(
    private val ctx: Context,
    private val windowManager: WindowManager,
    private val onOpenSettings: () -> Unit,
    private val onOpenPower: () -> Unit
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {

    companion object { private const val TAG = "MikuShadeWindow" }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    /**
     * The shade composable uses BackHandler, and BackHandler needs an OnBackPressedDispatcherOwner
     * that an Activity would have supplied. Without it the composition throws
     * IllegalStateException the first time it runs, which takes the accessibility service down and
     * with it the device's ONLY navigation. Found the hard way, 2026-09-18.
     */
    override val onBackPressedDispatcher = OnBackPressedDispatcher { close() }

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Visible to the user right now. */
    @Volatile var isOpen = false
        private set

    // Compose-side state the window drives. Held here so open()/close() can poke it from the
    // service without recreating anything.
    private var openSerial by mutableIntStateOf(0)
    private var closeSerial by mutableIntStateOf(0)
    private var startDragPx by mutableIntStateOf(-1)
    private var startExpanded by mutableStateOf(false)

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        if (view != null) return
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(this@MikuShadeWindow)
            setViewTreeSavedStateRegistryOwner(this@MikuShadeWindow)
            setViewTreeViewModelStoreOwner(this@MikuShadeWindow)
            setViewTreeOnBackPressedDispatcherOwner(this@MikuShadeWindow)
            visibility = View.GONE
            setContent { ShadeContent() }
            // Back dismisses the shade. The window only has focus while open, so this never
            // steals the key from the app underneath.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && isOpen) {
                    close(); true
                } else false
            }
            isFocusableInTouchMode = true
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            hiddenFlags(),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(v, p) }
            .onSuccess {
                view = v; params = p
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
                Log.i(TAG, "shade window attached (composed once, shown on demand)")
            }
            .onFailure { Log.w(TAG, "shade window add failed: $it") }
    }

    private fun hiddenFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun shownFlags() =
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** Show the shade. [dragOffsetPx] >= 0 means a finger is already pulling it down. */
    fun open(dragOffsetPx: Int = -1, expanded: Boolean = false) {
        val v = view ?: run { attach(); view } ?: return
        startDragPx = dragOffsetPx
        startExpanded = expanded
        openSerial++
        isOpen = true
        params?.let { p ->
            p.flags = shownFlags()
            runCatching { windowManager.updateViewLayout(v, p) }
        }
        v.visibility = View.VISIBLE
        v.requestFocus()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun close() {
        val v = view ?: return
        if (!isOpen) return
        isOpen = false
        closeSerial++
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        v.visibility = View.GONE
        params?.let { p ->
            p.flags = hiddenFlags()
            runCatching { windowManager.updateViewLayout(v, p) }
        }
    }

    fun detach() {
        view?.let { v -> runCatching { windowManager.removeView(v) } }
        view = null; params = null; isOpen = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /**
     * The shade's composition. Kept in this class rather than in MikuNotificationShadeView so the
     * view itself stays a plain composable that either an Activity or this window can host.
     */
    @androidx.compose.runtime.Composable
    private fun ShadeContent() {
        val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
        var panelH by remember { mutableFloatStateOf(screenH * 0.94f) }
        val ty = remember { Animatable(0f) }
        var panelHpx by remember { mutableIntStateOf(0) }

        // Finger-follow, same contract MikuShadeActivity had: the top strip keeps the pointer for
        // the whole gesture and publishes it through MikuShadeDrag.
        LaunchedEffect(openSerial) {
            if (openSerial == 0) return@LaunchedEffect
            val from = startDragPx
            if (from >= 0) {
                ty.snapTo(-(panelH - from).coerceAtLeast(0f))
                val serial = MikuShadeDrag.state.value.serial
                var wasDown = true
                MikuShadeDrag.state.collect { s ->
                    if (s.serial != serial) return@collect
                    if (s.fingerDown) {
                        wasDown = true
                        ty.snapTo((s.dragPx - panelH).coerceAtMost(0f))
                    } else if (wasDown) {
                        wasDown = false
                        val vel = s.velocityPxS
                        val revealed = ((panelH + ty.value) / panelH).coerceIn(0f, 1f)
                        if (vel < -900f || (revealed < 0.45f && vel < 600f)) {
                            ty.animateTo(-panelH - 40f, tween(MikuMotion.ms(150), easing = FastOutSlowInEasing))
                            close()
                        } else {
                            ty.animateTo(0f, MikuMotion.settle(), initialVelocity = vel.coerceIn(-4000f, 4000f))
                        }
                    }
                }
            } else {
                ty.snapTo(-panelH)
                ty.animateTo(0f, MikuMotion.settle())
            }
        }

        LaunchedEffect(closeSerial) {
            if (closeSerial == 0) return@LaunchedEffect
            ty.snapTo(-panelH - 40f)
        }

        MikuNotificationShadeView(
            onDismiss = { close() },
            onOpenSettings = onOpenSettings,
            onOpenPower = onOpenPower,
            startExpanded = startExpanded,
            followFinger = startDragPx >= 0,
            panelOffsetPx = { ty.value },
            revealProgress = { ((panelH + ty.value) / panelH).coerceIn(0f, 1f) },
            onPanelHeight = { h -> if (h > 0) { panelH = h.toFloat(); panelHpx = h } }
        )
    }
}
