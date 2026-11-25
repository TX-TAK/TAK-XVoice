# Samsung Active Key (ruggedized tablets & phones)

Samsung ruggedized hardware ships with a programmable **Active Key** — a
physical side button — that TAK-XVoice can use as a Push-To-Talk source
alongside (or instead of) a Bluetooth speakermic.

## Supported chassis

The Active Key PTT toggle appears **only** on hardware that actually has
the key. XV recognizes:

| Family            | `Build.MODEL` prefix      |
| ----------------- | ------------------------- |
| Galaxy Tab Active5 | `SM-X300` / `SM-X306` / `SM-X308` |
| Galaxy Tab Active4 Pro | `SM-T630` / `SM-T636` |
| Galaxy Tab Active3 | `SM-T570` / `SM-T575` / `SM-T577` |
| Galaxy XCover7    | `SM-G556`                 |
| Galaxy XCover6 Pro | `SM-G736`                |

On any other device the toggle is hidden and this feature is a no-op.

## The two keys

- **Active Key (side)** — keyCode `1015`. This is the PTT source.
- **Top / Emergency key** — keyCode `1079`. **Not** wired to PTT or the
  emergency alert today; it keeps its Samsung-configured behavior.

## Foreground PTT — works out of the box

While ATAK holds the top window, the Active Key arrives as an ordinary
`KeyEvent` (down on press, up on release) and XV keys PTT directly. No
setup beyond enabling the **Active Key** row in XV settings.

## Background and locked-screen PTT — assign the key to XV

To keep the key working while ATAK is backgrounded, or with the screen off
and locked, assign the key to XV in Samsung's own settings. This uses
Samsung's built-in "control the key with an app" mapping. It needs no
accessibility service, no Knox SDK and no Knox license.

1. In XV settings, tap **Use Active Key in the background and when
   locked**. XV opens Samsung's Active button screen. (You can also go
   there yourself: *Settings → Advanced features → Active button*.)
2. Turn on **Use Active button with app** and pick **Texas TAK-XVoice**.
3. Press and hold the key — XV now receives every press and release,
   whatever is in the foreground.

The XV settings row is read-only. It reflects whether Samsung's setting
currently points at XV; it is not a persisted XV preference. Turn the
Samsung setting off to give the key back to Samsung.

### How it works

Samsung addresses a `HARD_KEY_REPORT` broadcast (keyCode `1015`, report
type `1` = press, `2` = release) to **only** the app the operator
assigned the key to. XV is offered in that picker because its manifest
declares:

- a `<meta-data>` entry `com.samsung.android.knox.intent.action.HARD_KEY_PRESS`
  set to `true`, and
- an exported receiver (`SamsungHardKeyReceiver`) for the
  `com.samsung.android.knox.intent.action.HARD_KEY_REPORT` action.

Without both, the app never appears in Samsung's picker and receives
nothing.

> Samsung's "Use while screen locked" toggle is a separate setting for
> using the key to *open* an app over the lock screen. It was off during
> validation and PTT still worked with the screen locked.

## On-device validation

- **Galaxy Tab Active5 (SM-X308U, Android 16)** — validated end to end:
  Active Key PTT transmits in the foreground, with ATAK backgrounded, and
  with the screen off and locked, with the key assigned to XV and no
  accessibility service installed.

Other listed chassis are gated on by model prefix but have not yet been
validated end-to-end — see the curated-hardware policy in the top-level
README.

## Troubleshooting

- **Toggle missing:** the device model isn't in the supported list above,
  or `Build.MANUFACTURER` isn't `samsung`.
- **Works foreground, not backgrounded or locked:** the key isn't
  assigned to XV. Open Samsung's Active button screen (the XV row does
  this) and confirm **Use Active button with app** is on and set to XV.
- **XV isn't offered in Samsung's picker:** the firmware may not offer
  this mapping to third-party apps, or an older XV build without the
  manifest entries is installed.
- **Nothing happens at all:** confirm Samsung's Active button isn't set
  to a "launch app" press or press-and-hold action, which swallows the
  key press.
- **Seeing whether reports arrive:** each report XV receives is logged
  under the tag `XvSamsungHardKeyRx` (`adb logcat -s XvSamsungHardKeyRx`).
  Reports are dropped, with a log line, when no voice session is running.
