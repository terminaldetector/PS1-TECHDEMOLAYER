#!/usr/bin/env python3
"""Drive Granada from boot into stage-1 gameplay and report engine state."""
import sys; sys.path.insert(0,'.')
from emu import Granada

def boot_to_stage(gr, frames_after=600, tap_start=3):
    gr.step(600)                      # Sega logo + title
    for _ in range(tap_start):
        gr.press('START'); gr.step(8)
        gr.press();        gr.step(20)
    gr.step(frames_after)
    return gr

def obj_table(gr, n=128):
    """$FF8000 stage-object array, 0x20 bytes/entry: +0 type, +4 X, +8 Y"""
    out=[]
    for i in range(n):
        b=0xFF8000+0x20*i
        t=gr.rl(b)
        if t: out.append((i,t,gr.rl(b+4)>>8,gr.rl(b+8)>>8))
    return out

if __name__=='__main__':
    rom=sys.argv[1] if len(sys.argv)>1 else 'granada.md'
    gr=Granada(rom); boot_to_stage(gr)
    print("mode=%02X stage=%02X  targets(B434)=%d  lives(FE06)=%d"%(
        gr.rb(0xFFF600), gr.rb(0xFFABE1), gr.rw(0xFFB434), gr.rw(0xFFFE06)))
    print("player pos AB20=%08X AB24=%08X"%(gr.rl(0xFFAB20), gr.rl(0xFFAB24)))
    objs=obj_table(gr)
    from collections import Counter
    print("live stage objects: %d  types=%s"%(len(objs),
          {hex(k):v for k,v in sorted(Counter(o[1] for o in objs).items())}))
    for o in objs:
        if o[1] in (0x25,):
            print("   BOSS type %02X at slot %d  x=%04X y=%04X"%(o[1],o[0],o[2],o[3]))
    gr.save_png(sys.argv[2] if len(sys.argv)>2 else 'shot_game.png')
