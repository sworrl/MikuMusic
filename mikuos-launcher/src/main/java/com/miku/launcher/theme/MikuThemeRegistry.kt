package com.miku.launcher.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MikuOS Theming Engine — data model + runtime-selectable theme registry.
 *
 * This file is intentionally self-contained (pure data + a persisted StateFlow) and is NOT
 * wired into MikuLauncherActivity.kt. Wiring — reading [MikuThemeRegistry.selectedTheme],
 * applying [MikuTheme.wallpaperAsset] as the home/lock background, and recoloring UI chrome
 * from [MikuAccentPalette] — is a separate follow-up pass, done by whoever owns the activity.
 *
 * See docs/10_miku_theme_catalog.md for the full asset catalog, source-image inventory, and
 * per-theme suggested palettes this registry was seeded from.
 *
 * ### Asset resolution model
 * Themes deliberately do NOT hold compile-time `@DrawableRes Int` references, because the
 * underlying bitmaps (miku-assets/themes/<id>/wallpaper.*) are not yet copied into any
 * module's res/drawable-nodpi at the time this registry was authored. Instead each theme
 * carries the *drawable resource name it expects to exist* as a String, and callers resolve
 * it at runtime with [MikuThemeRegistry.resolveDrawableRes] (falls back to the Default
 * theme's drawable, then to 0 / "not found", if the name hasn't been wired into res/ yet).
 * This keeps the registry compilable today and forward-compatible once the real drawables
 * are added under names like `theme_default_wallpaper`, `theme_cyber_stage_wallpaper`, etc.
 */

/** Accent color set an applied theme pushes across launcher/lockscreen/settings chrome. */
data class MikuAccentPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color
)

/**
 * A single installable/selectable MikuOS visual theme.
 *
 * @param id stable machine id, persisted to SharedPreferences — never rename once shipped.
 * @param displayName human-readable name shown in the theme picker.
 * @param description one-line blurb for the picker UI.
 * @param wallpaperDrawableName expected res/drawable(-nodpi) resource name for the home
 *   wallpaper (see [MikuThemeRegistry.resolveDrawableRes]).
 * @param lockscreenDrawableName expected resource name for the lockscreen wallpaper. Many
 *   themes reuse the home wallpaper; pass the same name in that case.
 * @param accentPalette the recolor tokens this theme pushes when applied.
 * @param keyboardThemeRef optional id of a matching IME/keyboard theme (see
 *   docs/10_miku_theme_catalog.md §4 for AOSP LatinIME feasibility notes). Null = no matching
 *   keyboard skin exists yet.
 * @param hasRealArt false = the wallpaper is a stand-in/placeholder, not the art the theme is
 *   actually named after (e.g. "halloween" and "beach_icecream" ship with no dedicated art
 *   yet — see the catalog). UI should badge these as "preview" until real art lands.
 */
data class MikuTheme(
    val id: String,
    val displayName: String,
    val description: String,
    val wallpaperDrawableName: String,
    val lockscreenDrawableName: String,
    val accentPalette: MikuAccentPalette,
    val keyboardThemeRef: String? = null,
    val hasRealArt: Boolean = true
)

/**
 * Built-in MikuOS theme catalog + the currently-selected theme as a persisted [StateFlow].
 *
 * Usage (once wired):
 * ```
 * MikuThemeRegistry.init(context)
 * val theme by MikuThemeRegistry.selectedTheme.collectAsState()
 * val wallpaperRes = MikuThemeRegistry.resolveDrawableRes(context, theme.wallpaperDrawableName)
 * ```
 */
object MikuThemeRegistry {

    private const val PREFS_NAME = "mikuos_theme_prefs"
    private const val KEY_SELECTED_THEME_ID = "selected_theme_id"

    const val DEFAULT_THEME_ID = "default"

