# Poll Alarm

[![Build](https://github.com/meirhalachmi/poll-alarm/actions/workflows/build.yml/badge.svg)](https://github.com/meirhalachmi/poll-alarm/actions/workflows/build.yml)

An Android app that rings when a poll is opened in a WhatsApp group you follow: a full-screen
"there is a poll in group X" over the lock screen, with one button that takes you to the chat.

WhatsApp groups (school classes, clubs, building committees) often use polls for things that fill
up fast: sign-ups, slots, who brings what. A poll is just one more message in a busy group, and
it is easy to see it after it no longer matters. The app's UI is in Hebrew.

## How it works

WhatsApp has no API for this, and the app does not log in, run a modified client or read
WhatsApp's data. It uses the one thing WhatsApp already publishes on the phone: **its
home-screen widget**.

```
WhatsApp ──updates──▶ its home-screen widget ──hosted by──▶ WidgetWatcher
                                                              │ reads the rows
                                                              ▼
                                         WidgetRows.parse → NewRows → Polls.check
                                                              │ a new 📊 row in a followed chat
                                                              ▼
                                                  PollAlarm (full-screen, alarm stream)
```

- **Hosting the widget.** The app is an `AppWidgetHost`, like a launcher. The user binds
  WhatsApp's widget to it once (an Android confirmation), and Android then delivers the widget's
  updates to the app. The host view never has a window, so it is measured and laid out by hand
  before each read, and every row of the widget's list is fetched through the list's adapter.
- **Parsing rows** (`core/WidgetRows`). The widget lists unread messages: chat, time, and
  `Sender: text` for groups, with the sender in bold. A poll is a row starting with `📊`.
- **What is new** (`core/NewRows`). The same unread message is read many times. A row is new the
  first time its exact content (and its occurrence, for repeated lines) is seen.
- **The ring** (`PollAlarm`, `AlertActivity`). A full-screen notification on the alarm stream,
  looping for a minute; a vibrate-only notification re-posted every few seconds for a paired
  watch; and a quiet copy that stays until the chat is opened. "Open" clicks the chat's row in
  the hosted widget, which is WhatsApp's own intent for that chat, and falls back to opening
  WhatsApp if nothing came up.
- **Staying alive** (`WatchService`). A foreground service with a minimum-priority notification.
  A notification listener would avoid that notification, but Play Protect refuses to install a
  sideloaded app that declares one.
- **Knowing when it breaks** (`HealthMonitor`). The widget is not an API and can change with any
  WhatsApp update. A read that finds no list, or rows of which none parse, or a widget that was
  unbound, raises a notification and lets the user send a report with the widget's view tree.
- **Updates** (`Updater`). The app checks this repository's latest release every hour (and on
  demand), verifies the APK's SHA-256 against GitHub's asset digest, and installs it with
  `PackageInstaller`.

Nothing read from WhatsApp leaves the phone. The only network traffic is the update check.

### Limits

- Only unread messages reach the widget: a poll read on another device, or while its chat is
  open, does not ring.
- Opening the exact chat depends on Android letting the widget's click through; when it does
  not, WhatsApp opens on its chat list.
- If WhatsApp stopped marking polls with `📊`, rows would still parse and nothing would ring.

## Install

1. Download the latest `poll-alarm-<n>.apk` from [Releases](https://github.com/meirhalachmi/poll-alarm/releases) and install it.
2. Open the app and follow the steps on its screen: connect WhatsApp's widget, allow
   notifications and full-screen alerts, and exempt the app from battery optimisation.
3. Tick the groups to follow. A group with nothing unread is not listed yet; type its name.
4. "ניסיון צלצול" shows what an alert looks like.

**With a Galaxy Watch:** Samsung can mute phone notifications on the phone while the watch is
worn, which silences the ring. To keep it ringing, turn that off in the Galaxy Wearable app, under
the watch's notification settings (the option's name varies between versions). The watch itself
gets a vibration every two seconds while the poll rings.

## Build

Requires JDK 17 and the Android SDK (platform 36).

```sh
./gradlew test             # unit tests (core/ is plain Kotlin)
./gradlew assembleRelease  # APK
```

CI (`.github/workflows/build.yml`) runs the tests on every push and, on `main`, publishes a
signed APK as a release. The signing key comes from the `SIGNING_KEYSTORE_BASE64` and
`SIGNING_PASSWORD` repository secrets; without them the build is signed with the debug key and
nothing is published.

## Project layout

```
app/src/main/java/org/halachmi/polls/
  core/            widget row parsing, new-row tracking, poll rules (no Android, unit-tested)
  WidgetWatcher    hosts and reads WhatsApp's widget
  WatchService     keeps it running
  PollAlarm        the alert notifications
  AlertActivity    the full-screen alert
  HealthMonitor    detects when the widget is no longer understood
  Updater          self-update from GitHub releases
  MainActivity     setup and settings
```

## License

[MIT](LICENSE)
