# Reel Counter

![Reel Counter: counts every Reel and Short you swipe, and stops you at your daily limit](docs/social-preview.png)

An Android app that counts the Reels and Shorts you swipe through, shows the count in a small
animated "island" at the top of the screen, and stops you at a daily limit you choose.

<img src="docs/island-on-shorts.png" alt="The reel island showing 14 over a YouTube Short" width="300" align="right">

## Features

- **One count across apps.** Instagram Reels, YouTube Shorts, Snapchat Spotlight, TikTok, Moj
  and Josh all add to the same daily number.
- **The reel island.** A clear-glass circle at the top of the screen with animated eyes and
  today's count. It grows in when you open Reels or Shorts and shrinks away when you leave.
- **Eyes with moods.**
  - *Curious* while the count is low: the eyes glance around and blink, and look more worried
    as you get closer to your limit.
  - *Electrocuted* when you scroll fast (3 or more reels within 6 seconds): spiral pupils and
    lightning.
  - *Angry* at your limit: red eyes, brows down, a 💢 and a shake.
- **Daily limit.** When you reach it, Reels, Shorts and Spotlight are blocked until midnight. The
  rest of each app keeps working. TikTok, Moj and Josh are closed, since they are only short
  videos.
- **Pause before opening.** The first 5 social app opens each day are free. After that, opening one
  shows a pause screen that waits 3, 5, 10, 15, 20, 25 and then 30 seconds before you can
  continue, with a button to close the app instead.
- **Screen time.** Time spent and opens per social media app (16 apps, including WhatsApp,
  Facebook, X and Reddit), read from the same Android usage data that Digital Wellbeing uses.
- **History.** A 7-day chart of reels watched.

Everything stays on your phone. The app has no internet permission and no account.

## Install

1. Build the APK (see [Build](#build)) or download it from the
   [Releases](https://github.com/Bhaskar-2110/reel-counter-android/releases) page when one is
   published.
2. Copy it to your phone and open it. Allow **Install unknown apps** for the app you opened it
   from (Files, Chrome, Drive…). If Play Protect warns you, tap **More details → Install anyway**.

Requires Android 8.0 or newer.

## Set up

| Step | Where | Why |
|---|---|---|
| Turn on **Reel Counter** in Accessibility settings | App → *Open Accessibility settings* → Downloaded apps | Detects the Reels/Shorts screen and your swipes |
| Allow notifications | Prompt on first launch | The ongoing "N reels today" notification keeps counting running |
| Allow background running | App → *Allow background running* | Stops battery saving from switching counting off |
| Allow usage access (optional) | App → *Allow usage access* → Permit usage access | Shows screen time per app |

On Android 13 and newer, the Accessibility switch is greyed out for apps installed outside the
Play Store. Open **Settings → Apps → Reel Counter → ⋮ → Allow restricted settings**, then turn
it on again.

On **OnePlus / OxygenOS**, also set **Settings → Apps → Reel Counter → Battery** to
**Unrestricted** and lock the app in Recent apps, or the system may stop counting after a while.

## Permissions and privacy

| Permission | Used for |
|---|---|
| Accessibility service | Knowing when a Reels/Shorts/Spotlight screen is open, counting swipes, showing the island and the pause and limit screens. The service only reacts to screen changes and scrolling. It does not read messages or what you type, and it ignores events from apps it doesn't support. |
| Usage access | Screen time and opens per social app. |
| Foreground service and notifications | The ongoing notification that keeps the app alive. |
| Ignore battery optimizations | Asking Android not to stop the app in the background. |

Counts and settings are stored in the app's private storage on the phone. Nothing is uploaded.

## Supported apps

| App | Counted | How it's recognised |
|---|---|---|
| Instagram Reels | ✅ | Reels viewer screen |
| YouTube Shorts | ✅ | Shorts player screen |
| Snapchat Spotlight | ⚠️ Not verified yet | Best guess at the Spotlight screen |
| TikTok, Moj, Josh | ✅ | Whole app (it's all short videos) |

Apps sometimes rename their internal screens in updates. If one stops being counted, see
[If an app stops being counted](#if-an-app-stops-being-counted).

## Build

Needs JDK 17 or 21 and the Android SDK (compile SDK 35).

```bash
git clone https://github.com/Bhaskar-2110/reel-counter-android.git
cd reel-counter-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press **Run ▶** with your phone connected.

## How it works

A swipe is counted when a full-screen feed stops scrolling on a new item. For Instagram, YouTube
and Snapchat, the service first checks that the Reels/Shorts/Spotlight player is on screen, so
scrolling the normal feed isn't counted. Scrolling inside comment sheets and share menus is
ignored because those views don't fill the screen.

| File | Role |
|---|---|
| [`ReelCounterService.kt`](app/src/main/java/com/bhaskar/reelcounter/ReelCounterService.kt) | Accessibility service: recognises short-video screens, counts swipes, shows the island and the pause and limit screens |
| [`Platform.kt`](app/src/main/java/com/bhaskar/reelcounter/Platform.kt) | The supported apps and how each one's short-video screen is recognised |
| [`CountStore.kt`](app/src/main/java/com/bhaskar/reelcounter/CountStore.kt) | Daily counts, opens, limit and settings |
| [`KeepAliveService.kt`](app/src/main/java/com/bhaskar/reelcounter/KeepAliveService.kt) | Foreground service with the ongoing notification |
| [`UsageRepository.kt`](app/src/main/java/com/bhaskar/reelcounter/UsageRepository.kt) | Screen time and opens from `UsageStatsManager` |
| [`Eyes.kt`](app/src/main/java/com/bhaskar/reelcounter/Eyes.kt) | The animated eyes and their moods |
| [`IslandView.kt`](app/src/main/java/com/bhaskar/reelcounter/IslandView.kt) | The glass island with the eyes and the rolling count |
| [`MainActivity.kt`](app/src/main/java/com/bhaskar/reelcounter/MainActivity.kt) | The app's main screen |

On Android 12 and newer, a separate window behind the island blurs the video under it for the
frosted-glass look. Where window blur is off (for example in battery saver), the island uses a
slightly darker glass instead.

## If an app stops being counted

1. Open that app's Reels or Shorts screen with your phone connected to Android Studio.
2. Use **Tools → Layout Inspector** to find the resource ID of the full-screen video pager.
3. Add it to that app's `shortsViewIds` in [`Platform.kt`](app/src/main/java/com/bhaskar/reelcounter/Platform.kt).

To support a new app, add an entry to `Platform.kt`.

## Known limitations

- The first video you open in a session isn't counted until you swipe to the next one.
- Snapchat Spotlight detection hasn't been tested on a real device yet.
- Opening an app from the Recent apps screen counts as a new open for the pause screen.
