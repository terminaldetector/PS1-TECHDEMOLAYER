#!/usr/bin/env python3
"""
Minimal 68000 assembler - just the instruction forms the Granada hack needs.

Every instruction produced here is round-trip verified against Capstone by
tools/build.py, so an encoding mistake fails the build instead of the ROM.
"""
import struct

def _w(v):  return struct.pack('>H', v & 0xFFFF)
def _l(v):  return struct.pack('>I', v & 0xFFFFFFFF)

# data register numbers 0-7, address register numbers 0-7
D = {('d%d' % i): i for i in range(8)}
A = {('a%d' % i): i for i in range(8)}

class Asm:
    """Collects instructions; resolves symbolic labels in a second pass."""
    def __init__(self, org):
        self.org = org
        self.items = []        # (size, emit_fn) emit_fn(labels, pc) -> bytes
        self.labels = {}
        self._pc = org

    # ---- infrastructure -------------------------------------------------
    def _add(self, size, fn):
        self.items.append((self._pc, size, fn))
        self._pc += size

    def label(self, name):
        self.labels[name] = self._pc
        return self

    def here(self):
        return self._pc

    def raw(self, b):
        self._add(len(b), lambda L, pc, b=b: b)
        return self

    def assemble(self):
        out = bytearray()
        for pc, size, fn in self.items:
            b = fn(self.labels, pc)
            assert len(b) == size, "size mismatch at %06X: %d != %d" % (pc, len(b), size)
            out += b
        return bytes(out)

    def _target(self, L, t):
        return L[t] if isinstance(t, str) else t

    # ---- instructions ---------------------------------------------------
    def nop(self):            return self.raw(b'\x4e\x71')
    def rts(self):            return self.raw(b'\x4e\x75')

    def jmp_l(self, target):
        self._add(6, lambda L, pc: b'\x4e\xf9' + _l(self._target(L, target))); return self
    def jsr_l(self, target):
        self._add(6, lambda L, pc: b'\x4e\xb9' + _l(self._target(L, target))); return self

    def _branch(self, opbyte, target):
        def fn(L, pc):
            disp = self._target(L, target) - (pc + 2)
            assert -32768 <= disp <= 32767, "branch out of range at %06X" % pc
            return bytes([opbyte, 0x00]) + _w(disp)
        self._add(4, fn); return self
    def bra_w(self, t):  return self._branch(0x60, t)
    def bsr_w(self, t):  return self._branch(0x61, t)
    def bne_w(self, t):  return self._branch(0x66, t)
    def beq_w(self, t):  return self._branch(0x67, t)
    def bcc_w(self, t):  return self._branch(0x64, t)   # unsigned >=
    def bls_w(self, t):  return self._branch(0x63, t)   # unsigned <=
    def bhi_w(self, t):  return self._branch(0x62, t)   # unsigned >

    def movem_push(self, mask):   # movem.l <regs>,-(a7)
        return self.raw(b'\x48\xe7' + _w(mask))
    def movem_pop(self, mask):    # movem.l (a7)+,<regs>
        return self.raw(b'\x4c\xdf' + _w(mask))

    def moveq(self, imm, dn):
        return self.raw(bytes([0x70 | (D[dn] << 1), imm & 0xFF]))

    def move_l_dn_push(self, dn):            # move.l dn,-(a7)
        return self.raw(_w(0x2F00 | D[dn]))
    def move_l_imm_push(self, imm):          # move.l #imm,-(a7)
        return self.raw(b'\x2f\x3c' + _l(imm))
    def clr_l_push(self):                    # clr.l -(a7)
        return self.raw(b'\x42\xa7')
    def lea_a7_off(self, off):               # lea off(a7),a7
        return self.raw(b'\x4f\xef' + _w(off))

    def move_l_dn_dm(self, src, dst):        # move.l dsrc,ddst
        return self.raw(_w(0x2000 | (D[dst] << 9) | D[src]))
    def move_l_ind_dn(self, an, dn):         # move.l (an),dn
        return self.raw(_w(0x2010 | (D[dn] << 9) | A[an]))
    def move_l_dispan_dn(self, off, an, dn): # move.l off(an),dn
        return self.raw(_w(0x2028 | (D[dn] << 9) | A[an]) + _w(off))
    def move_w_dispan_dn(self, off, an, dn): # move.w off(an),dn
        return self.raw(_w(0x3028 | (D[dn] << 9) | A[an]) + _w(off))
    def move_l_dn_dispan(self, dn, off, an): # move.l dn,off(an)
        return self.raw(_w(0x2140 | (A[an] << 9) | D[dn]) + _w(off))

    def movea_l_imm(self, imm, an):          # movea.l #imm,an
        return self.raw(_w(0x207C | (A[an] << 9)) + _l(imm))
    def adda_l_dn(self, dn, an):             # adda.l dn,an
        return self.raw(_w(0xD1C0 | (A[an] << 9) | D[dn]))
    def suba_l_imm(self, imm, an):           # suba.l #imm,an
        return self.raw(_w(0x91FC | (A[an] << 9)) + _l(imm))

    def asl_l_imm(self, cnt, dn):            # asl.l #cnt,dn
        return self.raw(_w(0xE180 | ((cnt & 7) << 9) | D[dn]))
    def asr_l_imm(self, cnt, dn):            # asr.l #cnt,dn
        return self.raw(_w(0xE080 | ((cnt & 7) << 9) | D[dn]))
    def neg_l(self, dn):                     # neg.l dn
        return self.raw(_w(0x4480 | D[dn]))
    def add_l_dispan_dn(self, off, an, dn):  # add.l off(an),dn
        return self.raw(_w(0xD0A8 | (D[dn] << 9) | A[an]) + _w(off))
    def mulu_w(self, src, dst):              # mulu.w dsrc,ddst
        return self.raw(_w(0xC0C0 | (D[dst] << 9) | D[src]))
    def addq_w_absw(self, imm, addr):        # addq.w #imm,$addr.w
        return self.raw(_w(0x5078 | ((imm & 7) << 9)) + _w(addr))
    def subq_w_absw(self, imm, addr):        # subq.w #imm,$addr.w
        return self.raw(_w(0x5178 | ((imm & 7) << 9)) + _w(addr))
    def addq_w_dn(self, imm, dn):            # addq.w #imm,dn
        return self.raw(_w(0x5040 | ((imm & 7) << 9) | D[dn]))

    def move_b_imm_absl(self, imm, addr):    # move.b #imm,$addr.l
        return self.raw(b'\x13\xfc' + _w(imm & 0xFF) + _l(addr))
    def move_b_imm_absw(self, imm, addr):    # move.b #imm,$addr.w
        return self.raw(b'\x11\xfc' + _w(imm & 0xFF) + _w(addr))
    def move_w_imm_absw(self, imm, addr):    # move.w #imm,$addr.w
        return self.raw(b'\x31\xfc' + _w(imm) + _w(addr))
    def move_b_absl_dn(self, addr, dn):      # move.b $addr.l,dn
        return self.raw(_w(0x1039 | (D[dn] << 9)) + _l(addr))
    def move_b_absw_dn(self, addr, dn):      # move.b $addr.w,dn
        return self.raw(_w(0x1038 | (D[dn] << 9)) + _w(addr))
    def move_b_absl_absw(self, src, dst):    # move.b $src.l,$dst.w
        return self.raw(b'\x11\xf9' + _l(src) + _w(dst))
    def move_b_dn_absw(self, dn, addr):      # move.b dn,$addr.w
        return self.raw(_w(0x11C0 | D[dn]) + _w(addr))
    def move_w_absw_dn(self, addr, dn):      # move.w $addr.w,dn
        return self.raw(_w(0x3038 | (D[dn] << 9)) + _w(addr))
    def move_w_dn_absw(self, dn, addr):      # move.w dn,$addr.w
        return self.raw(_w(0x31C0 | D[dn]) + _w(addr))
    def tst_b_absw(self, addr):              # tst.b $addr.w
        return self.raw(b'\x4a\x38' + _w(addr))
    def tst_w_absw(self, addr):              # tst.w $addr.w
        return self.raw(b'\x4a\x78' + _w(addr))
    def cmpi_w_dn(self, imm, dn):            # cmpi.w #imm,dn
        return self.raw(_w(0x0C40 | D[dn]) + _w(imm))
    def cmpi_l_dn(self, imm, dn):            # cmpi.l #imm,dn
        return self.raw(_w(0x0C80 | D[dn]) + _l(imm))
    def cmpi_w_absw(self, imm, addr):        # cmpi.w #imm,$addr.w
        return self.raw(b'\x0c\x78' + _w(imm) + _w(addr))
    def andi_b_dn(self, imm, dn):            # andi.b #imm,dn
        return self.raw(_w(0x0200 | D[dn]) + _w(imm & 0xFF))
    def btst_b_imm_absw(self, bit, addr):    # btst #bit,$addr.w
        return self.raw(b'\x08\x38' + _w(bit) + _w(addr))
    def move_l_imm_absw(self, imm, addr):    # move.l #imm,$addr.w
        return self.raw(b'\x21\xfc' + _l(imm) + _w(addr))