    /** Default = the beach/ocean Miku wallpaper already shipping as R.drawable.miku_wallpaper
     * (720x1280 — the exact M500 panel resolution). This is the "classic" wallpaper-picker
     * entry that already exists in MikuLauncherActivity.kt's `wallpapers` list; this theme
     * entry just gives it a place in the theme system too. */
    val Default = MikuTheme(
        id = "default",
        displayName = "Default (Beach)",
        description = "Miku on the shoreline at sunrise — the shipping default.",
        wallpaperDrawableName = "miku_wallpaper", // already exists: R.drawable.miku_wallpaper
        lockscreenDrawableName = "miku_wallpaper",
        accentPalette = MikuAccentPalette(
            primary = Color(0xFF39C5BB),   // Miku teal
            secondary = Color(0xFF7ED6E0), // sea-foam
            tertiary = Color(0xFFFF9E80),  // coral (her hair-tie / warm accents)
            background = Color(0xFFEAF6F6)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** NO dedicated Halloween art exists anywhere in the repo as of this cataloging pass
     * (see docs/10_miku_theme_catalog.md). Wallpaper name points at a not-yet-created asset;
     * [resolveDrawableRes] will fall back to Default's art until `theme_halloween_wallpaper`
     * is actually added to res/. hasRealArt = false so the picker can badge it "coming soon". */
    val Halloween = MikuTheme(
        id = "halloween",
        displayName = "Halloween",
        description = "Jack-o-lantern Miku, witch hat + candy palette. Art not sourced yet.",
        wallpaperDrawableName = "theme_halloween_wallpaper", // TODO: does not exist yet
        lockscreenDrawableName = "theme_halloween_wallpaper",
        accentPalette = MikuAccentPalette(
            primary = Color(0xFFFF7518),   // pumpkin orange
            secondary = Color(0xFF6A3FA0), // witch violet
            tertiary = Color(0xFF39C5BB),  // keep a sliver of Miku teal so it still reads "Miku"
            background = Color(0xFF140A10)
        ),
        keyboardThemeRef = null,
        hasRealArt = false
    )

    /** Requested as "Miku eating ice cream on a beach". No render depicting ice cream exists;
     * the closest asset in the repo is the same beach/ocean art used by Default (she's on the
     * beach, just not holding ice cream). Ships pointed at that art as a functional stand-in —
     * flagged hasRealArt = false so it's visibly distinct from Default in the picker until a
     * real "ice cream" render is sourced or commissioned. */
    val BeachIcecream = MikuTheme(
        id = "beach_icecream",
        displayName = "Beach (Ice Cream)",
        description = "Miku with ice cream on the beach. Placeholder: reuses Default's beach art — no ice-cream render exists yet.",
        wallpaperDrawableName = "theme_beach_icecream_wallpaper", // distinct render art now bundled
        lockscreenDrawableName = "theme_beach_icecream_wallpaper",
        accentPalette = MikuAccentPalette(
            primary = Color(0xFF39C5BB),
            secondary = Color(0xFFFFB6C1), // strawberry ice-cream pink
            tertiary = Color(0xFFFFF3D6),  // vanilla cream
            background = Color(0xFFEAF6F6)
        ),
        keyboardThemeRef = null,
        hasRealArt = false
    )

    /** Real dedicated art: miku-assets/themes/cyber_stage/wallpaper.webp
     * (source: miku-player-kotlin app/mikuos-launcher res/drawable/miku_banner_cyber_stage.webp,
     * a.k.a. miku_renders/banners/banner_02_waifu_cyber_stage_16x9.jpg — Miku on a neon concert
     * stage holding the cassette-UI DAP). Picked as a bonus built-in theme because it already
     * has finished, on-brand art and a natural teal/magenta accent pair. */
    val CyberStage = MikuTheme(
        id = "cyber_stage",
        displayName = "Cyber Stage",
        description = "Neon concert-stage Miku with the cassette-deck DAP UI.",
        wallpaperDrawableName = "miku_banner_cyber_stage", // already exists in res/drawable
        lockscreenDrawableName = "miku_banner_cyber_stage",
        accentPalette = MikuAccentPalette(
            primary = Color(0xFF39C5BB),
            secondary = Color(0xFFE03177), // neon magenta
            tertiary = Color(0xFF2F5DFF),  // stage laser blue
            background = Color(0xFF060B18)
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** Real dedicated art: miku-assets/themes/cozy_cafe/wallpaper.jpg
     * (source: miku_renders/09_waifu_cozy_cafe.jpg — Miku in a sweater in a warm vinyl/lo-fi
     * lounge, cassette-UI DAP in hand). Second bonus built-in theme; warm palette contrasts
     * nicely with Cyber Stage. */
    val CozyCafe = MikuTheme(
        id = "cozy_cafe",
        displayName = "Cozy Cafe",
        description = "Lo-fi vinyl lounge Miku in a knit sweater, mug of coffee in frame.",
        wallpaperDrawableName = "theme_cozy_cafe_wallpaper", // TODO: not yet copied into res/
        lockscreenDrawableName = "theme_cozy_cafe_wallpaper",
        accentPalette = MikuAccentPalette(
            primary = Color(0xFFD89B4A),   // warm amber / tungsten bulb glow
            secondary = Color(0xFF4FB3AC), // muted teal
            tertiary = Color(0xFFF0DFC0),  // cream
            background = Color(0xFF2A1B12) // dark wood
        ),
        keyboardThemeRef = null,
        hasRealArt = true
    )

    /** All built-in themes, in picker display order. */
    val builtIns: List<MikuTheme> = listOf(Default, Halloween, BeachIcecream, CyberStage, CozyCafe)

    private val _selectedTheme = MutableStateFlow(Default)
    /** Currently-applied theme. Collect this as Compose state once wired into the UI. */
    val selectedTheme: StateFlow<MikuTheme> = _selectedTheme.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Call once (e.g. from Application.onCreate or the first Activity that needs it) to
     * restore the persisted theme selection. Safe to call multiple times. */
    fun init(context: Context) {
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        val savedId = p.getString(KEY_SELECTED_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        _selectedTheme.value = byId(savedId) ?: Default
    }

    /** Look up a built-in theme by id, or null if unknown. */
    fun byId(id: String): MikuTheme? = builtIns.firstOrNull { it.id == id }

    /** Apply + persist a theme selection by id. No-ops if [themeId] is unknown. */
    fun selectTheme(context: Context, themeId: String) {
        val theme = byId(themeId) ?: return
        val p = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        p.edit().putString(KEY_SELECTED_THEME_ID, themeId).apply()
        _selectedTheme.value = theme
    }

    /**
     * Resolve a theme's drawable resource *name* to an actual `@DrawableRes` id at runtime,
     * via [android.content.res.Resources.getIdentifier]. Falls back to [Default]'s wallpaper
     * name, then to 0, if neither name has been added to res/drawable(-nodpi) yet — so callers
     * never crash on a theme whose art hasn't been wired in.
     *
     * getIdentifier() is a reflection-ish lookup and intentionally only used here, once per
     * theme switch — not in a hot path.
     */
    fun resolveDrawableRes(context: Context, drawableName: String): Int {
        val res = context.resources
        val pkg = context.packageName
        val direct = res.getIdentifier(drawableName, "drawable", pkg)
        if (direct != 0) return direct
        if (drawableName != Default.wallpaperDrawableName) {
            val fallback = res.getIdentifier(Default.wallpaperDrawableName, "drawable", pkg)
            if (fallback != 0) return fallback
        }
        return 0
    }
}
