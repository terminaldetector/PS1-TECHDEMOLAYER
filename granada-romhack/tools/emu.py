#!/usr/bin/env python3
"""Headless Genesis test harness for the Granada rom-hack (Genesis Plus GX via stable-retro)."""
import os, sys, shutil, struct
import numpy as np
from stable_retro._retro import RetroEmulator, GameDataGlue
import stable_retro.data  # registers core info

BUTTONS = ["B","A","MODE","START","UP","DOWN","LEFT","RIGHT","C","Y","X","Z"]

class Granada:
    def __init__(self, rom):
        # core is chosen by extension; ensure .md
        if not rom.endswith('.md'):
            tmp = '/tmp/_gr_%d.md' % os.getpid()
            shutil.copyfile(rom, tmp); rom = tmp
        self.em = RetroEmulator(rom)
        self.g = GameDataGlue()
        self.em.configure_data(self.g)
        self.mask = [False]*12
    def press(self, *names):
        self.mask = [b in names for b in BUTTONS]
    def step(self, n=1):
        for _ in range(n):
            self.em.set_button_mask(self.mask, 0)
            self.em.step()
        self.g.update_ram()
    def ram(self):
        blocks = self.g.memory.blocks
        # Genesis work RAM is the 64KB block mapped at 0xFF0000 (key may be 0 or 0xFF0000)
        for k, v in blocks.items():
            if len(v) == 0x10000:
                return bytes(v), k
        # fall back to biggest
        k = max(blocks, key=lambda k: len(blocks[k]))
        return bytes(blocks[k]), k
    # NOTE: Genesis Plus GX exposes 68K work RAM byte-swapped inside each 16-bit
    # word, i.e. block index == (68K offset ^ 1).  All accessors correct for that.
    def rb(self, addr):
        r, _ = self.ram(); return r[((addr - 0xFF0000) & 0xFFFF) ^ 1]
    def rw(self, addr):
        r, _ = self.ram(); o = (addr - 0xFF0000) & 0xFFFF
        return (r[o ^ 1] << 8) | r[(o + 1) ^ 1]
    def rl(self, addr):
        r, _ = self.ram(); o = (addr - 0xFF0000) & 0xFFFF
        return sum(r[(o + i) ^ 1] << (8 * (3 - i)) for i in range(4))
    def mem68k(self):
        """work RAM in true 68K byte order"""
        r, _ = self.ram()
        return bytes(r[i ^ 1] for i in range(len(r)))
    def screen(self):
        return np.array(self.em.get_screen(), copy=True)
    def save_png(self, path):
        arr = self.screen()
        try:
            from PIL import Image
            Image.fromarray(arr[:, :, :3]).save(path)
        except ImportError:
            # minimal PPM fallback
            h, w = arr.shape[:2]
            with open(path.replace('.png', '.ppm'), 'wb') as f:
                f.write(b'P6\n%d %d\n255\n' % (w, h))
                f.write(arr[:, :, :3].tobytes())

if __name__ == '__main__':
    rom = sys.argv[1] if len(sys.argv) > 1 else 'granada.md'
    gr = Granada(rom)
    gr.step(60)
    r, base = gr.ram()
    print("RAM block key=%r size=%d" % (base, len(r)))
    print("boot ok; frame 60. non-zero RAM bytes: %d" % sum(1 for b in r if b))
