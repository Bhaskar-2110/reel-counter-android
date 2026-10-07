# Reel Counter (Android)

Counts the short videos you swipe through each day in Instagram Reels, YouTube Shorts,
Snapchat Spotlight, TikTok, Moj and Josh, as one combined count, and helps you cut back.

- **Reel island**: a small clear-glass circle at the top of the screen with animated eyes and
  today's count. The eyes are curious while the count is low, get electrocuted when you scroll
  fast (3+ reels in 6 seconds), and turn angry at your daily limit.
- **Daily limit**: once reached, Reels/Shorts/Spotlight are blocked until tomorrow; the rest of
  each app keeps working. TikTok, Moj and Josh are closed, since they are only short videos.
- **Pause on open**: after 5 free opens a day, opening a social app shows a pause screen that
  waits 3, 5, 10, 15, 20, 25 and then 30 seconds before you can continue.
- **Screen time**: per-app time and opens for social media apps, from Android's usage data (the
  same data Digital Wellbeing uses).
- **History**: a 7-day chart of reels watched.

Everything stays on the phone.

## Run it

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and choose this folder. Let Gradle sync.
3. Plug in your phone with USB debugging on (Settings → About phone → tap Build number 7 times,
   then Developer options → USB debugging), pick it in the device menu and press **Run ▶**.
   To get an APK file instead: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or run
   `./gradlew assembleDebug` (needs JDK 17 or 21).

## Set it up on the phone

1. Open Reel Counter and tap **Open Accessibility settings**, then turn on **Reel Counter**
   under *Downloaded apps*. On Android 13+ the switch may be greyed out for apps not installed
   from the Play Store: go to Settings → Apps → Reel Counter → ⋮ → **Allow restricted
   settings**, then try again.
2. Allow notifications. The ongoing "N reels today" notification keeps counting alive.
3. Tap **Allow background running**. On OnePlus, also set Battery to **Unrestricted** and lock
   the app in Recent apps.
4. For screen time, tap **Allow usage access** and turn on **Permit usage access**.

## How it works

| File | Role |
|---|---|
| `ReelCounterService.kt` | Accessibility service: detects Reels/Shorts screens and swipes, counts, shows the island, pause and block screens |
| `KeepAliveService.kt` | Foreground service with the ongoing notification, so battery savers don't stop counting |
| `Platform.kt` | The short-video apps and how each one's Reels/Shorts screen is recognised |
| `CountStore.kt` | Daily counts, opens and settings (SharedPreferences) |
| `UsageRepository.kt` | Screen time and opens from `UsageStatsManager` |
| `Eyes.kt`, `IslandView.kt`, `EyesView.kt` | The animated eyes and the glass island |

A swipe is counted when a full-screen feed stops scrolling on a new item. For Instagram,
YouTube and Snapchat the service first checks that the Reels/Shorts/Spotlight player is on
screen, so scrolling the normal feed isn't counted.

## If an app stops being counted

Apps rename their internal view IDs in some updates. Open the Reels or Shorts screen, use
**Tools → Layout Inspector** in Android Studio to find the ID of the full-screen pager, and
add it to that app's `shortsViewIds` in `Platform.kt`. Snapchat Spotlight is recognised by a
best guess (`shortsIdHints`) and hasn't been verified on a device yet.
