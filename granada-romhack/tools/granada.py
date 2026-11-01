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
TPL_SCORE      = 0x06            # word, score value
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
ANGLE_TBL_SIN  = 0x8FC6          # 64 longs
ANGLE_TBL_COS  = 0x8EC6          # 64 longs (= SIN - 0x100)

# ---- RAM ----------------------------------------------------------------
RAM_STAGE      = 0xABE1          # .w short form of $FFABE1, byte: stage 0-8
RAM_PLAYER     = 0xAB14          # player struct; +4 X, +8 Y, +0x14 angle
RAM_PSTATE     = 0xAB16          # player state (<=2 means "can act")
RAM_TARGETS    = 0xB434          # remaining stage targets
RAM_VINT_CNT   = 0xBEC0          # incremented every VBlank
RAM_LIVES      = 0xFE06

# free work RAM, verified untouched by the stock engine over a long play run
HK_PAD6        = 0xF650          # byte: raw 6-button nibble (active low)
HK_PAD6OK      = 0xF651          # byte: 1 when a 6-button pad answered
HK_CD_X        = 0xF652          # word cooldowns
HK_CD_Y        = 0xF654
HK_CD_Z        = 0xF656
HK_NGPLUS      = 0xF658          # word: New Game+ loop counter
HK_PADFRAME    = 0xF65A          # word: last frame the 6-button probe ran
HK_WEAPREQ     = 0xF65C          # byte: normalised X/Y/Z request (active high)

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
