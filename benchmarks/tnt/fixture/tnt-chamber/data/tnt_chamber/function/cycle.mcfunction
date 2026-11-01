# ── Sustain loop for the classic chamber ───────────────────────────────────
# Rebuilds the 5×5×5 unstable TNT core (destroyed by each blast) and re-fuses
# it every 2 ticks, keeping the explosion pressure continuous. Scheduled by
# build.mcfunction; reschedules itself.
fill 4 34 4 12 42 12 stone
fill 6 36 6 10 40 10 tnt
schedule function tnt_chamber:cycle 1t
