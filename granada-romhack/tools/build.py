#!/usr/bin/env python3
"""
Granada NG+ rom-hack builder.

    python3 tools/build.py <original Granada ROM> [-o out.gen]

Applies four feature patches, repairs the boot checksum and writes both a
patched ROM and an IPS patch.  Every byte of injected 68000 code is
disassembled back with Capstone and printed with --listing.
"""
import argparse, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import granada as G
import map_tool as M
from asm68k import Asm, push_mask, pop_mask

# ---------------------------------------------------------------- weapons
# Projectile kinds understood by the engine's shot spawner at $9830 are 1..6
# (dispatch table at $9FC0).  Kind 1 is the player's stock cannon.
WEAPON_X_KIND = 1      # Spread Shot  - three stock rounds in a 3-step fan
WEAPON_Y_KIND = 2      # Heavy Shell  - the engine's heavier round
WEAPON_Z_KIND = 1      # Omni Burst   - four rounds, 90 degrees apart

COOLDOWN_X, COOLDOWN_Y, COOLDOWN_Z = 10, 26, 22
DEBUG_PAD = os.environ.get('GRANADA_DEBUG_PAD') == '1'
NGPLUS_MAX = 7         # HP multiplier caps at 8x


def build_code(rom):
    """Assemble the injected code block at G.NEWCODE.  Returns (bytes, labels).

    `rom` is the untouched original, read only to copy the stock options-screen
    dispatch table into the relocated, one-entry-longer copy.
    """
    a = Asm(G.NEWCODE)
    P = G.PAD_PORT

    # ============================ 6-button controller read ===============
    # Replaces the stock 3-button routine at $2050.  The first two halves are
    # byte-for-byte what the original did, so $FFF602/$FFF604 keep their
    # meaning; the extra TH toggles that identify a 6-button pad and read
    # X/Y/Z/Mode run at most once per video frame, so repeated calls inside
    # one frame cannot desynchronise the pad's internal counter.
    a.label('pad6')
    a.movem_push(push_mask(['d0', 'd1']))
    a.move_b_imm_absl(0x00, P); a.nop(); a.nop()
    a.move_b_absl_absw(P, 0xF604)
    a.move_b_imm_absl(0x40, P); a.nop(); a.nop()
    a.move_b_absl_absw(P, 0xF602)
    a.move_w_absw_dn(G.RAM_VINT_CNT, 'd0')
    a.cmp_w_absw_dn(G.HK_PADFRAME, 'd0')
    a.beq_w('pad6_done')
    a.move_w_dn_absw('d0', G.HK_PADFRAME)
    a.move_b_imm_absl(0x00, P); a.nop(); a.nop()      # cycle 2 low
    a.move_b_imm_absl(0x40, P); a.nop(); a.nop()      # cycle 2 high
    a.move_b_imm_absl(0x00, P); a.nop(); a.nop()      # cycle 3 low = pad id
    a.move_b_absl_dn(P, 'd0')
    a.move_b_imm_absl(0x40, P); a.nop(); a.nop()      # cycle 3 high = M X Y Z
    a.move_b_absl_dn(P, 'd1')
    a.move_b_imm_absl(0x00, P); a.nop(); a.nop()      # cycle 4 low
    a.move_b_imm_absl(0x40, P)                        # leave TH high
    if DEBUG_PAD:
        a.move_b_dn_absw('d0', 0xF65E)                # raw pad-id byte
        a.move_b_dn_absw('d1', 0xF65F)                # raw extra-button byte
    a.andi_b_dn(0x0F, 'd0')
    a.bne_w('pad6_none')
    a.move_b_dn_absw('d1', G.HK_PAD6)
    a.move_b_imm_absw(0x01, G.HK_PAD6OK)
    a.bra_w('pad6_done')
    a.label('pad6_none')
    a.move_b_imm_absw(0xFF, G.HK_PAD6)
    a.move_b_imm_absw(0x00, G.HK_PAD6OK)
    a.label('pad6_done')
    a.movem_pop(pop_mask(['d0', 'd1']))
    a.rts()

    # ================================ New Game+ ==========================
    # Entered instead of the "return to title" tail at $4C2C, i.e. once the
    # player has cleared stage 9.  Bumps the loop counter and re-enters the
    # stage initialiser with stage := 0, keeping score, lives and weapons.
    a.label('ngplus')
    a.addq_w_absw(1, G.HK_NGPLUS)
    a.move_w_absw_dn(G.HK_NGPLUS, 'd0')
    a.cmpi_w_dn(NGPLUS_MAX, 'd0')
    a.bls_w('ng_ok')
    a.move_w_imm_absw(NGPLUS_MAX, G.HK_NGPLUS)
    a.label('ng_ok')
    a.move_b_imm_absw(0x00, G.RAM_STAGE)
    a.move_b_imm_absw(0x00, 0xFE05)        # stage mirror used by continue
    a.jmp_l(G.STAGE_INIT)

    # New Game+ counter is cleared whenever the title/option screen runs, so
    # a fresh run always starts at NG+0.
    a.label('ngreset')
    a.move_w_absw_absw(G.OPT_NGPLUS, G.HK_NGPLUS)
    a.move_b_imm_absw(0x00, 0xBEC4)        # the instruction we displaced
    a.jmp_l(0x2EBA)

    # ============================ difficulty scaling =====================
    # Replaces the three instructions at $5BCC that copy an object's hit
    # points out of its template.  HP becomes HP * (NG+ level + 1).
    a.label('hpscale')
    a.move_l_dn_push('d1')
    a.moveq(0, 'd0')
    a.move_w_dispan_dn(G.TPL_HP, 'a2', 'd0')
    a.move_w_absw_dn(G.HK_NGPLUS, 'd1')
    a.addq_w_dn(1, 'd1')
    a.mulu_w('d1', 'd0')
    a.cmpi_l_dn(0x7FFF, 'd0')
    a.bls_w('hp_ok')
    a.move_l_imm_dn(0x7FFF, 'd0')
    a.label('hp_ok')
    a.move_l_pop_dn('d1')
    a.move_l_dn_dispan('d0', 0x1C, 'a1')
    a.jmp_l(G.SPAWN_SET_HP_R)

    # ============================== weapon hook ==========================
    # $96C6 is the player's weapon update and has exactly one caller, so the
    # first two instructions can be relocated here.
    a.label('firehook')
    a.movem_push(push_mask(['d3', 'd4', 'd5', 'd6', 'd7', 'a4', 'a5']))
    a.movea_l_imm(0xFFAB14, 'a4')
    a.bsr_w('xyz')
    a.jmp_l(G.PLAYER_FIRE_R)

    a.label('xyz')
    a.movem_push(push_mask(['d0','d1','d2','d3','d4','d5','d6','d7',
                            'a0','a1','a2','a3','a4','a5','a6']))

    # ---- normalise the weapon buttons into $FFF65C (bit2=X,1=Y,0=Z, high=held)
    a.tst_b_absw(G.HK_PAD6OK)
    a.beq_w('wb3')
    a.move_b_absw_dn(G.HK_PAD6, 'd0')     # 6-button pad: M X Y Z, active low
    a.not_b_dn('d0')
    a.andi_b_dn(0x07, 'd0')
    a.move_b_dn_absw('d0', G.HK_WEAPREQ)
    a.bra_w('wb_done')

    # Fallback for a 3-button pad: the stock game leaves the A+B and B+C
    # combo slots unassigned (see $3BBA/$3BC0 and the option defaults at
    # $2F30/$2F36), and never looks at A+C at all, so all three are free.
    a.label('wb3')
    a.moveq(0, 'd0')
    a.btst_b_imm_absw(4, 0xF604); a.bne_w('nx')     # A
    a.btst_b_imm_absw(4, 0xF602); a.bne_w('nx')     # B
    a.ori_b_imm_dn(4, 'd0')
    a.label('nx')
    a.btst_b_imm_absw(4, 0xF604); a.bne_w('ny')     # A
    a.btst_b_imm_absw(5, 0xF602); a.bne_w('ny')     # C
    a.ori_b_imm_dn(2, 'd0')
    a.label('ny')
    a.btst_b_imm_absw(4, 0xF602); a.bne_w('nz')     # B
    a.btst_b_imm_absw(5, 0xF602); a.bne_w('nz')     # C
    a.ori_b_imm_dn(1, 'd0')
    a.label('nz')
    a.move_b_dn_absw('d0', G.HK_WEAPREQ)
    a.label('wb_done')

    # ---- cooldowns -------------------------------------------------------
    for cd, nxt in ((G.HK_CD_X, 'cd1'), (G.HK_CD_Y, 'cd2'), (G.HK_CD_Z, 'cd3')):
        a.tst_w_absw(cd); a.beq_w(nxt); a.subq_w_absw(1, cd); a.label(nxt)

    # the player must be in a state where it may act (same guard $96C6 uses)
    a.move_w_absw_dn(G.RAM_PSTATE, 'd0')
    a.cmpi_w_dn(2, 'd0')
    a.bhi_w('xyz_end')

    def shot(angle_delta, kind):
        a.move_l_dispan_dn(0x14, 'a4', 'd3')
        if angle_delta:
            a.addi_l_dn(angle_delta & 0x3F, 'd3')
            a.andi_l_dn(0x3F, 'd3')
        a.moveq(kind, 'd2')
        a.bsr_w('fireshot')

    # X - Spread Shot: three rounds in a fan
    a.btst_b_imm_absw(2, G.HK_WEAPREQ); a.beq_w('do_y')
    a.tst_w_absw(G.HK_CD_X);            a.bne_w('do_y')
    a.move_w_imm_absw(COOLDOWN_X, G.HK_CD_X)
    for d in (0, 3, -3): shot(d, WEAPON_X_KIND)
    a.label('do_y')
    # Y - Heavy Shell: one heavy round, long cooldown
    a.btst_b_imm_absw(1, G.HK_WEAPREQ); a.beq_w('do_z')
    a.tst_w_absw(G.HK_CD_Y);            a.bne_w('do_z')
    a.move_w_imm_absw(COOLDOWN_Y, G.HK_CD_Y)
    shot(0, WEAPON_Y_KIND)
    a.label('do_z')
    # Z - Omni Burst: four rounds, 90 degrees apart
    a.btst_b_imm_absw(0, G.HK_WEAPREQ); a.beq_w('xyz_end')
    a.tst_w_absw(G.HK_CD_Z);            a.bne_w('xyz_end')
    a.move_w_imm_absw(COOLDOWN_Z, G.HK_CD_Z)
    for d in (0, 16, 32, 48): shot(d, WEAPON_Z_KIND)
    a.label('xyz_end')
    a.movem_pop(pop_mask(['d0','d1','d2','d3','d4','d5','d6','d7',
                          'a0','a1','a2','a3','a4','a5','a6']))
    a.rts()

    # ====================== GAME MODE row on the options screen ==========
    # Seeds the NG+ level when a game begins.  Replaces the
    # "move.b #$12,$FFF600" that both the CONTINUE and GAME START paths run.
    a.label('applyng')
    a.move_b_imm_absl(0x12, 0xFFF600)
    a.move_w_absw_absw(G.OPT_NGPLUS, G.HK_NGPLUS)
    a.rts()

    # Draw the value cell of the GAME MODE row.
    a.label('ngval')
    ALL = ['d0','d1','d2','d3','d4','d5','d6','d7','a0','a1','a2','a3','a4','a5','a6']
    a.movem_push(push_mask(ALL))
    a.move_l_imm_dn(G.OPT_CURSOR_VDP + 6 * 0x80 + 0x16, 'd0')   # value column
    a.jsr_l(G.SET_VDP_ADDR)
    a.moveq(0, 'd0')
    a.move_w_absw_dn(G.OPT_NGPLUS, 'd0')
    a.andi_l_dn(0x07, 'd0')
    a.asl_l_imm(3, 'd0')                      # 8-byte records
    a.movea_l_imm(0, 'a0')                    # patched below to str_vals
    a.adda_l_dn('d0', 'a0')
    a.jsr_l(G.DRAW_STRING)
    a.movem_pop(pop_mask(ALL))
    a.rts()

    # Draw the whole extra row.  Replaces "moveq #0,d7 / move.l #$C650,d0"
    # at the tail of the option screen's draw code, so it must leave those
    # two registers exactly as that code did.
    a.label('ngrow')
    SOME = ['d0','d1','d2','d3','d4','d5','d6','a0','a1','a2','a3','a4','a5','a6']
    a.movem_push(push_mask(SOME))
    a.move_l_imm_dn(G.OPT_CURSOR_VDP + 6 * 0x80 + 2, 'd0')      # label column
    a.jsr_l(G.SET_VDP_ADDR)
    a.movea_l_imm(0, 'a0')                    # patched below to str_label
    a.jsr_l(G.DRAW_STRING)
    a.bsr_w('ngval')
    a.movem_pop(pop_mask(SOME))
    a.moveq(0, 'd7')
    a.move_l_imm_dn(G.OPT_CURSOR_VDP, 'd0')
    a.rts()

    # Row handler, reached through the relocated dispatch table.  Left/right
    # move through NORMAL and NG+1..NG+7, mirroring how the RANK row works.
    a.label('ngedit')
    a.move_w_absw_dn(G.OPT_NGPLUS, 'd5')
    a.move_b_absw_dn(0xF602, 'd0')
    a.btst_imm_dn(2, 'd0')                    # LEFT (active low)
    a.bne_w('ng_nl')
    a.subq_w_absw(1, G.OPT_NGPLUS)
    a.tst_w_absw(G.OPT_NGPLUS)
    a.bge_w('ng_nl')
    a.move_w_imm_absw(NGPLUS_MAX, G.OPT_NGPLUS)
    a.label('ng_nl')
    a.btst_imm_dn(3, 'd0')                    # RIGHT
    a.bne_w('ng_nr')
    a.addq_w_absw(1, G.OPT_NGPLUS)
    a.cmpi_w_absw(NGPLUS_MAX, G.OPT_NGPLUS)
    a.bls_w('ng_nr')
    a.move_w_imm_absw(0, G.OPT_NGPLUS)
    a.label('ng_nr')
    a.cmp_w_absw_dn(G.OPT_NGPLUS, 'd5')
    a.beq_w('ng_done')
    a.move_w_absw_absw(G.OPT_NGPLUS, G.HK_NGPLUS)
    a.bsr_w('ngval')
    a.label('ng_done')
    a.jmp_l(G.OPT_LOOP)

    # fire one projectile: d2 = kind, d3 = angle (0..63), a4 = player struct.
    # Argument layout copied from the stock call site at $97EC-$9820.
    a.label('fireshot')
    a.movem_push(push_mask(['d4', 'd5', 'd6', 'd7', 'a0']))
    a.move_l_dn_dm('d3', 'd0')
    a.asl_l_imm(2, 'd0')
    a.movea_l_imm(G.ANGLE_TBL_SIN, 'a0')
    a.adda_l_dn('d0', 'a0')
    a.move_l_ind_dn('a0', 'd5')
    a.suba_l_imm(0x100, 'a0')
    a.move_l_ind_dn('a0', 'd4')
    a.move_l_dn_dm('d4', 'd7'); a.neg_l('d7')
    a.move_l_dn_dm('d5', 'd6')
    a.move_l_imm_push(1)                       # arg7
    a.clr_l_push()                             # arg6
    a.move_l_dn_push('d3')                     # arg5 angle
    a.move_l_dn_dm('d4', 'd0'); a.asr_l_imm(4, 'd0'); a.move_l_dn_push('d0')
    a.move_l_dn_dm('d5', 'd0'); a.asr_l_imm(4, 'd0'); a.move_l_dn_push('d0')
    a.move_l_dn_dm('d6', 'd0'); a.asl_l_imm(2, 'd0')
    a.add_l_dispan_dn(8, 'a4', 'd0'); a.add_l_dn_dm('d4', 'd0'); a.move_l_dn_push('d0')
    a.move_l_dn_dm('d7', 'd0'); a.asl_l_imm(2, 'd0')
    a.add_l_dispan_dn(4, 'a4', 'd0'); a.add_l_dn_dm('d5', 'd0'); a.move_l_dn_push('d0')
    a.move_l_dn_push('d2')                     # arg0 kind
    a.jsr_l(G.SPAWN_SHOT)
    a.lea_a7_off(0x20)
    a.movem_pop(pop_mask(['d4', 'd5', 'd6', 'd7', 'a0']))
    a.rts()

    # relocated dispatch table: the stock seven rows, GAME MODE inserted
    # before EXIT so EXIT stays the last entry
    a.label('opttbl')
    rows = [G.be32(rom, G.OPT_DISPATCH + 4 * i) for i in range(G.OPT_ROWS)]
    for t in rows[:6]:
        a.raw(struct.pack('>I', t))
    a.raw(b'\x00\x00\x00\x00')          # placeholder for 'ngedit'
    a.raw(struct.pack('>I', rows[6]))     # EXIT

    a.label('str_label')
    a.raw(b'GAME MODE\x00')
    a.label('str_vals')
    for i in range(8):
        txt = b'NORMAL' if i == 0 else (b'NG+%d  ' % i)
        a.raw(txt + b'\x00\x00')

    code = bytearray(a.assemble())
    L = a.labels
    base = G.NEWCODE
    # fill the placeholders that needed label addresses
    struct.pack_into('>I', code, L['opttbl'] + 24 - base, L['ngedit'])
    for lbl, sym in (('ngval', 'str_vals'), ('ngrow', 'str_label')):
        # patch the movea.l #0,a0 inside each routine
        i = code.index(b'\x20\x7c\x00\x00\x00\x00', L[lbl] - base)
        struct.pack_into('>I', code, i + 2, L[sym])
    return bytes(code), L


