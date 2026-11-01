#!/usr/bin/env python3
"""
Automated checks for the Granada rom-hack, run against a real Mega Drive
emulator (Genesis Plus GX through stable-retro, headless).

    pip install stable-retro pillow
    python3 tools/build.py <orig.gen> -o /tmp/hack.gen
    python3 tools/build.py <orig.gen> --test-autoclear -o /tmp/auto.gen
    python3 tools/testbench.py /tmp/hack.gen /tmp/auto.gen
"""
import os, shutil, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from emu import Granada
from drive import boot_to_stage

FAIL = []

def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + (("   " + detail) if detail else ""))
    if not cond:
        FAIL.append(name)

def live_objects(gr):
    return [(i, gr.rl(0xFF8000 + 0x20 * i), gr.rl(0xFF8000 + 0x20 * i + 0x1C))
            for i in range(128) if gr.rl(0xFF8000 + 0x20 * i)]

def projectile_kinds(gr):
    out = []
    for i in range(88):
        k = gr.rl(0xFFA000 + 0x20 * i)
        if 1 <= k <= 6:
            out.append(k)
    return out

def run_feature_tests(rom):
    print("\n[1] boot, stage 1 contents, weapons  (%s)" % os.path.basename(rom))
    gr = Granada(rom)
    gr.step(600)
    check("ROM passes its own boot checksum and reaches the title",
          gr.rb(0xFFF600) == 0x06, "mode=%02X" % gr.rb(0xFFF600))
    check("New Game+ counter starts at 0", gr.rw(0xFFF658) == 0)
    boot_to_stage(gr)
    check("gameplay mode entered", gr.rb(0xFFF600) == 0x12)
    objs = live_objects(gr)
    bosses = [o for o in objs if o[1] == 0x25]
    check("stage 1 carries two bosses", len(bosses) == 2,
          "slots %s" % [b[0] for b in bosses])
    check("stage target counter raised to 19", gr.rw(0xFFB434) == 19,
          "targets=%d" % gr.rw(0xFFB434))
    check("boss hit points unscaled on the first loop",
          all(b[2] == 0x40 for b in bosses), "hp=%s" % [b[2] for b in bosses])

    # weapons, through the 3-button fallback binding
    def volley(btns):
        gr.press(); gr.step(100)
        gr.press(*btns); gr.step(6)
        peak = {}
        for _ in range(25):
            gr.step(2)
            for k in set(projectile_kinds(gr)):
                peak[k] = max(peak.get(k, 0), projectile_kinds(gr).count(k))
        return peak
    base = volley(('A',))
    req_idle = gr.rb(0xFFF65C)
    spread = volley(('A', 'B'))
    req_x = gr.rb(0xFFF65C)
    heavy = volley(('A', 'C'))
    omni = volley(('B', 'C'))
    check("weapon-request byte decodes A+B as the X weapon",
          req_idle & 4 == 0 and req_x & 4 == 4,
          "idle=%02X A+B=%02X" % (req_idle, req_x))
    check("X / Spread Shot puts more rounds in the air than the stock gun",
          spread.get(1, 0) > base.get(1, 0),
          "stock=%d spread=%d" % (base.get(1, 0), spread.get(1, 0)))
    check("Y / Heavy Shell spawns the engine's kind-2 projectile",
          heavy.get(2, 0) > 0, "kind2=%d" % heavy.get(2, 0))
    check("Z / Omni Burst fires without the stock trigger held",
          omni.get(1, 0) > 0, "kind1=%d" % omni.get(1, 0))

def tap(gr, *btns, hold=6, gap=16):
    gr.press(*btns); gr.step(hold)
    gr.press();      gr.step(gap)


def enter_options(gr):
    gr.step(600)
    tap(gr, 'START', gap=40)     # title -> menu
    tap(gr, 'DOWN')              # GAME START -> OPTIONS
    tap(gr, 'START', gap=60)


