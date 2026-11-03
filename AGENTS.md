# Line-of-Sight Pilot (视距飞手)

A life-size 450 quadcopter takes off from a pad on your real living-room floor; the two PICO controller thumbsticks are a
Mode 2 transmitter (left = throttle + yaw, right = pitch + roll) and you practise the multirotor VLOS test items: tail-in
hover, nose-in hover, a slow 360, the horizontal figure eight, a spot landing, the full run, an ATTI hover, plus free
flight and an endless waypoint run. PICO OS 6, Kotlin, PICO Spatial SDK 6.1.9, package `io.embodit.dronesight`.
Skeleton D (passthrough anchor tool), container **STAGE_MIXED** (manifest `pico.spatial.stage.style=1`). Since v0.1.2
(user on the headset + profile D8 / D21, factory snippet `prompts/snippets/native-window-move.md`) ALL 2D content is
ONE system **Planar WindowContainer** `ControlPanel` (540 × 680 dp = 0.43 × 0.54 m, measured 0.8 mm / dp), opened by
the Stage: pages switch with the system **TabBar** (飞行 / 科目 / 设置), the system caption bar under the window moves /
closes / minimises it (no drag code in the app). On the Stage only the height tag over the drone (scale 0.75) and the
"打开面板" chip (shown while the window is closed / minimised) are left. No permissions, no network.

Design package (binding): `../../spatial-app-factory/apps/drone-sight/design-package.json` (17 states S00-S15 + S06a /
S14 overlays, rules.flight / judge / courseGeometry, copy + `content.tone`). PRD
https://claude.ai/artifact/XLcHF5kJwb9TN3x6vThphq, sample https://claude.ai/artifact/3EQmjpBS1hq9TKdr8tzktb.
Scaffold: `pico-cli project create --sdk-type spatial --template stage`, then the panel pool / head tracker / theme /
fonts / debug receiver ported from Dog Manners (same factory, same Mac), controller + plane tracking after Figure Model
and Furniture Fit, the flight model after Spatial Chopper Toy's velocity solver (simplified to the design's
velocity-command model).

## Structure

```
app/src/main/java/io/embodit/dronesight/
  Main.kt                  DefaultStage (Mixed) -> DsTheme -> DsScreen; WindowContainer ControlPanel -> DsWindow
  domain/                  pure Kotlin, JVM-tested (app/src/test)
    Flight.kt              FlightModel (GPS velocity command / ATTI acceleration + drag, wind, take-off, ceiling,
                           touch-down soft / heavy / hard), Wind (none / light / gusty, GPS sway)
    Sticks.kt              Mode 2 sticks, dead zone 0.08 + expo 0.3, per-axis inversion, merge (hardware / pads / debug)
    Course.kt              CourseGeometry (pad, hover box, poles, figure-eight path, gates G1-G4), Gate, Orient
    Session.kt             LessonId (L1-L7, F1, F2), Leniency (D27), Session = one flight + its judges; Outcome / Diff
    Waypoints.kt           F2 generator (seed = round), WaypointRing
    Calibration.kt         D25: the ONE place for the drone model's offset / rotation / scale
    Layout.kt              CourseFrame, placeUpright (the only head -> pose function, yaw only), Placement
  domain/RotorSynth.kt     v0.1.2 rotor sound (B20): 4 detuned voices, pitch = throttle, ±2.5 % slow random drift,
                           slow AM, low-pass, distance gain, pan; JVM-tested (RotorSynthTest renders the QA sample)
  platform/                DsLog (tag DroneSight), Store (prefs), Sound (cues assets/sfx + RotorAudio: AudioTrack
                           thread playing RotorSynth), tracking/HeadTracker,
                           input/Controllers (sticks, trigger, A / X, ray), sense/FloorFinder (FLOOR, 5 s)
  ui/
    DsUiState.kt           Screen / Prefs / Hint / FlightSnap, designState() -> "S06+S06a+S14"
    DsController.kt        ALL behaviour: events (Ui.*) -> state, sessions, results, D27 adaptive leniency, quit (D26)
    DsScreen.kt            wires controller, scene, Stage panels; opens the main window; lifecycle (background -> S15)
    window/                DsWindow (window content + WindowCtl: lifecycle, open / recall / chip, dp -> m),
                           MainWindowTemplate (factory template: TabBar pages + Settings last, D26)
    scene/DroneScene.kt    ECS: build (resources on IO), floor, S03 ghost + lock, session stepping, drone pose,
                           window pose estimate, tag / chip placement, view-cone fades, logs, debug seams
    scene/CourseView.kt    pad, hover box + progress, spin ring, poles, gates, path, rings, bounds, wind sock
    scene/DroneView.kt     root -> tilt -> calib (D25) -> quad_A.glb; blur discs, shadow + nose V, height line,
                           direction-hint arrows, landing offset line
    scene/PanelRig.kt      pre-built panel pool (Spatial Ruler findings)   scene/SceneKit.kt  meshes, materials, labels
    components/            DsStage (SpatialView + tag + chip + tap), Panels.kt (all pages), Strings.kt
    theme/                 DsColors (the only colour literals), DsTheme (16 roles), DsTypography (Noto Sans / SC)
app/src/debug/             DebugCommandReceiver + DebugCommands (QA hooks)
app/src/main/assets/       quad_A.glb (design default A), sfx/*.wav cues (tools/render_audio.py; no rotor WAV since v0.1.2)
tools/                     dev.sh, strings_table.py (single source of copy), subset_cjk_font.py, render_audio.py,
                           make_icon.py + vector_render.py, contrast.py + contrast_samples.py, small.py
```