# ---------------------------------------------------------------- map I/O
def terrain_attr(rom, stage, wx, wy):
    """Attribute byte of the map cell under world position (wx, wy)."""
    bm = G.stage_ptr(rom, G.TBL_BLOCKMAP, stage)
    bd = G.stage_ptr(rom, G.TBL_BLOCKDEFS, stage)
    at = G.stage_ptr(rom, G.TBL_TILEATTR, stage)
    tx, ty = (wx >> 3) & 0xFF, (wy >> 3) & 0xFF
    blk = rom[bm + ((((ty >> 2) << 6) | (tx >> 2)) & 0xFFF)]
    sub = rom[bd + (blk << 4) + ((ty & 3) << 2) + (tx & 3)]
    return rom[at + sub]


def spawn_records(rom, stage):
    base = G.stage_ptr(rom, G.TBL_SPAWNLIST, stage)
    return base, [tuple(G.be16(rom, base + 8 * i + 2 * f) for f in range(4))
                  for i in range(G.SPAWN_SLOTS)]


def place_second_boss(rom, stage=0):
    """Clone the stage boss into a free spawn slot on equally passable ground."""
    rom = bytearray(rom)
    base, recs = spawn_records(rom, stage)
    bosses = [(i, r) for i, r in enumerate(recs) if r[0] == 0x25]
    if len(bosses) != 1:
        raise SystemExit("expected exactly one type-0x25 boss in stage %d" % stage)
    slot, (kind, bx, by, param) = bosses[0]
    free = [i for i, r in enumerate(recs) if r[0] == 0]
    if not free:
        raise SystemExit("no free spawn slot in stage %d" % stage)
    want = terrain_attr(rom, stage, bx, by)
    # Prefer a spot on the same ground, far enough that the two bosses do not
    # overlap but close enough that they fight as a pair.
    best = None
    for dx in range(-0x140, 0x141, 8):
        for dy in range(-0x140, 0x141, 8):
            dist = max(abs(dx), abs(dy))
            if not (0xA0 <= dist <= 0x120):
                continue
            x, y = (bx + dx) & 0x7FF, (by + dy) & 0x7FF
            if all(terrain_attr(rom, stage, (x + ox) & 0x7FF, (y + oy) & 0x7FF) == want
                   for ox in (-0x10, 0, 0x10) for oy in (-0x10, 0, 0x10)):
                score = (abs(dy), abs(abs(dx) - 0x100))   # favour same row
                if best is None or score < best[0]:
                    best = (score, x, y)
    if best is None:
        raise SystemExit("found no passable position for the second boss")
    _, x, y = best
    off = base + 8 * free[0]
    struct.pack_into('>HHHH', rom, off, kind, x, y, param)
    return bytes(rom), (free[0], x, y, slot, bx, by)


