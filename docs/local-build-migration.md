# Moving from the Play Store build to a local `debugProd` build

The local build installs as `au.com.shiftyjelly.pocketcasts.debug` and can run next to the
Play Store app. Data is not shared between the two, so the library and settings come across
through your Pocket Casts account instead.

## 1. Build and install `debugProd`

Use `debugProd`, not `debug`: `debug` points at the staging servers (`*.pocketcasts.net`)
and your account does not exist there.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:installDebugProd
```

Or assemble and sideload manually:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebugProd
adb install -r app/build/outputs/apk/debugProd/app-debugProd.apk
```

## 2. Sign in on the new build

Open "Pocket Casts (debug)" and sign in with the same email and password as the Play Store app.

"Continue with Google" is hidden in a local build because the Google client ID lives in the
secrets the repo does not ship. If the account was created through Google, sign in with the
email/password pair instead (use the password reset flow on pocketcasts.com if it never had one).

## 3. Let the first full sync run

The first sync after sign-in is a full sync: it pulls podcasts, folders, filters, bookmarks,
Up Next, played and archived state, ratings and Plus membership from the server. Leave the app
in the foreground on the Podcasts screen until it settles, then pull to refresh once.

## 4. Restore the rest by hand

Everything below is device-local, so it does not sync and has to be set again:

- Appearance: theme, app icon, tinted icons, dynamic colours
- Notification settings and headphone controls
- Storage: download location, auto-download, auto-archive, cleanup
- Playback defaults that are not in the synced set (playback speed per podcast, trim silence,
  volume boost, skip on episode end behaviour)
- Downloads themselves (the new package has its own storage directory)
- Home screen widgets, Android Auto / Wear layout

## 5. Keep the Play app until you are happy

Both builds can stay signed in. Only use one of them day to day, otherwise the two devices
fight over Up Next and played state through the same account.

Plus: membership comes from the account and works. Buying or changing a subscription inside the
local build will fail, because Play Billing only serves apps signed by the Play key - use the
Play Store for purchase changes.

## Fallback: OPML

If signing in is not possible, export from the Play app (Settings -> Import & export OPML),
then import the same file in the local build. That carries subscriptions only - no played
state, Up Next or settings.