Conventions: Stage metres, -Z ahead. Course-local = origin on the pad centre ON THE FLOOR (y = height), local -Z = your
view direction when the course was placed, poles on the X axis. The drone's nose is its -Z (GLB checked in
CalibrationTest: front red LEDs led45 / led135 at -Z). Yaw convention: + = turn left. One direction of data: panel
taps / debug hooks -> `DsController.onEvent`; the frame system steps `DsController.session` and reports events
(`onSessionEvents`) and a few-times-a-second snapshot (`onSnap`); drone transforms never go through Compose.

## Build, run

`tools/dev.sh` works on both machines. macOS (kj-mbp): pinned to emulator-5556 / AVD PICO_6_1_kj-mbp_2. Windows
(kj-alienware, Git Bash, since 2026-11-02): primer JDK 21, `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`,
adb from `~/Android/Sdk`, `local.properties` = `sdk.dir=C:/Users/Admin/Android/Sdk` (forward slashes, not in git); no
device is pinned: `DEV=<serial> AVD=<avd> tools/dev.sh …` with the emulator state.json gives you, or `DEV=PB324XJGL2090081G`
for the headset (no AVD check). The headset sleeps within seconds when nobody wears it (frame loop stops, screencap is
50 bytes): send `input keyevent KEYCODE_WAKEUP` every 2 s while driving it. A kj-mbp-signed install must be uninstalled
before a kj-alienware build goes on.

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh relaunch     # relaunch = force-stop + am start -W
tools/dev.sh test                          # JVM unit tests (flight, sloppy judging, endings, waypoints, D25, layout, S03)
tools/dev.sh log 'STATE|SESSION|RESULT|PLACE|CALIB' 60
tools/dev.sh crash
tools/dev.sh shot NAME [DIR]               # pico-cli capture screenshot (2160 px)
python3 tools/strings_table.py && python3 tools/subset_cjk_font.py --source ~/Slides/Assets/Fonts/NotoSansSC-VariableFont_wght.ttf
tools/dev.sh test && python3 tools/render_audio.py ../spatial-app-factory/apps/drone-sight/qa   # cues + rotor 10 s (v0.1.2 synth + v0.1.1 A/B) + spectrograms
python3 tools/make_icon.py ../../spatial-app-factory/apps/drone-sight/icon.png
python3 tools/contrast.py SHOTS_DIR --md OUT.md                               # D13 table (fixed pairs + screenshot samples)
```

JAVA_HOME = the primer JDK 21 (set by dev.sh). Material gate: `/usr/bin/grep -rEf
../../spatial-app-factory/bin/material-grep.txt --include='*.kt' --include='*.kts' app/src` must print nothing (the
shell's `grep` here is ugrep and rejects that regex file).

## Debug broadcast (debug builds only)

`adb -s emulator-5556 shell "am broadcast -a io.embodit.dronesight.DEBUG -p io.embodit.dronesight --es cmd 'NAME ARGS'"`
or `tools/dev.sh cmd 'NAME ARGS'`. Each logs `CMD NAME ARGS -> result`; every state change logs
`STATE S06+S14 screen=… why=…`. Commands send the panels' own events or feed the same inputs as the sticks / pads /
flight sensors (factory rule 2026-10-29: no shortcut state writes). The emulator runs at ~1 fps: allow a few seconds.

| command | effect |
|---|---|
| `firstRun` `welcomeOk` `picker` `place` | clear prefs -> S01 / 好，开始 / walk the start pages to S04 / 放在这里 (S03 -> S04) |
| `nofloor 1` `floor 1` | S02: skip plane detection -> 5 s estimate path / take a FLOOR at head − 1.6 m |
| `ray X,Z` / `ray off` | S03: the aiming ray hits the floor X m right, Z m ahead of the head (ghost follows) |
| `lesson L1..L7|F1|F2` `takeoff` `pause` `resume` `key` | pick a lesson (S05) / 起飞 / 暂停 / 继续 / the A-X key |
| `retry` `next` `prev` `nextLesson` `toPicker` | result keys (重飞 / 下一科) and the panel chevrons |
| `stick l X,Y r X,Y [ms]` | synthetic stick deflection (raw -1..1, shaped like the hardware) for ms (default 500) |
| `vstick l|r X,Y [ms]` / `vstick off` | the virtual pad position (S14), held for ms (default 2000) |
| `fly hover` `fly nosein` `fly spin [ms]` | in the air in the hover box (tail-in / nose towards you / turning with the left stick) |
| `fly gate N` | flies through gates 1..N-1 for real (frame by frame), stops before gate N |
| `fly chain K` | L6: items 1..K-1 done for real (box + fast-forwarded time), now on item K |
| `fly out` `fly left` | outside the field (S06a) / between your eyes and the panel (drone 40 % see-through) |
| `fly land soft|heavy|off` `fly crash` | up to the hover height, then touch down at 0.3 / 0.7 m/s, 1 m off the pad / 1.5 m/s (S09) |
| `wind none|light|gusty|gust` `mode gps|atti` `tol lenient|standard` | panel segments; `gust` starts a gust now |
| `speed K` `simulate S` `freeze 1|0` | time scale / fast-forward S seconds of the session / hold the flight still |
| `waypoint seed N` `round N` `best L1 20` | F2 round N / set the round / inject a best score (picker chip) |
| `settings [top|bottom]` `closeSettings` `replace` `recall` `quitAsk` `quitCancel` `quitYes` | S11 (scrolled) / back / 重摆场地 / 叫回面板 (close + reopen the window) / S12 inline / quit |
| `tab fly|lessons|settings` | the TabBar's own event (v0.1.2) |
| `window [close|open]` | the window's state / close it as × does / the "打开面板" chip (restore if minimised) |
| `lang zh|en|system` `mute on|off` `volume N` `poles M` `seated 1|0` `bigtag 1|0` | settings |
| `nocontroller 1|0|auto` `vsticks` `hints` `block` `unblock` | controller presence as the provider would report it: 1 = none (pads auto, S01 / S03 no-controller copy), 0 = connected (with-controller copy, also on the emulator), auto = real / toggles / S13 |
| `fit` `fit reset` | D13 text-fit summary: layouts checked, texts that overflowed (each also logs `OVERFLOW <panel>/<control>`) |
| `view DEG` | emulator camera cannot turn: placements (course, chip) use the head turned DEG (+ = left) |
| `state S01..S15 | S06A` | macro of the above to reach a design state |
| `where` `panels` `instances` `bounds PANEL` `dump` `ftest on|off` | diagnostics; `ftest` puts F↑ on the gate-1 tag ×3 (D10) |

Text fit (D13 pre-QA gate): every panel Text reports to `TextFit` (`onTextLayout = fits("panel/control")`); a text that
does not fit logs `OVERFLOW …`. `tools/fit_sweep.sh` runs every state in en + zh with the widest chips (hover 15/20 s, 360°,
gate 4/4, round 12 past 10:00) and prints the OVERFLOW lines (must be none). Page titles fit themselves (20 sp / 1 line →
16 sp / 2 → 14 sp / 2), dynamic chips sit in a fixed-width slot sized from their widest text for the lesson.

Waypoint rings (F2) are drawn from the REAL head, panel (page + stick pads) and course pose (`RingGuard`, logged as
`RINGS round=… guard=live eye=… panelAz=… near=…`): every ring point >= 1.5 m from the head, none in the panel's window
seen from the eyes; re-drawn when the panel is dragged / called back before take-off (WaypointTest sweeps 12 096 poses).

Virtual pads end to end: `tools/pad_lesson.sh [OUTDIR]` (L1 with the pads only: take off by holding the left pad up
1 s (`PAD_TAKEOFF=0` uses the 起飞 key), climb into the box, 20 s GPS hold, sink onto the pad -> `RESULT … PASS`).

## Screenshot list (QA, design states)

| state | how |
|---|---|
| S00 | relaunch, capture within 2 s (panel "准备中…") |
| S01 | `firstRun` (+ `nocontroller 1` for the no-controller first line) |
| S02 | `state S02` (spinner) ; `nofloor 1` -> timeout line |
| S03 | `state S03` ; `ray 1,2.5` follows ; `stick l 0.8,0 1500` turns the ghost |
| S04 | `state S04` ; `best L1 20` chip |
| S05 | `lesson L1` … `lesson F2` (one per lesson) |
| S06 | `lesson L1`, `fly hover` (green box + progress) ; `lesson L2`, `fly nosein` (arrows, 对头) ; `lesson L4`, `fly gate 2` ; `wind gusty`, `wind gust` ; `lesson L6`, `fly chain 3` |
| S06a | `fly out` |
| S07 | `lesson L5`, `fly land soft|heavy|off` |
| S08 | after S07 (1.2 s); pass / near (`fly land off`) / retry (`fly crash`) |
| S09 | `fly crash` |
| S10 | `waypoint seed 7`, `takeoff` ; `fly gate 3` (rings use the same path macro) |
| S11 | `settings` / `settings bottom` (退出应用 visible) ; `lang en` / `lang system` (语言 自动 / 中 / EN, first row) |
| S12 | `quitAsk` (confirm opens inside the settings page, bottom) |
| S13 | `block` (in the main window's 飞行 page; `unblock` -> S00) |
| window | first frame: system window + TabBar on its left, caption dot under it ; hover the dot ; `window close` -> chip ; tap chip / `window open` ; `recall` (log ON_DESTROY -> ON_CREATE) |
| S14 | `nocontroller 1` (pads auto) ; `vstick r 0.7,0.3` |
| S15 | `fly hover`, `pause` |

## Platform facts found here

- v0.1.2 main window (headset, 2026-11-02): `WINDOW lifecycle ON_CREATE / ON_START / ON_STOP` while the Stage is still
  building, then `ON_START / ON_RESUME` ~4 s later; `LocalPhysicalLengthConverter` in the window gives 0.8 mm / dp (same
  as the Stage's DP_M). The system puts the window ~1.75 m straight ahead; the app can neither read nor set its pose, so
  `WINDOW estimate` (open-time head pose) is what the waypoint guard and the drone fade use. The default course is 16°
  right / 3 m, clear of the window; the L4 left pole and F2 rings can still be behind it, then the user drags it away.

- Emulator: `ControllerTrackingProvider.supportState` = `DEVICE_NOT_SUPPORTED` (no controllers) -> the virtual pads show
  automatically; the hardware stick path is only testable on the headset (design verification.deviceOnly).
- Emulator FLOOR plane: when it comes (often within 5 s here) it sits at y = 0.40 m (anchor position = vertices), 0.35 m
  above the head-estimated floor (head 1.65 − 1.6). The app takes the plane as is (design); `nofloor 1` forces the
  estimate path.
- **Transparent + textured Unlit material = render-thread stall on the 6.1 emulator.** The drone's first ground shadow
  (transparent Unlit with a radial texture) took the runtime from 2 fps to 0 within seconds and the runtime watchdog
  killed it (worldspace / systemui / launcher with it; only `adb emu kill` + cold boot recovers). Found by bisecting
  with `lite course|bounds|sticks|catcher|drone|shadow 0|1` over 10 cold boots (build.log). Rule here: no texture on a
  transparent material (the shadow is three plain 16 % discs); textured labels are opaque.
- Creating entities on the main thread waits for the runtime: 13 entities in one go at S03 caused one input ANR. The
  course's line work is 13 merged meshes created during S00 (with frames in between); re-layouts refill the meshes
  on the IO thread (`MeshResource.replaceWithMeshModel`). Every per-frame engine write is cached.
- kj-mbp emulator-5556 with emulator-5554 busy (host load 20-28 on 14 cores): the runtime still lags and gets killed
  after 2-30 min of use even without the above; `adb emu kill` +
  `pico-cli emulator start --avd PICO_6_1_kj-mbp_2 --adb-device emulator-5556 --args=-no-snapshot-load` recovers it.
  `am start -W` often reports `Status: timeout` while the app does start (18-30 s cold start).