def run_options_test(rom):
    print("\n[3] GAME MODE row on the options screen  (%s)" % os.path.basename(rom))
    gr = Granada(rom)
    enter_options(gr)
    check("options screen reached, GAME MODE defaults to NORMAL",
          gr.rb(0xFFF600) == 0x06 and gr.rw(0xFFFE1E) == 0,
          "mode=%02X value=%d" % (gr.rb(0xFFF600), gr.rw(0xFFFE1E)))

    # the stock rows must still work after the dispatch table was relocated
    stock0 = gr.rw(0xFFFE08)                 # GRANADA (lives)
    tap(gr, 'RIGHT')
    check("stock row 0 (GRANADA) still responds", gr.rw(0xFFFE08) != stock0,
          "%d -> %d" % (stock0, gr.rw(0xFFFE08)))
    tap(gr, 'DOWN')
    stock1 = gr.rw(0xFFFE06)                 # RANK
    tap(gr, 'RIGHT')
    check("stock row 1 (RANK) still responds", gr.rw(0xFFFE06) != stock1,
          "%d -> %d" % (stock1, gr.rw(0xFFFE06)))

    # down to the new row (cursor is on row 1 now)
    for _ in range(5):
        tap(gr, 'DOWN')
    tap(gr, 'RIGHT')
    v1 = gr.rw(0xFFFE1E)
    check("GAME MODE steps up off NORMAL", v1 > 0, "value=%d" % v1)
    check("choosing a mode seeds the live NG+ level", gr.rw(0xFFF658) == v1,
          "setting=%d live=%d" % (v1, gr.rw(0xFFF658)))
    for _ in range(12):
        tap(gr, 'RIGHT')
    check("GAME MODE wraps instead of running past NG+7",
          0 <= gr.rw(0xFFFE1E) <= 7, "value=%d" % gr.rw(0xFFFE1E))
    while gr.rw(0xFFFE1E) != 3:
        tap(gr, 'RIGHT' if gr.rw(0xFFFE1E) < 3 else 'LEFT')

    tap(gr, 'DOWN')                          # EXIT is now the last row
    tap(gr, 'START', gap=60)
    check("EXIT returns to the title menu", gr.rb(0xFFF600) == 0x06)
    tap(gr, 'START', gap=90)
    gr.step(240)
    check("game starts with the chosen mode applied",
          gr.rb(0xFFF600) == 0x12 and gr.rw(0xFFF658) == 3,
          "mode=%02X live NG+=%d" % (gr.rb(0xFFF600), gr.rw(0xFFF658)))
    hp = [gr.rl(0xFF8000 + 0x20 * i + 0x1C)
          for i in range(128) if gr.rl(0xFF8000 + 0x20 * i) == 0x25]
    check("bosses spawn with NG+3 hit points (64 x 4)", hp == [256, 256],
          "hp=%s" % hp)


def run_ngplus_test(rom):
    print("\n[2] New Game+ loop and difficulty scaling  (%s)" % os.path.basename(rom))
    gr = Granada(rom)
    boot_to_stage(gr, frames_after=120)
    hp0 = [o[2] for o in live_objects(gr) if o[1] == 0x25]
    stages, hp_by_loop = [], {0: hp0}
    want = 1
    for i in range(1400):
        gr.press(); gr.step(10)
        st, ng = gr.rb(0xFFABE1), gr.rw(0xFFF658)
        if (ng, st) not in stages:
            stages.append((ng, st))
        if ng == want and st == 0:
            gr.step(120)
            hp_by_loop[ng] = [o[2] for o in live_objects(gr) if o[1] == 0x25]
            want += 1
            if want > 2:
                break
        if gr.rb(0xFFF600) != 0x12:
            break
    check("all nine stages play through and the game loops into NG+1",
          (1, 0) in stages, "visited %d stage entries" % len(stages))
    check("NG+ keeps looping into NG+2", (2, 0) in stages)
    check("boss HP scales x2 on NG+1",
          hp_by_loop.get(1) == [128, 128], "hp=%s" % hp_by_loop.get(1))
    check("boss HP scales x3 on NG+2",
          hp_by_loop.get(2) == [192, 192], "hp=%s" % hp_by_loop.get(2))
    check("both bosses survive into later loops",
          len(hp_by_loop.get(2, [])) == 2)

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); raise SystemExit(1)
    hack, auto = sys.argv[1], sys.argv[2]
    # one emulator instance per process, so fork for the second ROM
    pid = os.fork()
    if pid == 0:
        run_feature_tests(hack)
        os._exit(1 if FAIL else 0)
    _, status = os.waitpid(pid, 0)
    rc1 = status >> 8
    pid = os.fork()
    if pid == 0:
        run_options_test(hack)
        os._exit(1 if FAIL else 0)
    _, status = os.waitpid(pid, 0)
    rc2 = status >> 8
    run_ngplus_test(auto)
    rc = 1 if (FAIL or rc1 or rc2) else 0
    print("\n%s" % ("SOME CHECKS FAILED" if rc else "ALL CHECKS PASSED"))
    raise SystemExit(rc)
