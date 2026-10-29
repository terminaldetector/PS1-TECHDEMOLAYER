# Comparison with the supplied v0.4 baseline

The baseline (`baseline/DIGITAL_IRIS.BIN`, 430416 B, SHA256
`a0e41f46…78ab2aa2`) is preserved unchanged. It was never an output path and
was not reused: no source for it was supplied, so there was nothing to audit
at source level and nothing to inherit. This project is a clean
implementation.

## Structural

| | baseline | this build |
|---|---|---|
| Image | 430416 B, 183 sectors | 823200 B, 350 sectors |
| ISO9660 Mode 2 raw | yes | yes |
| `SYSTEM.CNF` boot target | `cdrom:\DIGITAL_IRIS.EXE;1` | `cdrom:\DIRIS.EXE;1` |
| Name in the ISO directory | `DIGITAL_.EXE;1` | `DIRIS.EXE;1` |
| **Boot name matches directory** | **no — the headline defect** | **yes** |
| Raw sector EDC verified | not checked by the supplied tool | yes, 350/350 |
| Executable | PS-X EXE, 16384 B | PS-X EXE, 79872 B |
| CD boot confirmed in an emulator | no | yes, `-slowboot` |

The name mismatch is fixed at the source: `tools/mkiso.py` takes the boot name
from one variable and writes it into both `SYSTEM.CNF` and the ISO9660
directory, so the two cannot drift apart. The supplied checker
(`baseline/verify_disc.py`), which returns exit 1 on the baseline, returns
exit 0 on this image.

## Visual

The baseline screenshot (`baseline/gemini_duckstation.jpg`) is a DuckStation
**Android** capture showing: a flat blue-grey fill, a few green slivers, a
small purple polygon flower, large torn brown polygons, and one orange dot.
No mountain, no water, no ground plane with perspective, no lighting
direction.

This build, captured from DuckStation booting the CD image
(`reports/emulator/`): a volumetric Mt Fuji with a ragged snowline, a lake
with grazing-angle sky reflection and a moon-glitter path, a textured soil
bank, a grass field with three levels of detail and out-of-phase wind, and a
botanically readable iris — three drooping falls with ochre beards, three
upright standards — growing continuously from a seed through to a moonlit
night.

Equivalent-anchor comparison, as the brief asked:

| Anchor | baseline | this build |
|---|---|---|
| ~15 s | not demonstrated | `ps1_01.png` — shoot emerging from the split seed, textured soil, Fuji and lake behind |
| ~45 s | not demonstrated | `ps1_03.png` — leaf fan, bud forming |
| ~70 s | the single supplied frame: flower, slivers, torn polygons | `ps1_04.png` — open iris against Fuji, camera risen |
| ~90 s | not demonstrated | `ps1_05.png` — sunset, lit faces on mountain and petals |
| ~110 s | not demonstrated | `ps1_07.png` — moonlight, moon path on the lake, fireflies |

## What this comparison does not claim

- The baseline's source was never seen, so nothing is asserted about why it
  looked as it did. The torn polygons are consistent with unclipped
  near-plane geometry, but that is a hypothesis, not a finding.
- The baseline may well have had more in it than the one supplied frame
  shows. One screenshot is not a survey.
- Both numbers in the performance table below are from the same emulator, but
  the baseline was never run here, so no frame rate is claimed for it.

| | baseline | this build |
|---|---|---|
| Measured frame rate | not measured | 15–20 fps (DuckStation, software renderer, 100% speed) |