BLOCKED_MASK = 0x50          # bits the engine isolates when building collision


def tile_reach_pct(rom, stage):
    """Share of the 256x256 tile world the player can actually walk to."""
    _g, _seen, _st, n = M.reachable(rom, stage)
    return 100.0 * n / 65536


def walkable_blocks(rom, stage, need=8):
    """Block ids the player can cross: at least `need` of 16 tiles are open."""
    _bm, bd, at = (G.stage_ptr(rom, G.TBL_BLOCKMAP, stage),
                   G.stage_ptr(rom, G.TBL_BLOCKDEFS, stage),
                   G.stage_ptr(rom, G.TBL_TILEATTR, stage))
    out = set()
    for blk in range(256):
        openn = sum(1 for i in range(16)
                    if not (rom[at + rom[bd + (blk << 4) + i]] & BLOCKED_MASK))
        if openn >= need:
            out.add(blk)
    return out


def fill_blocks(rom, stage):
    """Block ids that are fully open - what a carved corridor is paved with."""
    return walkable_blocks(rom, stage, need=16)


def block_grid(rom, stage):
    bm = G.stage_ptr(rom, G.TBL_BLOCKMAP, stage)
    return bytearray(rom[bm:bm + 0x1000]), bm


def _reach_blocks(grid, walk, start):
    seen = bytearray(0x1000)
    if grid[start] not in walk:
        return seen, 0
    seen[start] = 1
    stack = [start]
    n = 1
    while stack:
        i = stack.pop()
        x, y = i & 63, i >> 6
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            j = (((y + dy) & 63) << 6) | ((x + dx) & 63)
            if not seen[j] and grid[j] in walk:
                seen[j] = 1; n += 1; stack.append(j)
    return seen, n