# register masks for movem.l <regs>,-(a7)  (bit0=a7 .. bit15=d0)
def push_mask(regs):
    order = ['a7','a6','a5','a4','a3','a2','a1','a0','d7','d6','d5','d4','d3','d2','d1','d0']
    m = 0
    for r in regs: m |= 1 << order.index(r)
    return m

# register masks for movem.l (a7)+,<regs>  (bit0=d0 .. bit15=a7)
def pop_mask(regs):
    order = ['d0','d1','d2','d3','d4','d5','d6','d7','a0','a1','a2','a3','a4','a5','a6','a7']
    m = 0
    for r in regs: m |= 1 << order.index(r)
    return m

# ---- additional forms used by the Granada patches -----------------------
def _extend(cls):
    def cmp_w_absw_dn(self, addr, dn):       # cmp.w $addr.w,dn
        return self.raw(_w(0xB078 | (D[dn] << 9)) + _w(addr))
    def move_l_imm_dn(self, imm, dn):        # move.l #imm,dn
        return self.raw(_w(0x203C | (D[dn] << 9)) + _l(imm))
    def move_l_pop_dn(self, dn):             # move.l (a7)+,dn
        return self.raw(_w(0x201F | (D[dn] << 9)))
    def andi_l_dn(self, imm, dn):            # andi.l #imm,dn
        return self.raw(_w(0x0280 | D[dn]) + _l(imm))
    def addq_l_dn(self, imm, dn):            # addq.l #imm,dn
        return self.raw(_w(0x5080 | ((imm & 7) << 9) | D[dn]))
    def subq_l_dn(self, imm, dn):            # subq.l #imm,dn
        return self.raw(_w(0x5180 | ((imm & 7) << 9) | D[dn]))
    def add_l_dn_dm(self, src, dst):         # add.l dsrc,ddst
        return self.raw(_w(0xD080 | (D[dst] << 9) | D[src]))
    def addi_l_dn(self, imm, dn):            # addi.l #imm,dn
        return self.raw(_w(0x0680 | D[dn]) + _l(imm))
    for f in (cmp_w_absw_dn, move_l_imm_dn, move_l_pop_dn, andi_l_dn,
              addq_l_dn, subq_l_dn, add_l_dn_dm, addi_l_dn):
        setattr(cls, f.__name__, f)
