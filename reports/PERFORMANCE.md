# DIGITAL IRIS — performance

Everything below was measured. Nothing is extrapolated, and nothing from the
host preview appears in this file.

## How it was measured

Two independent instruments:

1. **DuckStation's own frame counter.** Its OSD reports the rate at which the
   emulated console completes frames, alongside VPS (the field rate) and the
   emulation speed. All readings below were taken at **100% emulation speed**,
   so the console is being run in real time and the FPS figure is the
   console's, not the host's.

2. **The ROM's own profiler** (`src/profiler.c`). Root counter 1 counts
   hblanks at 15.734 kHz — one tick is 63.56 us, one NTSC field is 525 ticks.
   Each scene section is bracketed, and the results are drawn as white bars on
   black rules, one row per section, so a screenshot is a measurement. Raw
   readings are in `reports/emulator/profile_readout.txt`; the screenshots
   they were read from are `profiler_hud_20fps.png` and
   `profiler_hud_15fps.png`.

Emulator configuration: DuckStation 0.1-12442, software renderer, 1x
resolution, PGXP off, true colour off, no texture filtering, no widescreen
hack, all enhancements disabled, NTSC-U, booted from the CD image with
`-slowboot`. BIOS: OpenBIOS built from source (see BUILD.md).

## Result

| Profile | Measured | Fields per frame |
|---|---|---|
| BALANCED (default) | **15–20 fps** | 3 fields in lighter shots, 4 in heavier ones |

Specific readings from the OSD: **19.94 fps** during the growth phase,
**14.95 fps** once the flower is open. The frame rate is quantised to whole
NTSC fields because the demo waits for vblank, so it moves between 60/3 and
60/4 rather than varying continuously.

**This misses the 25–30 fps the brief asked for.** It is reported as measured.
The piece is stable and does not tear or corrupt at this rate, but it is a
slow 15–20, not 30.

LOW and SHOWCASE are selected on the console with L2/R2. They were not
measured on the console because that needs a controller press during a
capture; their relative load is given below from primitive counts, which is
evidence of a difference but not of a frame rate. Marked UNVERIFIED in
SCENE_TESTS.md accordingly.

## Where the time goes

From the ROM's profiler, two frames at different points in the programme:

| Section | Growth phase (19.94 fps) | Flower open (14.95 fps) |
|---|---:|---:|
| sky + sun/moon disc | 0.00 ms | 0.00 ms |
| Mt Fuji | 5.21 ms | 5.21 ms |
| far ridges | 2.67 ms | 2.67 ms |
| lake | 6.36 ms | 6.36 ms |
| soil bank | 8.14 ms | 7.63 ms |
| grass | 14.36 ms | 14.75 ms |
| iris | 4.32 ms | 15.89 ms |
| fireflies | 0.00 ms | 0.00 ms |
| present (OT DMA + vblank wait) | 6.99 ms | 10.30 ms |
| pad poll | 0.00 ms | 0.00 ms |
| OT clear | 0.00 ms | 0.00 ms |
| **total** | **48.05 ms** | **62.92 ms** |

`present` includes the wait for the next field, so it absorbs the slack up to
the field boundary; CPU and GPU work is the total minus that row.

The sky row reads 0.00 ms because it is below the 2-hblank resolution of one
bar pixel, not because it is free.

## What the optimisation actually bought

Measured at the same point in the programme, same emulator settings:

| Change | fps |
|---|---:|
| first working console build | 4.98 |
| bounded the SIO0 pad poll, halved the ordering table | 9.97 |
| shaded ground/Fuji/lake once per vertex instead of once per quad corner; soil albedo precomputed at init instead of a 3-octave noise per vertex per frame | 11.96 |
| grass lit once per frame rather than per blade | 14.95 |
| geometry budgets cut; distant blades build 3 vertices instead of 6 | 15–20 |

A factor of three to four, all of it from doing less redundant work rather
than from cutting the scene down: the shipped scene still has Mt Fuji, the
lake with its reflection, the textured bank, the grass field, the full plant,
the day-to-night lighting and the fireflies.

The single largest win was noticing that the soil's albedo — a three-octave
value noise — was being re-evaluated for every corner of every quad of every
frame, when it never changes.

## Scene load

Primitive counts from the host build, which emits the identical packet stream
(`reports/frame_stats.txt`):

| t (s) | primitives | triangles | pixels | packet buffer |
|---:|---:|---:|---:|---:|
| 2 | 248 | 410 | 170287 | 0 KiB |
| 30 | 359 | 604 | 149988 | 12 KiB |
| 58 | 345 | 646 | 140520 | 14 KiB |
| 96 | 496 | 857 | 156260 | 18 KiB |
| 116 | 499 | 866 | 156364 | 19 KiB |

Overdraw is about 2x on a 76800-pixel framebuffer. No frame overflowed the
primitive arena (`overflow = 0` throughout) and no packet was dropped.

## Memory

From the link map and the EXE header:

| | bytes |
|---|---:|
| .text | 67088 |
| .rodata | 9716 |
| .data | 864 |
| .bss | 329124 |
| **total RAM** | **~397 KiB of 2 MiB** |

The bulk of .bss is the two render lists (ordering table plus primitive arena,
double buffered). The stack sits at 0x801FFF00.

VRAM: two 320x240 15bpp framebuffers at (0,0) and (0,240), and a 4bpp texture
page at x=640 holding two 64x64 textures plus two 16-entry CLUTs at y=200.
Well under the 1 MiB available; most of VRAM is unused.

## What was NOT measured

- Real PlayStation hardware. Nobody ran this on a console. Every number here
  is from DuckStation.
- PCSX-Redux. Not run; only DuckStation was used.
- LOW and SHOWCASE frame rates (see above).
- GPU-side timing in isolation. The profiler measures CPU wall time per
  section; the GPU's own cost appears inside `present` as the DMA wait, not
  broken out.
