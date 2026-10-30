# Charge Limit

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that keeps the battery of a wall tablet between two levels. A tablet that never leaves its charger sits at 100% for months, and the battery swells. This plugin stops charging at an upper level, lets the battery run down to a lower one and then charges again.

It replaces a separate charge limiter app and its boot script. The limits show up in Home Assistant as selects, so you can change them from a dashboard.

## Requirements

- Kiosk Satellite 2026.10.44 or newer.
- [Shizuku](https://shizuku.rikka.app/) running as root (uid 0), with Kiosk Satellite allowed to use it under **Settings > Device > Shizuku**. Writing the kernel's charging switch needs root. A Shizuku started over ADB runs as the shell user and cannot write it; the plugin page then says so.
- A device whose kernel has a charging switch the plugin knows. See [Supported devices](#supported-devices).

## Installation

Use the button below to open the plugin in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=syphernl/ks-charge-limit)

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/syphernl/ks-charge-limit` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Enable the plugin from its entry row once it is installed.

## How it works

Once a minute the plugin reads the battery level from `/sys/class/power_supply/battery/capacity` through Shizuku.

- At or above **Stop charging at** it writes the "stop" value to the charging switch.
- At or below **Resume charging at** it writes the "charge" value.
- In between it leaves the switch alone, so the battery runs down from the upper level to the lower one before it charges again.

The defaults are 85% and 50%. **Resume charging at** always stays below **Stop charging at**: raising one past the other moves the other along by 5%.

Some switches cannot be read back, so on its first check after a start the plugin also stops charging when the level is between the two limits. From then on it knows the state of the switch.

Raising **Stop charging at** while charging is paused starts charging again right away, up to the new level.

Turning **Limit charging** off writes the "charge" value once and then leaves the switch alone. A start with the limit already off does the same, in case an earlier session left charging stopped.

When a switch exists but refuses the write, the plugin tries the next one in the list.

**Turn the limit off before you disable or remove the plugin.** Kiosk Satellite revokes a plugin's access before it stops it, so the plugin cannot release the switch on its way out. If charging was stopped at that moment, it stays stopped until something writes the switch again or the device reboots. The same holds while Kiosk Satellite itself is not running. A low battery alert in Home Assistant catches both.

## Home Assistant

With ESPHome and native entities enabled in Kiosk Satellite, the plugin adds:

| Entity | What it is |
| --- | --- |
| `switch.<kiosk>_charge_limit_charge_limit` | Limit charging on or off |
| `select.<kiosk>_charge_limit_stop_charging_at` | Upper level, 50% to 100% in steps of 5 |
| `select.<kiosk>_charge_limit_resume_charging_at` | Lower level, 20% to 95% in steps of 5 |
| `binary_sensor.<kiosk>_charge_limit_charging_paused` | On while the plugin holds charging off |
| `sensor.<kiosk>_charge_limit_status` | A short status, such as `Paused at 85%, resumes at 50%` |

A change from Home Assistant is saved in the plugin settings, so it survives a restart.

## Supported devices

The plugin uses the first of these files that exists:

| File | Charge | Stop | Seen on |
| --- | --- | --- | --- |
| `battery/device/Charging_Enable` | 1 | 0 | Amazon Fire HD 8 (karnak), LineageOS 20 |
| `battery/charging_enable` | 1 | 0 | Lenovo Tab M10 FHD Plus (TB-X606F), LineageOS 21 GSI |
| `battery/charging_enabled` | 1 | 0 | |
| `battery/battery_charging_enabled` | 1 | 0 | |
| `battery/input_suspend` | 0 | 1 | |
| `battery/store_mode` | 0 | 1 | |

All paths are under `/sys/class/power_supply/`. The first two are tested; the others come from common charge limiter apps. Without any of them the plugin page reports that the device has no known charging switch.

## Where data goes

Nowhere outside the device and your own Home Assistant. The plugin reads two battery files, writes one charging switch and publishes the result through the ESPHome connection Kiosk Satellite already has.

## Development

`python3 tools/test.py` runs the tests, including the shell script against a fake battery folder. `python3 tools/build.py --android-platform 35` builds the plugin ZIP into `dist/`. Both need a JDK and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`, found through `JAVA_HOME` and `ANDROID_HOME`.

## License

Apache-2.0
