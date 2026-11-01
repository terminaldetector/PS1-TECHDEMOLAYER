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

    # The specials sit on X/Y/Z by default; this emulator only offers a
    # 3-button pad, so switch the options screen to the A/B/C fallback and
    # exercise the weapon logic through that.
    tap(gr, 'START', gap=40); tap(gr, 'DOWN'); tap(gr, 'START', gap=60)
    for _ in range(7):
        tap(gr, 'DOWN')                      # cursor -> SPECIALS
    tap(gr, 'RIGHT')
    check("SPECIALS row switches to the 3-button fallback",
          gr.rw(0xFFFE20) == 1, "value=%d" % gr.rw(0xFFFE20))
    tap(gr, 'DOWN'); tap(gr, 'START', gap=60)        # EXIT
    tap(gr, 'START', gap=90); gr.step(300)           # GAME START

    check("gameplay mode entered", gr.rb(0xFFF600) == 0x12)
    objs = live_objects(gr)
    bosses = [o for o in objs if o[1] == 0x25]
    check("stage 1 carries two bosses", len(bosses) == 2,
          "slots %s" % [b[0] for b in bosses])
    check("stage target counter raised to 19", gr.rw(0xFFB434) == 19,
          "targets=%d" % gr.rw(0xFFB434))
    check("boss hit points unscaled on the first loop",
          all(b[2] == 0x40 for b in bosses), "hp=%s" % [b[2] for b in bosses])

    def wait_ready(limit=600):
        """the weapon code, like the stock fire routine, only runs while the
        player state at $FFAB16 is 2 or less"""
        for _ in range(limit):
            if gr.rw(0xFFAB16) <= 2:
                return True
            gr.press(); gr.step(1)
        return False

    def volley(btns, frames=30):
        gr.press(); gr.step(60); wait_ready()
        gr.press(*btns); gr.step(4)
        peak = 0
        for _ in range(frames):
            gr.step(2); peak = max(peak, len(projectile_kinds(gr)))
        return peak, gr.rb(0xFFF65C)

    stock, _ = volley(('A',))
    beam, req_x = volley(('A', 'B'))
    nova, _ = volley(('B', 'C'))
    check("weapon-request byte decodes A+B as the X weapon", req_x & 4 == 4,
          "req=%02X" % req_x)
    check("X / Hyper Beam lays a column of rounds at once", beam >= 8,
          "stock peak=%d, beam peak=%d" % (stock, beam))
    check("Z / Nova puts a full ring in the air", nova >= 8,
          "nova peak=%d" % nova)

    gr.press(); gr.step(60)
    check("player returns to an actionable state", wait_ready(),
          "AB16=%d" % gr.rw(0xFFAB16))
    gr.press('A', 'C'); gr.step(6)
    gr.press(); gr.step(10)
    armed = gr.rw(0xFFF65E)
    check("Y / Support Fire arms a timed escort", armed > 200,
          "frames armed=%d" % armed)
    seen = 0
    for _ in range(40):
        gr.step(4)
        seen = max(seen, len(projectile_kinds(gr)))
    check("support fire keeps shooting with no button held", seen > 0,
          "rounds seen after release=%d" % seen)
    check("support charge is counting down", gr.rw(0xFFF65E) < armed,
          "%d -> %d" % (armed, gr.rw(0xFFF65E)))


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

    # down to GAME MODE (cursor is on row 1 now)
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

    tap(gr, 'DOWN'); tap(gr, 'DOWN')         # past SPECIALS to EXIT
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
