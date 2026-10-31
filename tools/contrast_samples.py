"""Screenshot samples for tools/contrast.py (Drone Sight, emulator-5556 shots in apps/drone-sight/build/shots2, 2160 px,
view -38 so the control panel is straight ahead). (pair, where, shot, box x0,y0,x1,y1, threshold)."""
SAMPLES = [
    ("labelPrimary / glass", "settings title 设置 / Settings (titleMedium)", "S11_bottom", (928, 1310, 995, 1332), 4.5),
    ("labelPrimary / glass", "settings row label Fly slower (bodyMedium)", "S11_bottom", (955, 1356, 1025, 1380), 4.5),
    ("labelPrimary / glass", "settings row label Direction hints", "S11_bottom", (955, 1406, 1055, 1430), 4.5),
    ("labelPrimary / glass", "settings row label Big height tag", "S11_bottom", (955, 1556, 1050, 1580), 4.5),
    ("labelPrimary / glass", "settings row label Reset progress", "S11_bottom", (955, 1708, 1057, 1732), 4.5),
    ("labelPrimary / fillTertiary", "normal key Move course (settings)", "S11_bottom", (995, 1606, 1082, 1630), 4.5),
    ("labelPrimary / fillTertiary", "segment item On (unselected)", "S11_bottom", (1141, 1406, 1165, 1428), 4.5),
    ("labelPrimary / glass", "picker title Pick one", "S04_picker", (928, 1313, 1000, 1334), 4.5),
    ("labelPrimary / fillTertiary", "lesson row name Takeoff · Tail-in hover (titleSmall)", "S04_picker", (983, 1357, 1122, 1375), 4.5),
    ("labelPrimary / fillTertiary", "lesson row tagline (labelLarge 14 sp)", "S04_picker", (983, 1376, 1150, 1391), 4.5),
    ("labelPrimary / glass", "flight title Takeoff · Tail-in hover (2 lines, 16 sp)", "S05_L1", (930, 1306, 1026, 1340), 4.5),
    ("labelPrimary / fillTertiary", "segment item ATTI (unselected)", "S05_L1", (1040, 1366, 1070, 1384), 4.5),
]

# text that WCAG 1.4.3 does not require (disabled / decorative); still measured and listed
EXEMPT = []