_extend(Asm)

def _extend2(cls):
    def ori_b_imm_dn(self, imm, dn):         # ori.b #imm,dn
        return self.raw(_w(0x0000 | D[dn]) + _w(imm & 0xFF))
    def btst_imm_dn(self, bit, dn):          # btst #bit,dn
        return self.raw(_w(0x0800 | D[dn]) + _w(bit))
    def not_b_dn(self, dn):                  # not.b dn
        return self.raw(_w(0x4600 | D[dn]))
    for f in (ori_b_imm_dn, btst_imm_dn, not_b_dn):
        setattr(cls, f.__name__, f)
_extend2(Asm)

def _extend3(cls):
    def bge_w(self, t):  return self._branch(0x6C, t)
    def blt_w(self, t):  return self._branch(0x6D, t)
    def move_w_absw_absw(self, src, dst):    # move.w $src.w,$dst.w
        return self.raw(b'\x31\xf8' + _w(src) + _w(dst))
    for f in (bge_w, blt_w, move_w_absw_absw):
        setattr(cls, f.__name__, f)
_extend3(Asm)

def _extend4(cls):
    def add_l_absw_dn(self, addr, dn):       # add.l $addr.w,dn
        return self.raw(_w(0xD0B8 | (D[dn] << 9)) + _w(addr))
    def add_l_dn_absw(self, dn, addr):       # add.l dn,$addr.w
        return self.raw(_w(0xD1B8 | (D[dn] << 9)) + _w(addr))
    def move_l_absw_dn(self, addr, dn):      # move.l $addr.w,dn
        return self.raw(_w(0x2038 | (D[dn] << 9)) + _w(addr))
    def move_l_dn_absw(self, dn, addr):      # move.l dn,$addr.w
        return self.raw(_w(0x21C0 | D[dn]) + _w(addr))
    def clr_l_absw(self, addr):              # clr.l $addr.w
        return self.raw(b'\x42\xb8' + _w(addr))
    def sub_l_dn_dm(self, src, dst):         # sub.l dsrc,ddst
        return self.raw(_w(0x9080 | (D[dst] << 9) | D[src]))
    def move_l_dispan_dn_a(self, off, an, dn):
        return self.move_l_dispan_dn(off, an, dn)
    def tst_l_dn(self, dn):                  # tst.l dn
        return self.raw(_w(0x4A80 | D[dn]))
    def bpl_w(self, t):  return self._branch(0x6A, t)
    def bmi_w(self, t):  return self._branch(0x6B, t)
    def ble_w(self, t):  return self._branch(0x6F, t)
    def bgt_w(self, t):  return self._branch(0x6E, t)
    def adda_l_imm(self, imm, an):           # adda.l #imm,an
        return self.raw(_w(0xD1FC | (A[an] << 9)) + _l(imm))
    def movea_l_ind_an(self, src, dst):      # movea.l (asrc),adst
        return self.raw(_w(0x2050 | (A[dst] << 9) | A[src]))
    def move_l_ind_an_dn(self, an, dn):      # move.l (an),dn
        return self.move_l_ind_dn(an, dn)
    def subq_w_dn(self, imm, dn):            # subq.w #imm,dn
        return self.raw(_w(0x5140 | ((imm & 7) << 9) | D[dn]))
    def dbra(self, dn, t):                   # dbra dn,target
        def fn(L, pc):
            disp = (L[t] if isinstance(t, str) else t) - (pc + 2)
            assert -32768 <= disp <= 32767
            return _w(0x51C8 | D[dn]) + _w(disp)
        self._add(4, fn); return self
    for f in (add_l_absw_dn, add_l_dn_absw, move_l_absw_dn, move_l_dn_absw,
              clr_l_absw, sub_l_dn_dm, tst_l_dn, bpl_w, bmi_w, ble_w, bgt_w,
              adda_l_imm, movea_l_ind_an, subq_w_dn, dbra):
        setattr(cls, f.__name__, f)
_extend4(Asm)

def _extend5(cls):
    def cmp_l_dn_dn(self, src, dst):         # cmp.l dsrc,ddst
        return self.raw(_w(0xB080 | (D[dst] << 9) | D[src]))
    def sub_l_dispan_dn(self, off, an, dn):  # sub.l off(an),dn
        return self.raw(_w(0x90A8 | (D[dn] << 9) | A[an]) + _w(off))
    for f in (cmp_l_dn_dn, sub_l_dispan_dn):
        setattr(cls, f.__name__, f)
_extend5(Asm)
