# DIGITAL IRIS — what is not done, and what is wrong

## Not implemented

### Audio
The SPU is untouched. The piece is silent. The brief allowed this to be a
separate milestone and it was: graphics work ran to the end of the available
time. Nothing in the architecture blocks it — SPU RAM is entirely free and
the frame loop has an obvious place to drive a sequencer.

### Pagoda / stone lantern
Both reference paintings place a small structure on the far shore
(`IRIS_STAGE_9` a pagoda, `IRIS_STAGE_8` a stone lantern). Neither is in the
scene. It would be perhaps 40 polygons and would noticeably improve the
sense of place.

### The autumn maple branch
`IRIS_STAGE_9` frames stages 6–9 with a red maple branch at the top left.
Not implemented.

## Performance

**The demo runs at 15–20 fps, not the 25–30 fps the brief asked for.** This
is measured, not estimated; see PERFORMANCE.md for the method and the
per-section breakdown.

Where the remaining time goes, and what would most likely buy the next
factor:

- **Grass is ~14.5 ms**, the largest single cost. Each blade still runs its
  own wind evaluation and six-vertex transform. Evaluating wind once per
  clump rather than once per blade, and pre-baking blade silhouettes for the
  two distant LODs, is the obvious next step.
- **The soil bank is ~8 ms** for 150 quads, which is far more than the GTE
  path should cost. Most of it is the CPU near-plane clipper: any polygon
  with a vertex off-screen gets its coordinates saturated by the GTE, which
  makes the size test think it is enormous, so it takes the slow path even
  when it did not need to. Testing the true extent in view space before
  projecting would avoid most of these.
- **`present` absorbs 7–10 ms** waiting for the field boundary. That is the
  vsync wait, not waste, but it means work must drop below 50 ms to gain a
  whole field.

## Known visual defects

### Near-field gaps in the ground
A small number of polygons at the very bottom of frame are dropped by the
near-plane clipper, leaving a thin band of backdrop. This was originally very
visible because the backdrop below the horizon was sky-coloured; it is now
dark earth, so a gap reads as shadow rather than a hole. The underlying cause
— the clipper rejecting a polygon it should have trimmed — is not fixed.

### Lake banding
The grazing-angle sky reflection steps visibly between the lake's grid rows,
because the row spacing is coarse where the gradient changes fastest. More
rows would fix it at a fill-rate cost.

### Diagonal seam on additive quads
PS1 renders a quad as two triangles and the shared edge is covered twice, so
additive transparency shows a faint diagonal. The fireflies avoid this by
using rectangle primitives; the moon halo still has it.

### Textures do not survive the near-plane clipper's fallback
They do for quads — UVs are interpolated through the clipper — but a textured
*triangle* that needs clipping falls back to flat Gouraud. In practice this
only affects a few polygons at the bottom edge.

## Verification gaps

- **No real PlayStation hardware.** Everything is DuckStation.
- **Only one emulator.** PCSX-Redux was not run, so emulator-specific
  behaviour has not been cross-checked.
- **LOW and SHOWCASE frame rates unmeasured** on the console; only their
  primitive loads are known to differ.
- **T16 and T18 are PARTIAL**, for the reasons given in SCENE_TESTS.md.

## Reproducibility caveat: the BIOS

DuckStation will not boot without a BIOS, and it classifies a BIOS image's
region by searching it for a marker string. OpenBIOS — the open-source
replacement used here — contains no such string, so DuckStation rejects it
with "No BIOS image found for NTSC-U region". The marker was written into the
free, zero-filled space at offset `0x7FF32` of our own OpenBIOS build to make
it classify.

This is worth stating plainly: it is a region tag added to a BIOS we compiled
ourselves from public source. No Sony code is involved, and the demo itself
does not depend on it — a retail BIOS image works without any of this.

## Technical debt

- `plat_upload_vram` assumes the transfer size divides evenly into DMA
  blocks; it picks the largest block size that fits, but a texture whose
  halfword count is an awkward prime would transfer one word per block.
- The profiler's bars are read off screenshots by a Python script. This works
  and is honest, but printing the numbers over the BIOS TTY would be better
  if OpenBIOS's TTY were hooked by DuckStation.
- `g_bound_mtx` is a global mirror of the matrix last pushed to the GTE,
  which the near-plane clipper reads. It works because rendering is
  single-threaded, but it is an implicit coupling between `camera.c` and
  `draw.c`.
- The quality profile is read from a global inside the draw functions rather
  than being passed down.
