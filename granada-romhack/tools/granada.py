#!/usr/bin/env python3
"""
Granada (Mega Drive) engine map - addresses recovered by reverse engineering.
Every constant here was confirmed either by disassembly or by reading live RAM
out of a running emulator (see docs/ENGINE.md).
"""
import struct

ROM_SIZE      = 0x80000
HDR_CHECKSUM  = 0x18E            # boot code at 0x31C-0x344 verifies this

# ---- per-stage pointer tables (6 rows x 9 stages, longs) ----------------
TBL_BLOCKMAP   = 0x60BE          # row 0 -> $FFAB08  64x64 block indices (0x1000 b)
TBL_BLOCKDEFS  = 0x60E2          # row 1 -> $FFAB0C  256 blocks x 4x4 tile ids
TBL_TILESET    = 0x6106          # row 2            compressed stage tiles
TBL_PALETTE    = 0x612A          # row 3            32 bytes -> $FFB30E
TBL_TILEATTR   = 0x614E          # row 4 -> $FFAB10  per-tile attribute byte
TBL_SPAWNLIST  = 0x6172          # row 5            128 x 8-byte object records
TBL_MUSIC      = 0x4646          # word per stage
TBL_STARTPOS   = 0x2B254         # 16 bytes per stage, two longs <<8

OBJ_TEMPLATES  = 0x2A150         # 32 bytes per object type
OBJ_INIT_TABLE = 0x5D3C          # long per type: spawn-time init handler
TPL_HP         = 0x04            # word, hit points
TPL_RANGE      = 0x06            # word, activation radius: the object's AI is
                                 # skipped entirely while the player is further
                                 # away than this ($A104-$A138)
TPL_TARGET     = 0x14            # word, non-zero = counts toward stage clear

SPAWN_SLOTS    = 128
SPAWN_REC      = 8               # word type, word X, word Y, word param

# ---- code addresses we hook --------------------------------------------
PAD_READ       = 0x2050          # 3-button pad read  -> $FFF602 / $FFF604
STAGE_INIT     = 0x4846          # (re)enter a stage
STAGE_ADVANCE  = 0x4C10          # addq.b #1,$FFABE1
GAME_COMPLETE  = 0x4C2C          # reached after stage 8 cleared
SPAWN_SET_HP   = 0x5BCC          # moveq #0,d0 / move.w 4(a2),d0 / move.l d0,$1C(a1)
SPAWN_SET_HP_R = 0x5BD6          # return point for the HP hook
PLAYER_FIRE    = 0x96C6          # player weapon update (only caller: 0x4BB6)
PLAYER_FIRE_R  = 0x96D0          # return point for the fire hook
SPAWN_SHOT     = 0x9830          # (type,x,y,vx,vy,angle,p6,p7) -> projectile
# One shared table of 320 longs at $8EC6 holding -256*cos(a * 2*pi / 256).
# Code reads T[a+64] at $8FC6 + a*4 and T[a] at $8EC6 + a*4, so ANGLES RUN
# 0..255, not 0..63.
ANGLE_TBL      = 0x8EC6
ANGLE_TBL_90   = 0x8FC6
ANGLE_STEPS    = 256
ATAN2          = 0x8456          # d0 = dx, d1 = dy -> d0 = angle 0..255
SHOT_POOL      = 16              # projectile slots at $FFA000 ($98BC)
OBJ_ARRAY      = 0xFF8000
OBJ_STRIDE     = 0x20
OBJ_HP         = 0x1C

# ---- RAM ----------------------------------------------------------------
RAM_STAGE      = 0xABE1          # .w short form of $FFABE1, byte: stage 0-8
RAM_PLAYER     = 0xAB14          # player struct; +4 X, +8 Y, +0x14 angle
RAM_PSTATE     = 0xAB16          # player state (<=2 means "can act")
RAM_TARGETS    = 0xB434          # remaining stage targets
RAM_VINT_CNT   = 0xBEC0          # incremented every VBlank
RAM_RANK       = 0xFE06          # option: EASY/NORMAL/HARD/MANIA
RAM_STOCK      = 0xFE08          # option: number of Granadas (lives)

# options screen ($36C6 draws it, $3802 is its loop)
OPT_DISPATCH   = 0x3B84          # 7 longs, one row handler each
OPT_ROWS       = 7               # cursor range 0..6 in the stock game
OPT_CURSOR_VDP = 0xC650          # cursor cell = this + row * 0x80
OPT_EXIT_VDP   = 0xC952          # where the EXIT label is drawn
OPT_LOOP       = 0x3B80          # every row handler ends by branching here
DRAW_STRING    = 0x7C20          # a0 = NUL-terminated string
SET_VDP_ADDR   = 0x609E          # d0 = VDP address
GAME_START_1   = 0x3578          # CONTINUE  : move.b #$12,$FFF600
GAME_START_2   = 0x35CC          # GAME START: move.b #$12,$FFF600

# $FFFE1E/$FFFE1F are the first bytes past the stock option block and are
# never written by the original (verified on a running emulator)
OPT_NGPLUS     = 0xFE1E          # word: NG+ level chosen in the options screen
OPT_SPECIAL    = 0xFE20          # word: 0 = specials on X/Y/Z, 1 = A/B/C combos

# free work RAM, verified untouched by the stock engine over a long play run
HK_PAD6        = 0xF650          # byte: raw 6-button nibble (active low)
HK_PAD6OK      = 0xF651          # byte: 1 when a 6-button pad answered
HK_CD_X        = 0xF652          # word cooldowns
HK_CD_Y        = 0xF654
HK_CD_Z        = 0xF656
HK_NGPLUS      = 0xF658          # word: New Game+ loop counter
HK_PADFRAME    = 0xF65A          # word: last frame the 6-button probe ran
HK_WEAPREQ     = 0xF65C          # byte: normalised X/Y/Z request (active high)
HK_SUPPORT     = 0xF65E          # word: frames of support fire left
HK_SUPCD       = 0xF660          # word: support-fire volley cooldown
HK_OFSX        = 0xF664          # long: muzzle offset applied by fire_shot
HK_OFSY        = 0xF668
HK_STEPX       = 0xF66C          # long: per-segment step of the beam
HK_STEPY       = 0xF670
HK_ANG         = 0xF674          # word: angle held across a volley
HK_IDX         = 0xF676          # word: volley counter

# free ROM, 0xFF-filled in the original (0x6C97C..0x6E6FF)
NEWCODE        = 0x6C980
NEWCODE_END    = 0x6E700

PAD_PORT       = 0xA10003

def be16(b, o): return struct.unpack('>H', b[o:o+2])[0]
def be32(b, o): return struct.unpack('>I', b[o:o+4])[0]

def stage_ptr(rom, table, stage):
    return be32(rom, table + 4 * stage)

def checksum(rom):
    s = 0
    for o in range(0x200, len(rom), 2):
        s = (s + be16(rom, o)) & 0xFFFF
    return s

def fix_checksum(rom):
    rom = bytearray(rom)
    struct.pack_into('>H', rom, HDR_CHECKSUM, checksum(rom))
    return bytes(rom)
