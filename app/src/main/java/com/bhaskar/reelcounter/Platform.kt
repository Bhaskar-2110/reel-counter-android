package com.bhaskar.reelcounter

/**
 * A short-video app the service watches.
 *
 * The short-video screen is recognised by [shortsViewIds] (exact resource IDs, fast lookup) or
 * [shortsIdHints] (text that appears in the resource ID of a large, visible view; used where
 * the exact ID isn't known). With neither, the whole app is a short-video feed and every
 * full-screen swipe counts. Apps rename these IDs in updates; if counting stops for one app,
 * inspect its short-video screen with Android Studio's Layout Inspector and update the lists.
 *
 * Most apps report scrolling when you swipe to the next video. Where an app doesn't, set
 * [progressBarId]: the ID of the video's progress bar, whose description ("29 seconds of
 * 59 seconds") shows when a new video starts; or [signatureRootId]: a view whose text (the
 * creator and sound line) changes with each video.
 */
enum class Platform(
    val label: String,
    val shortName: String,
    val packages: Set<String>,
    val shortsViewIds: List<String> = emptyList(),
    val shortsIdHints: List<String> = emptyList(),
    val progressBarId: String? = null,
    val signatureRootId: String? = null,
) {
    INSTAGRAM(
        "Instagram Reels",
        "Instagram",
        setOf("com.instagram.android"),
        shortsViewIds = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_viewer_pager",
        ),
    ),
    YOUTUBE(
        "YouTube Shorts",
        "YouTube",
        setOf("com.google.android.youtube"),
        shortsViewIds = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
        ),
        // YouTube's Shorts pager doesn't report scrolling, so new Shorts are spotted from the
        // progress bar instead.
        progressBarId = "com.google.android.youtube:id/reel_time_bar",
    ),
    SNAPCHAT(
        "Snapchat Spotlight",
        "Snapchat",
        setOf("com.snapchat.android"),
        // Most of Snapchat's view names are scrambled, but these two aren't (checked on a
        // OnePlus with Snapchat, 8 Oct 2026). Snapchat sends no scroll events, so a new video is
        // spotted when the creator/sound text in the viewer changes.
        shortsViewIds = listOf("com.snapchat.android:id/spotlight_container"),
        signatureRootId = "com.snapchat.android:id/opera_viewer",
    ),
    TIKTOK("TikTok", "TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
    MOJ("Moj", "Moj", setOf("in.mohalla.video")),
    JOSH("Josh", "Josh", setOf("com.eterno.shortvideos"));

    /** True when everything in the app is a short-video feed. */
    val isWholeApp: Boolean get() = shortsViewIds.isEmpty() && shortsIdHints.isEmpty()

    companion object {
        fun forPackage(pkg: String): Platform? = entries.firstOrNull { pkg in it.packages }
    }
}
