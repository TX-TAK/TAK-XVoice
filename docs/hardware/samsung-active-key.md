# Samsung Active Key (ruggedized tablets & phones)

Samsung ruggedized hardware ships with a programmable **Active Key** — a
physical side button — that TAK-XVoice can use as a Push-To-Talk source
alongside (or instead of) a Bluetooth speakermic.

## Supported chassis

The **Samsung Active Key PTT** settings row appears **only** on hardware that actually has
the key. XV recognizes:

| Family            | `Build.MODEL` prefix      |
| ----------------- | ------------------------- |
| Galaxy Tab Active5 | `SM-X300` / `SM-X306` / `SM-X308` |
| Galaxy Tab Active4 Pro | `SM-T630` / `SM-T636` |
| Galaxy Tab Active3 | `SM-T570` / `SM-T575` / `SM-T577` |
| Galaxy XCover7    | `SM-G556`                 |
| Galaxy XCover6 Pro | `SM-G736`                |

On any other device the row is hidden and this feature is a no-op.

## The two keys

- **Active Key (side)** — keyCode `1015`. This is the PTT source.
- **Top / Emergency key** — keyCode `1079`. **Not** wired to PTT or the
  emergency alert today; it keeps its Samsung-configured behavior.

## Setup — assign the key to XV

Samsung's own setting is the single on/off switch. Once the key is
assigned to XV it works as PTT in the foreground, with ATAK backgrounded,
and with the screen off and locked. This uses Samsung's built-in "control
the key with an app" mapping. It needs no accessibility service, no Knox
SDK and no Knox license.

1. In XV settings, tap **Samsung Active Key PTT**. XV opens Samsung's
   Active button screen. (You can also go there yourself: *Settings →
   Advanced features → Active button*.)
2. Turn on **Use Active button with app** and pick **Texas TAK-XVoice**.
3. Press and hold the key — XV now receives every press and release,
   whatever is in the foreground.

XV has no toggle of its own. The row shows **Assigned** when Samsung's
setting points at XV and **Set up** otherwise. Turn the Samsung setting
off to give the key back to Samsung.

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

- **Row missing:** the device model isn't in the supported list above,
  or `Build.MANUFACTURER` isn't `samsung`.
- **Key does nothing in XV:** the key isn't assigned to XV (the XV row
  shows **Set up**). Tap the row and confirm **Use Active button with
  app** is on and set to XV.
- **XV isn't offered in Samsung's picker:** the firmware may not offer
  this mapping to third-party apps, or an older XV build without the
  manifest entries is installed.
- **Nothing happens at all:** confirm Samsung's Active button isn't set
  to a "launch app" press or press-and-hold action, which swallows the
  key press.
- **Seeing whether reports arrive:** each report XV receives is logged
  under the tag `XvSamsungHardKeyRx` (`adb logcat -s XvSamsungHardKeyRx`).
  Reports are dropped, with a log line, when no voice session is running.
