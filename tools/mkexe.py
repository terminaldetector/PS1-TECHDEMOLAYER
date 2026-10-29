#!/usr/bin/env python3
"""Wrap a flat binary in a PS-X EXE header.

The header layout is the one documented in psx-spx under
"CDROM File Formats / Executables". The BIOS reads it, copies `t_size` bytes
from file offset 0x800 to `t_addr`, sets SP from the header and jumps to `pc`.
It does NOT clear .bss, which is why crt0.s does.
"""
import struct
import sys
import pathlib

LOAD_ADDR = 0x80010000
STACK_TOP = 0x801FFF00
REGION = b"Sony Computer Entertainment Inc. for North America area"


def read_elf(path):
    """Pull the entry point and the .bss extent out of the linked ELF."""
    data = pathlib.Path(path).read_bytes()
    if data[:4] != b"\x7fELF":
        raise SystemExit("%s is not an ELF file" % path)
    if data[5] != 1:
        raise SystemExit("expected a little-endian ELF")
    entry = struct.unpack_from("<I", data, 24)[0]

    # Walk the section headers for __bss_start / __bss_end via .symtab.
    e_shoff, = struct.unpack_from("<I", data, 32)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 46)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = \
            struct.unpack_from("<IIIIIIIIII", data, off)
        sections.append(dict(name=name, type=stype, addr=addr, offset=offset,
                             size=size, link=link, entsize=entsize))
    shstr = sections[e_shstrndx]

    def sname(s):
        base = shstr["offset"] + s["name"]
        end = data.index(b"\0", base)
        return data[base:end].decode()

    bss_addr = bss_size = 0
    for s in sections:
        if sname(s) == ".bss":
            bss_addr, bss_size = s["addr"], s["size"]
    return entry, bss_addr, bss_size


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mkexe.py <elf> <flat.bin> <out.exe>")
    elf, binpath, out = sys.argv[1:4]
    entry, bss_addr, bss_size = read_elf(elf)
    body = pathlib.Path(binpath).read_bytes()

    # The body must be a whole number of 2048-byte blocks.
    if len(body) % 2048:
        body += b"\0" * (2048 - (len(body) % 2048))

    hdr = bytearray(0x800)
    hdr[0x00:0x08] = b"PS-X EXE"
    struct.pack_into("<I", hdr, 0x10, entry)        # initial PC
    struct.pack_into("<I", hdr, 0x14, 0)            # initial GP (unused, -G0)
    struct.pack_into("<I", hdr, 0x18, LOAD_ADDR)    # destination
    struct.pack_into("<I", hdr, 0x1C, len(body))    # size of the body
    struct.pack_into("<I", hdr, 0x28, bss_addr)
    struct.pack_into("<I", hdr, 0x2C, bss_size)
    struct.pack_into("<I", hdr, 0x30, STACK_TOP)    # stack base
    struct.pack_into("<I", hdr, 0x34, 0)            # stack offset
    hdr[0x4C:0x4C + len(REGION)] = REGION

    pathlib.Path(out).write_bytes(bytes(hdr) + body)
    print("mkexe: %s  pc=0x%08X load=0x%08X body=%d bytes  bss=0x%08X+%d"
          % (out, entry, LOAD_ADDR, len(body), bss_addr, bss_size))
    if entry != LOAD_ADDR:
        print("       note: entry differs from load address (that is fine; "
              "crt0 is not necessarily first after --gc-sections)")


if __name__ == "__main__":
    main()
