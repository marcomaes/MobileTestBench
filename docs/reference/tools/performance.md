# Performance

Charts memory and CPU of one app, plus frame statistics on Android, and measures app start time.

| | |
|---|---|
| **Works on** | Android and iOS simulator (frame data on Android only; physical iPhones not supported) |
| **Needs** | `adb` for Android; `xcrun` for the iOS simulator; mobilecli for *Foreground app* |

## Use it

1. Open the **Performance** tab and choose **App**: *Foreground app* or an installed app.
2. Press **Start** to sample once per second. Press **Stop** to end it.
3. Pick a window (1, 5 or 10 minutes) and hover a chart for the values.
4. Use **Clear** to empty the charts and **Save CSV...** to export the samples.
5. To time a launch, pick an explicit app, set **Kind** (*Cold* or *Warm*) and **Runs**, then press **Measure**.

## Good to know

* The newest 600 samples (10 minutes) are kept. Selecting another tab stops sampling.
* Sampling adds load to the device being measured.
* Android CPU is in percent of one core and can exceed 100. Memory shows PSS and RSS.
* iOS memory is the physical footprint. There is no frame or jank source on iOS.
* iOS start timing reads undocumented UIKit log text. If the lines are not found the note says "fallback" and the number is only the `simctl launch` call duration.
* Not verified: the panel in the sandbox IDE, physical devices, and apps with several processes.

## Technical notes and what is not verified

<details>
<summary>Full reference text</summary>

The **Performance** tab of Device Tools charts memory, CPU and (Android) frame statistics of one app and measures its start time. **App** is *Foreground app* (re-read every 5 s with `mobilecli apps foreground`; needs mobilecli) or an installed app picked from the list or typed (package name or bundle id; letters, digits, '.', '_' and '-' only, because the id reaches the device shell). **Start** samples once per second on one daemon thread that never overlaps its own calls (a slow call delays the next sample, it does not pile up); **Stop**, a device change, closing the panel and Disconnect end it; **Clear** empties the charts; **Save CSV...** writes the samples (seconds, memory_mb, rss_mb, cpu_percent, fps, janky_percent; an empty cell is a gap). The newest 600 samples (10 minutes) are kept; the window combo shows the last 1, 5 or 10 minutes, and the readouts (now / min / avg / max, gaps ignored) cover the shown window. Hover a chart for a crosshair with the values. When the app is not running the lines stop and the status says so; polling continues, so sampling resumes by itself after a restart (the status then says "restarted"). Selecting another tab stops sampling too (the samples stay on the charts), so nothing polls the device while the tab is out of sight. Sampling adds load to the device being measured.

* **Android** (adb): one `adb shell` call per second reads `pidof`, `/proc/<pid>/stat`, `VmRSS` and the `dumpsys gfxinfo` counters. **CPU** is the delta of utime+stime (USER_HZ 100) over the device clock, in percent of ONE core (it can exceed 100 on a multicore device); the first CPU point appears after two samples. **Memory** shows PSS (blue) and RSS (yellow). PSS comes from `dumpsys meminfo`, which took 2 to 8 s on the emulator and once timed out with exit code 0, so it runs on its own task every 5 s (skipped while the previous call runs; the last value is held) and the 1 s loop never waits for it; RSS is read every second and is about twice the PSS for the playground app, so the chart names both. **Frames**: `Total frames rendered` and `Janky frames` are cumulative counters, so each second shows the delta: janky percentage (chart) and frames per second (in the title). An idle app renders no frames (fps 0, jank shown as a gap); a counter that goes down (app restarted, someone ran `gfxinfo reset`) re-baselines. For an app with several processes only the first pid of `pidof` is sampled. On the emulator used here everything was 100% janky because the host was overloaded, so these absolute numbers say little.

* **iOS simulator** (the app is a normal macOS process): pid from `simctl spawn launchctl list`, memory is the physical **footprint** (`footprint -p <pid> -f bytes`; `ps` rss is far too high for a simulator app, 331 MB against 52 MB), CPU is the delta of the `ps` CPU time (10 ms resolution, so a 1 s window is accurate to about 1%; percent of one host core). There is no frame or jank source without Instruments, so the tab says so. Physical iPhones are not supported.

* **Measure app start**: pick an explicit app (not *Foreground app*), **Kind** *Cold* or *Warm*, **Runs** 1 to 10 and press **Measure** (a dialog says the app will be stopped or sent to the background). The table shows run, kind, milliseconds and a note, **Average** gives average, min and max, the list under it keeps the last 20 measurements of this session (not persisted) with app and device, and **Cancel** stops after the current run. The first failed run stops the series. *Android*: cold = `am force-stop`, 1.5 s, then `am start -W` with the launcher intent (`-a MAIN -c LAUNCHER -f 0x10200000 -n <pkg>/<activity>`, activity from `cmd package resolve-activity`) and its **TotalTime**; warm = Home key, 2 s, the same command (a plain `am start -n` after Home only brings the task to the front and prints no TotalTime; if the app is not running a warm run first launches it untimed). The note shows the LaunchState (COLD or WARM) and flags a mismatch. `mobilecli apps launch` is not used: it prints no timing and returns after about 1.5 s whatever the app is doing. *iOS simulator*: cold = terminate (an app that is not running is fine), 1.5 s, `simctl launch`; warm = Home button (through mobilecli, needed for warm), 2 s, launch. Timing comes from the simulator log: the "Requesting launch of ..." line to the last UIKit "Deactivation reason ... -> 0" line of the app process, which means UIApplication became active (not first frame, and including about 0.4 s of launch animation); it needs a 2.5 s settle delay plus about 1 s for `log show` per run. This reads undocumented UIKit debug text, so when the lines are not found the note says "fallback" and the number is only the duration of the `simctl launch` call, which times the spawn and nothing else. Cold starts on the emulator took 9 to 34 s here because the host was loaded, so a 10 run series can take minutes and timings depend on host load.

* Verified: the parsers and the sample, statistics, start and CSV logic against real output of an Android emulator (Android 17) and an iPhone 17 Pro simulator (iOS 26.5), and every command by hand during scouting. **Not verified:** the panel in the sandbox IDE (chart painting, hover, the thread handling; the logic is unit tested, the Swing code is not), physical devices (`dumpsys meminfo` is normally much faster there; real-device jank values and the USER_HZ of 100 were not seen), apps with several processes, the iOS log timing on other Xcode or iOS versions, with scene based apps or launch storyboards, and after the app was suspended for a long time, and sampling while the mirror and the inspector are busy on a loaded host.

</details>

[All Device Tools tabs](../device-tools.md) · [Feature guide](../../FEATURES.md)