def expand_playable_area(rom, stage, min_component=96, max_rounds=32):
    """
    Several Granada stages hide large stretches of perfectly open ground that
    no route reaches from the player's start.  This finds those sealed pockets
    and carves the shortest possible corridor to each, rewriting only cells
    that are solid today, so the stage keeps its own artwork.
    Measured the way the engine sees the world: per tile, not per block.
    """
    from collections import deque
    rom = bytearray(rom)
    bm = G.stage_ptr(rom, G.TBL_BLOCKMAP, stage)
    openblk = fill_blocks(rom, stage)
    counts = {}
    for blk in rom[bm:bm + 0x1000]:
        if blk in openblk:
            counts[blk] = counts.get(blk, 0) + 1
    if not counts:
        return bytes(rom), 0, tile_reach_pct(rom, stage), tile_reach_pct(rom, stage)
    fill = max(counts, key=counts.get)
    before = tile_reach_pct(rom, stage)
    carved_blocks = set()

    for _ in range(max_rounds):
        grid, seen, start, _n = M.reachable(rom, stage)
        solid = bytearray(65536)
        reach = bytearray(65536)
        for ty in range(256):
            row = grid[ty]; srow = seen[ty]; base = ty << 8
            for tx in range(256):
                solid[base | tx] = 1 if (row[tx] & M.BLOCKED_MASK) else 0
                reach[base | tx] = 1 if srow[tx] else 0

        # largest open-but-unreachable component
        comp = bytearray(65536)
        best = None
        for i in range(65536):
            if solid[i] or reach[i] or comp[i]:
                continue
            stack = [i]; comp[i] = 1; cells = [i]
            while stack:
                j = stack.pop()
                x, y = j & 255, j >> 8
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    k = (((y + dy) & 255) << 8) | ((x + dx) & 255)
                    if not comp[k] and not solid[k] and not reach[k]:
                        comp[k] = 1; cells.append(k); stack.append(k)
            if best is None or len(cells) > len(best):
                best = cells
        if best is None or len(best) < min_component:
            break
        target = set(best)

        # 0-1 BFS out of the reachable region; stepping into solid ground costs 1
        INF = 1 << 30
        dist = [INF] * 65536
        prev = [-1] * 65536
        dq = deque()
        for i in range(65536):
            if reach[i]:
                dist[i] = 0; dq.append(i)
        hit = -1
        while dq:
            i = dq.popleft()
            if i in target:
                hit = i; break
            d = dist[i]
            x, y = i & 255, i >> 8
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                j = (((y + dy) & 255) << 8) | ((x + dx) & 255)
                w = solid[j]
                if d + w < dist[j]:
                    dist[j] = d + w; prev[j] = i
                    (dq.appendleft if w == 0 else dq.append)(j)
        if hit < 0:
            break

        i = hit
        touched = False
        while i != -1 and dist[i] > 0:
            if solid[i]:
                tx, ty = i & 255, i >> 8
                bi = (((ty >> 2) & 63) << 6) | ((tx >> 2) & 63)
                if rom[bm + bi] != fill:
                    rom[bm + bi] = fill
                    carved_blocks.add(bi); touched = True
            i = prev[i]
        if not touched:
            break

    rom = bytes(rom)
    return rom, len(carved_blocks), before, tile_reach_pct(rom, stage)


