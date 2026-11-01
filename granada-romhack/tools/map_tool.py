#!/usr/bin/env python3
"""
Granada stage-map tool.

The engine stores every stage as three layers (see docs/ENGINE.md):

    block map    64 x 64 bytes      one block id per 4x4 tile cell
    block defs   256 x 16 bytes     the 4x4 tile ids that make up each block
    tile attrs   256 bytes          one attribute byte per tile id

which together describe a 256 x 256 tile world.

    python3 tools/map_tool.py info    <rom>
    python3 tools/map_tool.py render  <rom> <stage> <out.png>
    python3 tools/map_tool.py export  <rom> <stage> <out.bin>
    python3 tools/map_tool.py import  <rom> <stage> <in.bin> <out.gen>
"""
import os, sys, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import granada as G

BLOCKED_MASK = 0x50     # the bits the engine itself isolates when it builds
                        # its collision buffer at $FF0000 (see $5FE4-$6060)

def layers(rom, stage):
    return (G.stage_ptr(rom, G.TBL_BLOCKMAP, stage),
            G.stage_ptr(rom, G.TBL_BLOCKDEFS, stage),
            G.stage_ptr(rom, G.TBL_TILEATTR, stage))

def attr_grid(rom, stage):
    """256x256 grid of attribute bytes, indexed [ty][tx]."""
    bm, bd, at = layers(rom, stage)
    g = []
    for ty in range(256):
        row = bytearray(256)
        for tx in range(256):
            blk = rom[bm + (((ty >> 2) << 6) | (tx >> 2))]
            sub = rom[bd + (blk << 4) + ((ty & 3) << 2) + (tx & 3)]
            row[tx] = rom[at + sub]
        g.append(row)
    return g

def start_pos(rom, stage):
    b = G.TBL_STARTPOS + 16 * stage
    return G.be32(rom, b), G.be32(rom, b + 4)

def reachable(rom, stage):
    """Flood fill the open cells from the player's start tile."""
    g = attr_grid(rom, stage)
    sx, sy = start_pos(rom, stage)
    start = ((sx >> 3) & 0xFF, (sy >> 3) & 0xFF)
    seen = [[False] * 256 for _ in range(256)]
    if g[start[1]][start[0]] & BLOCKED_MASK:
        return g, seen, start, 0
    stack = [start]
    seen[start[1]][start[0]] = True
    n = 1
    while stack:
        x, y = stack.pop()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = (x + dx) & 0xFF, (y + dy) & 0xFF
            if not seen[ny][nx] and not (g[ny][nx] & BLOCKED_MASK):
                seen[ny][nx] = True; n += 1; stack.append((nx, ny))
    return g, seen, start, n

def cmd_info(rom):
    print("stage  blockmap  blockdefs  tileattr  spawns  music  start(tile)  "
          "open%%  reachable%%")
    for s in range(9):
        bm, bd, at = layers(rom, s)
        sx, sy = start_pos(rom, s)
        g, seen, st, n = reachable(rom, s)
        openn = sum(1 for r in g for v in r if not (v & BLOCKED_MASK))
        print("  %d    %06X    %06X     %06X   %06X  %04X   %3d,%-3d     "
              "%5.1f  %5.1f" % (
              s, bm, bd, at, G.stage_ptr(rom, G.TBL_SPAWNLIST, s),
              G.be16(rom, G.TBL_MUSIC + 2 * s), st[0], st[1],
              100 * openn / 65536, 100 * n / 65536))

def cmd_render(rom, stage, out):
    from PIL import Image
    g, seen, st, n = reachable(rom, stage)
    img = Image.new('RGB', (256, 256))
    px = img.load()
    for ty in range(256):
        for tx in range(256):
            v = g[ty][tx]
            if v & BLOCKED_MASK:
                px[tx, ty] = (40, 40, 52)                    # solid
            elif seen[ty][tx]:
                px[tx, ty] = (70 + (v * 7) % 120, 150, 90)   # open & reachable
            else:
                px[tx, ty] = (120, 70, 70)                   # open but cut off
    for dx in range(-2, 3):
        for dy in range(-2, 3):
            px[(st[0] + dx) & 0xFF, (st[1] + dy) & 0xFF] = (255, 230, 60)
    base, recs = (G.stage_ptr(rom, G.TBL_SPAWNLIST, stage),
                  [tuple(G.be16(rom, G.stage_ptr(rom, G.TBL_SPAWNLIST, stage) + 8 * i + 2 * f)
                         for f in range(4)) for i in range(G.SPAWN_SLOTS)])
    for kind, x, y, _p in recs:
        if not kind:
            continue
        tpl = G.OBJ_TEMPLATES + kind * 32
        boss = G.be16(rom, tpl + G.TPL_SCORE) >= 0x2710
        col = (255, 70, 70) if boss else (90, 160, 255)
        r = 3 if boss else 1
        for dx in range(-r, r + 1):
            for dy in range(-r, r + 1):
                px[((x >> 3) + dx) & 0xFF, ((y >> 3) + dy) & 0xFF] = col
    img.resize((768, 768), Image.NEAREST).save(out)
    print("stage %d -> %s  (yellow = player start, red = boss, blue = object, "
          "dark = solid, brown = open but unreachable)" % (stage, out))

def cmd_export(rom, stage, out):
    bm, _, _ = layers(rom, stage)
    open(out, 'wb').write(rom[bm:bm + 0x1000])
    print("wrote 4096-byte 64x64 block map of stage %d to %s" % (stage, out))

def cmd_import(rom_path, stage, src, out):
    rom = bytearray(open(rom_path, 'rb').read())
    data = open(src, 'rb').read()
    if len(data) != 0x1000:
        raise SystemExit("block map must be exactly 4096 bytes")
    bm, _, _ = layers(rom, int(stage))
    rom[bm:bm + 0x1000] = data
    rom = G.fix_checksum(bytes(rom))
    open(out, 'wb').write(rom)
    print("stage %s block map replaced, checksum repaired -> %s" % (stage, out))

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); raise SystemExit(1)
    cmd = sys.argv[1]
    if cmd == 'import':
        cmd_import(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    else:
        rom = open(sys.argv[2], 'rb').read()
        if cmd == 'info':     cmd_info(rom)
        elif cmd == 'render': cmd_render(rom, int(sys.argv[3]), sys.argv[4])
        elif cmd == 'export': cmd_export(rom, int(sys.argv[3]), sys.argv[4])
        else: print(__doc__)
