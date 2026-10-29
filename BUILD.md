# DIGITAL IRIS — building and running

## What this is

A native PlayStation 1 demo: one continuous 3D scene in which an iris grows
from a seed on the shore of a lake below Mt Fuji, blooms, and the day turns to
sunset and then to a moonlit night. Geometry, textures and animation are
generated on the console. There are no pre-rendered frames and no asset files
on the disc: the ISO contains `SYSTEM.CNF` and one executable.

No SDK is linked in. The ROM is bare-metal C plus one assembly startup file,
talking to the GPU, DMA controller, GTE and SIO0 directly.

## Toolchain actually used

| Component | Version | Notes |
|---|---|---|
| C compiler | `mipsel-linux-gnu-gcc` 12.4.0 (Ubuntu 12.4.0-2ubuntu1~24.04cross8) | Linux-targeting cross-GCC used freestanding: `-nostdlib -ffreestanding`, no libc and no libgcc |
| Binutils | 2.42 | the linker's name for the target here is `elf32-tradlittlemips`, not `elf32-littlemips` |
| Make | GNU Make 4.3 | |
| Python | 3.12 | `tools/` only: table generation, EXE and ISO packaging, verification |
| Host preview | `gcc` 13 (x86-64) | optional, see below |

Install on Debian/Ubuntu:

```bash
sudo apt-get install -y gcc-mipsel-linux-gnu binutils-mipsel-linux-gnu make python3
```

The ROM links **no library at all** — not even libgcc. That is why every
division in the console code path is 32-bit: a 64-bit divide would pull in
`__divdi3`. See `fdiv()` in `include/fixed.h`.

## Clean build

```bash
make clean && make ps1 iso
```

Outputs:

```
dist/DIRIS.EXE          PS-X EXE, load and entry 0x80010000
dist/DIGITAL_IRIS.BIN   CD image, ISO9660 in raw Mode 2 Form 1 (2352 B/sector)
dist/DIGITAL_IRIS.CUE   track descriptor for the BIN
```

`make iso` runs `tools/mkiso.py`, which takes the boot name from a single
variable and writes it into both `SYSTEM.CNF` and the ISO9660 directory, so
the two cannot disagree. (They disagreed in the v0.4 baseline; that was the
headline defect in the audit.)

## Verifying the image

```bash
python3 tools/verify_disc.py dist/DIGITAL_IRIS.BIN --json reports/disc_verify.json
```

Fifteen structural checks, including every raw sector's sync pattern, MSF
address, mode byte, duplicated subheader and EDC. The EDC check uses the CRC
property that the checksum computed over the message *including* its own
checksum is zero, so it validates the polynomial rather than merely that the
writer agrees with itself. Exit status 0 means all checks passed.

It is a static checker. It says nothing about whether the program runs.

## Running it

### DuckStation, booting from the CD image

```bash
duckstation-qt -slowboot -fullscreen dist/DIGITAL_IRIS.CUE
```

`-slowboot` runs the full BIOS boot sequence — the BIOS reads `SYSTEM.CNF`,
finds `cdrom:\DIRIS.EXE;1`, loads it and jumps to it. That is the path the
screenshots in `reports/emulator/` were taken through; direct EXE loading was
not used.

A BIOS image is required. The measurements here were taken with **OpenBIOS**,
the open-source BIOS replacement from the PCSX-Redux project, built from
source with the same MIPS toolchain:

```bash
git clone --depth 1 https://github.com/pcsx-redux/nugget.git
make -C nugget/openbios PREFIX=mipsel-linux-gnu FORMAT=elf32-tradlittlemips
```

One caveat, documented because it affects reproducibility: DuckStation decides
a BIOS image's region by searching it for a marker string, and OpenBIOS does
not contain one, so DuckStation reports "No BIOS image found for NTSC-U
region" and refuses to boot. Writing the NTSC-U marker into the free space at
offset `0x7FF32` of our own OpenBIOS build makes DuckStation classify it and
boot normally. No Sony code is involved — the marker is a region tag in an
otherwise zero-filled area of a BIOS we compiled ourselves.

### Emulator settings used for the measurements

GPU renderer **Software**, resolution scale **1x**, PGXP **off**, true colour
**off**, texture filtering **off**, widescreen hack **off**, aspect
**stretch**. All image enhancements are disabled; the captures are native
320x240 output.

## Host preview (development aid, not evidence)

```bash
make host
./build-host/preview --out preview --stats --times 2000,45000,96000
```

This compiles the same scene sources against a software model of the PS1 GPU
and GTE, and writes one PPM per timestamp. It consumes the *same* linked list
of GP0 packets the console's DMA channel 2 consumes, walking 24-bit tag
addresses through a simulated 2 MiB RAM at the same base address, so it also
works as a packet validator.

**It is not evidence that anything runs on a PlayStation.** Console evidence
is in `reports/SCENE_TESTS.md` and `reports/emulator/`.

Options: `--seed N`, `--orbit DEG`, `--quality 0|1|2`, `--debug FLAGS`
(bit flags from `include/scene.h`), `--warm N`, `--stats`.

## Controls on the console

| Button | Effect |
|---|---|
| SELECT | timing HUD on/off |
| SQUARE | grass on/off |
| TRIANGLE | textures on/off |
| CIRCLE | fireflies on/off |
| CROSS | FVA skeleton overlay |
| START | freeze the sun where it is |
| L1 / R1 | orbit the camera around the plant |
| L2 / R2 | LOW / SHOWCASE quality profile |

The piece plays correctly with nothing pressed.