def widen_arena(rom, stage, cx, cy, radius_tiles):
    """Clear the solid cells inside a square, leaving existing open scenery."""
    rom = bytearray(rom)
    grid, bm = block_grid(rom, stage)
    walk = walkable_blocks(rom, stage)
    openblk = fill_blocks(rom, stage)
    counts = {}
    for b in grid:
        if b in openblk: counts[b] = counts.get(b, 0) + 1
    if not counts:
        return bytes(rom), 0
    fill = max(counts, key=counts.get)
    ctx, cty = ((cx >> 3) & 0xFF) >> 2, ((cy >> 3) & 0xFF) >> 2
    r = radius_tiles >> 2
    changed = 0
    for by_ in range(cty - r, cty + r + 1):
        for bx_ in range(ctx - r, ctx + r + 1):
            i = (((by_ & 63) << 6) | (bx_ & 63))
            if grid[i] not in walk:
                grid[i] = fill; changed += 1
    rom[bm:bm + 0x1000] = grid
    return bytes(rom), changed


# ---------------------------------------------------------------- patching
def apply_hook(rom, addr, target, blank):
    """jmp target.l at addr, then NOPs out the rest of the displaced bytes."""
    rom = bytearray(rom)
    struct.pack_into('>HI', rom, addr, 0x4EF9, target)
    for o in range(addr + 6, addr + blank, 2):
        struct.pack_into('>H', rom, o, 0x4E71)
    return bytes(rom)


