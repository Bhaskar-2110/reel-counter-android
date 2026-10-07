package com.bhaskar.reelcounter

/**
 * A short-video app the service watches.
 *
 * The short-video screen is recognised by [shortsViewIds] (exact resource IDs, fast lookup) or
 * [shortsIdHints] (text that appears in the resource ID of a large, visible view; used where
 * the exact ID isn't known). With neither, the whole app is a short-video feed and every
 * full-screen swipe counts. Apps rename these IDs in updates; if counting stops for one app,
 * inspect its short-video screen with Android Studio's Layout Inspector and update the lists.
 */
enum class Platform(
    val label: String,
    val packages: Set<String>,
    val shortsViewIds: List<String> = emptyList(),
    val shortsIdHints: List<String> = emptyList(),
) {
    INSTAGRAM(
        "Instagram Reels",
        setOf("com.instagram.android"),
        shortsViewIds = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_pager",
        ),
    ),
    YOUTUBE(
        "YouTube Shorts",
        setOf("com.google.android.youtube"),
        shortsViewIds = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
        ),
    ),
    SNAPCHAT(
        "Snapchat Spotlight",
        setOf("com.snapchat.android"),
        shortsIdHints = listOf("spotlight"),
    ),
    TIKTOK("TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
    MOJ("Moj", setOf("in.mohalla.video")),
    JOSH("Josh", setOf("com.eterno.shortvideos"));

    /** True when everything in the app is a short-video feed. */
    val isWholeApp: Boolean get() = shortsViewIds.isEmpty() && shortsIdHints.isEmpty()

    companion object {
        fun forPackage(pkg: String): Platform? = entries.firstOrNull { pkg in it.packages }
    }
}
