# ── Declarative TNT benchmark chamber (self-contained) ─────────────────────
# Classic room: obsidian shell + stone fill + a 5×5×5 TNT core (125 TNT),
# fully self-contained — works on a fresh void world with no other setup.
#
# Chain mechanism (why this works standalone):
#   - core uses minecraft:tnt[unstable=true] — destroyed-by-explosion TNT
#     ignites, so one blast chains through the whole core
#   - the fuse is a summoned primed TNT (summon minecraft:tnt {fuse:20})
#   - tnt_chamber:cycle reschedules itself every 2 ticks to rebuild the
#     core and re-fuse, sustaining the pressure (the binary world's
#     repeating command block did the same)
#
# Runs once on world load (data/tnt_chamber/tags/function/load.json).
#
# Room layout (origin = 0 0 0, chamber interior spans x/z 1..14, y 1..6):
#   y=0      : obsidian floor
#   y=1..6   : obsidian walls (perimeter) + stone interior
#   y=7      : obsidian ceiling
#   Core     : 5×5×5 = 125 unstable TNT centred at (9, 3, 9)
#   Fuse     : a primed TNT inside the core

# Clean
kill @e
fill 0 30 0 16 46 16 air

# ── Obsidian shell ─────────────────────────────────────────────────────────
fill 0 30 0 16 46 16 obsidian outline

# ── Fuse + sustain loop ────────────────────────────────────────────────────
summon minecraft:tnt 8 38 8 {fuse:2}
schedule function tnt_chamber:cycle 1t

# ── Force-load the region so the chamber is ticked from boot ───────────────
forceload add -32 -32 32 32