def make_ips(orig, new):
    out = bytearray(b'PATCH')
    i = 0
    while i < len(new):
        if i < len(orig) and new[i] == orig[i]:
            i += 1; continue
        start = i
        run = 0
        while i < len(new) and run < 8:
            if i < len(orig) and new[i] == orig[i]:
                run += 1
            else:
                run = 0
            i += 1
            if i - start >= 0xFFFF:
                break
        end = i - run
        chunk = new[start:end]
        while chunk:
            piece, chunk = chunk[:0xFFFF], chunk[0xFFFF:]
            off = start
            if off >= 0x1000000:
                raise SystemExit("IPS cannot address offset %X" % off)
            out += bytes([off >> 16 & 0xFF, off >> 8 & 0xFF, off & 0xFF])
            out += bytes([len(piece) >> 8 & 0xFF, len(piece) & 0xFF]) + piece
            start += len(piece)
    out += b'EOF'
    return bytes(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('rom')
    ap.add_argument('-o', '--out', default='granada_ngplus.gen')
    ap.add_argument('--ips', default=None)
    ap.add_argument('--listing', action='store_true')
    ap.add_argument('--no-map', action='store_true',
                    help='skip the stage-1 arena enlargement')
    ap.add_argument('--test-autoclear', action='store_true',
                    help='TEST BUILD: stop objects registering as stage targets, '
                         'so every stage self-clears in ~80 frames')
    args = ap.parse_args()

    orig = open(args.rom, 'rb').read()
    if len(orig) != G.ROM_SIZE:
        raise SystemExit("expected a 512 KiB Granada ROM, got %d bytes" % len(orig))
    if G.checksum(orig) != G.be16(orig, G.HDR_CHECKSUM):
        print("warning: source ROM checksum already differs from its header")
    rom = orig

    code, labels = build_code(orig)
    if G.NEWCODE + len(code) > G.NEWCODE_END:
        raise SystemExit("injected code overflows the free ROM block")
    # the free block must really be free
    if any(b != 0xFF for b in orig[G.NEWCODE:G.NEWCODE + len(code)]):
        raise SystemExit("free ROM block at %06X is not empty" % G.NEWCODE)
    rom = bytearray(rom)
    rom[G.NEWCODE:G.NEWCODE + len(code)] = code
    rom = bytes(rom)

    # 1. 6-button controller read
    rom = apply_hook(rom, G.PAD_READ, labels['pad6'], 8)
    # 2. New Game+ instead of "return to title" after stage 9
    rom = apply_hook(rom, G.GAME_COMPLETE, labels['ngplus'], 10)
    # 2b. clear the NG+ counter whenever the title screen runs
    rom = apply_hook(rom, 0x2EB4, labels['ngreset'], 6)
    # 3. enemy hit points scale with the NG+ level
    rom = apply_hook(rom, G.SPAWN_SET_HP, labels['hpscale'], 10)
    # 4. X / Y / Z weapons
    rom = apply_hook(rom, G.PLAYER_FIRE, labels['firehook'], 10)

    # 4b. GAME MODE row on the options screen
    rom = bytearray(rom)
    #   cursor now wraps over eight rows instead of seven
    rom[0x384B] = G.OPT_ROWS                       # moveq #7,d7 (wrap up)
    struct.pack_into('>I', rom, 0x385A, G.OPT_ROWS)  # cmpi.l #7,d7 (wrap down)
    #   relocate the row dispatch table: lea $3B84(pc),a0 + nop -> movea.l #tbl,a0
    struct.pack_into('>HI', rom, 0x38AC, 0x207C, labels['opttbl'])
    #   EXIT moves one row down to make space
    struct.pack_into('>I', rom, 0x3776, G.OPT_EXIT_VDP + 0x80)
    #   draw the new row: moveq #0,d7 / move.l #$C650,d0 -> jsr ngrow + nop
    struct.pack_into('>HIH', rom, 0x37E2, 0x4EB9, labels['ngrow'], 0x4E71)
    #   seed the NG+ level when a game begins (CONTINUE and GAME START)
    for site in (G.GAME_START_1, G.GAME_START_2):
        struct.pack_into('>HIH', rom, site, 0x4EB9, labels['applyng'], 0x4E71)
    rom = bytes(rom)
    print("options screen: GAME MODE row added (NORMAL, NG+1..NG+%d)" % NGPLUS_MAX)

    # 5. a second boss on stage 1
    rom, info = place_second_boss(rom, 0)
    print("stage 1: second boss (type 25) in slot %d at x=%04X y=%04X "
          "(original boss slot %d at x=%04X y=%04X)" % info)

    # 6. maps: open the boss arena, then reconnect every stage's sealed-off
    #    ground so far more of each 256x256 world is actually playable
    if not args.no_map:
        slot, bx2, by2, _oslot, bx, by = info
        rom, changed = widen_arena(rom, 0, (bx + bx2) // 2, (by + by2) // 2, 20)
        print("stage 1: boss arena opened up, %d solid blocks cleared" % changed)
        print("map expansion (share of each 256x256 tile world the player can reach):")
        for st in range(9):
            rom, carved, before, after = expand_playable_area(rom, st)
            print("   stage %d: %5.1f%% -> %5.1f%%   (%d corridor blocks carved)"
                  % (st + 1, before, after, carved))

    if args.test_autoclear:
        rom = bytearray(rom)                      # NOP "addq.w #1,$B434"
        struct.pack_into('>HH', rom, 0x5BDE, 0x4E71, 0x4E71)
        rom = bytes(rom)
        print("TEST BUILD: stage targets disabled, stages self-clear")

    rom = G.fix_checksum(rom)
    open(args.out, 'wb').write(rom)
    ips = args.ips or os.path.splitext(args.out)[0] + '.ips'
    open(ips, 'wb').write(make_ips(orig, rom))
    print("wrote %s (%d bytes) and %s" % (args.out, len(rom), ips))
    print("injected code: %06X-%06X (%d bytes), checksum repaired to %04X"
          % (G.NEWCODE, G.NEWCODE + len(code), len(code), G.be16(rom, G.HDR_CHECKSUM)))

    if args.listing:
        import capstone
        md = capstone.Cs(capstone.CS_ARCH_M68K, capstone.CS_MODE_M68K_000)
        rev = {v: k for k, v in labels.items()}
        for i in md.disasm(code, G.NEWCODE):
            if i.address in rev:
                print("\n%s:" % rev[i.address])
            print("  %06X  %-22s %s %s" % (i.address, i.bytes.hex(), i.mnemonic, i.op_str))


if __name__ == '__main__':
    main()
