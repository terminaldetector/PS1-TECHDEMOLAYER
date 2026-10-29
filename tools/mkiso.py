#!/usr/bin/env python3
"""Build a PlayStation CD image: ISO9660 in raw Mode 2 Form 1 sectors.

Produces a 2352-bytes-per-sector BIN with correct sync, MSF header, subheader,
EDC and ECC, plus the matching CUE. The point of generating the image here
rather than with a packer is that the BOOT name in SYSTEM.CNF and the name in
the ISO9660 directory come from one variable, so they cannot drift apart -
which is exactly the defect the v0.4 audit found in the baseline image.

Layout:
    0..15   system area (all zero; a retail disc carries Sony's licence data
            here, which is not ours to ship - see ISSUES.md)
    16      primary volume descriptor
    17      volume descriptor set terminator
    18,19   type-L path table and its copy
    20,21   type-M path table and its copy
    22      root directory
    23..    SYSTEM.CNF, then the executable
"""
import argparse
import datetime
import hashlib
import pathlib
import struct

SECTOR_USER = 2048
SECTOR_RAW = 2352

SYS_AREA = 16
PVD_LBA = 16
TERM_LBA = 17
PATH_L_LBA = 18
PATH_L2_LBA = 19
PATH_M_LBA = 20
PATH_M2_LBA = 21
ROOT_LBA = 22
FILE_LBA = 23

# ---------------------------------------------------------------- EDC / ECC

def _edc_table():
    tbl = []
    for i in range(256):
        edc = i
        for _ in range(8):
            edc = (edc >> 1) ^ (0xD8018001 if edc & 1 else 0)
        tbl.append(edc)
    return tbl


EDC_TABLE = _edc_table()


def edc_compute(data):
    edc = 0
    for b in data:
        edc = (edc >> 8) ^ EDC_TABLE[(edc ^ b) & 0xFF]
    return edc & 0xFFFFFFFF


def _ecc_tables():
    f = [0] * 256
    b = [0] * 256
    for i in range(256):
        j = ((i << 1) ^ (0x11D if (i & 0x80) else 0)) & 0xFF
        f[i] = j
        b[i ^ j] = i
    return f, b


ECC_F, ECC_B = _ecc_tables()


def _ecc_block(sec, base, major_count, minor_count, major_mult, minor_inc,
               dest, dest_off):
    """One ECC pass.

    `sec` is the whole raw sector and `base` is 12: the Q pass deliberately
    reads past the user data into the P parity that the first pass just
    wrote, which is why this takes the live buffer rather than a slice.
    """
    size = major_count * minor_count
    for major in range(major_count):
        index = (major >> 1) * major_mult + (major & 1)
        ecc_a = 0
        ecc_b = 0
        for _ in range(minor_count):
            temp = sec[base + index]
            index += minor_inc
            if index >= size:
                index -= size
            ecc_a ^= temp
            ecc_b ^= temp
            ecc_a = ECC_F[ecc_a]
        ecc_a = ECC_B[ECC_F[ecc_a] ^ ecc_b]
        dest[dest_off + major] = ecc_a
        dest[dest_off + major + major_count] = (ecc_a ^ ecc_b) & 0xFF


def _to_bcd(v):
    return ((v // 10) << 4) | (v % 10)


def build_sector(lba, user, last_of_file=False):
    """Wrap 2048 bytes of user data in a Mode 2 Form 1 raw sector."""
    sec = bytearray(SECTOR_RAW)
    sec[0] = 0x00
    for i in range(1, 11):
        sec[i] = 0xFF
    sec[11] = 0x00

    # MSF address = LBA + 150, in BCD.
    amin, rem = divmod(lba + 150, 75 * 60)
    asec, afrac = divmod(rem, 75)
    sec[12] = _to_bcd(amin)
    sec[13] = _to_bcd(asec)
    sec[14] = _to_bcd(afrac)
    sec[15] = 0x02                      # mode 2

    # Subheader, stored twice: file 0, channel 0, submode, coding 0.
    submode = 0x89 if last_of_file else 0x08   # data (+ EOF/EOR on the last)
    for base in (16, 20):
        sec[base + 0] = 0x00
        sec[base + 1] = 0x00
        sec[base + 2] = submode
        sec[base + 3] = 0x00

    sec[24:24 + SECTOR_USER] = user

    # EDC covers the subheader, the user data, i.e. bytes 16..2071.
    struct.pack_into("<I", sec, 2072, edc_compute(sec[16:2072]))

    # ECC is computed with the 4 header bytes treated as zero.
    saved = bytes(sec[12:16])
    sec[12:16] = b"\0\0\0\0"
    _ecc_block(sec, 12, 86, 24, 2, 86, sec, 2076)         # P parity
    _ecc_block(sec, 12, 52, 43, 86, 88, sec, 2076 + 172)  # Q parity
    sec[12:16] = saved
    return bytes(sec)


# ------------------------------------------------------------- ISO9660 bits

def both16(v):
    return struct.pack("<H", v) + struct.pack(">H", v)


def both32(v):
    return struct.pack("<I", v) + struct.pack(">I", v)


def dir_datetime(dt):
    return bytes([dt.year - 1900, dt.month, dt.day, dt.hour, dt.minute, dt.second, 0])


def vol_datetime(dt):
    return ("%04d%02d%02d%02d%02d%02d00" % (dt.year, dt.month, dt.day,
                                            dt.hour, dt.minute, dt.second)).encode() + b"\0"


def dir_record(name, lba, length, is_dir, dt):
    ident = name
    rec = bytearray()
    rec += b"\0"                      # length, filled in below
    rec += b"\0"                      # extended attribute length
    rec += both32(lba)
    rec += both32(length)
    rec += dir_datetime(dt)
    rec += bytes([0x02 if is_dir else 0x00])
    rec += b"\0"                      # file unit size
    rec += b"\0"                      # interleave gap
    rec += both16(1)                  # volume sequence number
    rec += bytes([len(ident)])
    rec += ident
    if len(rec) % 2:
        rec += b"\0"
    rec[0] = len(rec)
    return bytes(rec)


def build_pvd(volume_id, total_sectors, path_table_size, root_rec, dt):
    p = bytearray(SECTOR_USER)
    p[0] = 1
    p[1:6] = b"CD001"
    p[6] = 1
    p[8:40] = b"PLAYSTATION".ljust(32)
    p[40:72] = volume_id.encode().ljust(32)
    p[80:88] = both32(total_sectors)
    p[120:124] = both16(1)
    p[124:128] = both16(1)
    p[128:132] = both16(SECTOR_USER)
    p[132:140] = both32(path_table_size)
    struct.pack_into("<I", p, 140, PATH_L_LBA)
    struct.pack_into("<I", p, 144, PATH_L2_LBA)
    struct.pack_into(">I", p, 148, PATH_M_LBA)
    struct.pack_into(">I", p, 152, PATH_M2_LBA)
    p[156:156 + len(root_rec)] = root_rec
    p[190:318] = b"DIGITAL IRIS".ljust(128)
    p[318:446] = b"DIGITAL IRIS".ljust(128)
    p[446:574] = b"DIGITAL IRIS BUILD TOOLS".ljust(128)
    p[574:702] = b"DIGITAL IRIS".ljust(128)
    p[702:739] = b"".ljust(37)
    p[739:776] = b"".ljust(37)
    p[776:813] = b"".ljust(37)
    p[813:830] = vol_datetime(dt)
    p[830:847] = vol_datetime(dt)
    p[847:864] = b"0" * 16 + b"\0"
    p[864:881] = b"0" * 16 + b"\0"
    p[881] = 1
    return bytes(p)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exe", required=True)
    ap.add_argument("--name", required=True, help="8.3 name on the disc, e.g. DIRIS.EXE")
    ap.add_argument("--volume", default="DIGITAL_IRIS")
    ap.add_argument("--out", required=True, help="output path without extension")
    args = ap.parse_args()

    dt = datetime.datetime(2026, 10, 29, 12, 0, 0)
    exe = pathlib.Path(args.exe).read_bytes()

    # ONE source of truth for the boot name.
    iso_name = (args.name + ";1").encode()
    system_cnf = ("BOOT = cdrom:\\%s;1\r\n"
                  "TCB = 4\r\n"
                  "EVENT = 10\r\n"
                  "STACK = 801FFF00\r\n" % args.name).encode()

    cnf_lba = FILE_LBA
    cnf_sectors = (len(system_cnf) + SECTOR_USER - 1) // SECTOR_USER
    exe_lba = cnf_lba + cnf_sectors
    exe_sectors = (len(exe) + SECTOR_USER - 1) // SECTOR_USER

    # Root directory: ".", "..", SYSTEM.CNF, the executable.
    root_self = dir_record(b"\x00", ROOT_LBA, SECTOR_USER, True, dt)
    root_parent = dir_record(b"\x01", ROOT_LBA, SECTOR_USER, True, dt)
    rec_cnf = dir_record(b"SYSTEM.CNF;1", cnf_lba, len(system_cnf), False, dt)
    rec_exe = dir_record(iso_name, exe_lba, len(exe), False, dt)
    root_dir = root_self + root_parent + rec_cnf + rec_exe
    assert len(root_dir) <= SECTOR_USER
    root_dir = root_dir.ljust(SECTOR_USER, b"\0")

    # Path tables, one entry for the root.
    pt_l = bytes([1, 0]) + struct.pack("<I", ROOT_LBA) + struct.pack("<H", 1) + b"\0\0"
    pt_m = bytes([1, 0]) + struct.pack(">I", ROOT_LBA) + struct.pack(">H", 1) + b"\0\0"
    path_table_size = 10          # 8 bytes + 1 identifier byte + 1 pad

    total = exe_lba + exe_sectors
    if total < 350:
        total = 350               # keep the image a sane minimum length

    root_rec_for_pvd = dir_record(b"\x00", ROOT_LBA, SECTOR_USER, True, dt)
    pvd = build_pvd(args.volume, total, path_table_size, root_rec_for_pvd, dt)

    term = bytearray(SECTOR_USER)
    term[0] = 0xFF
    term[1:6] = b"CD001"
    term[6] = 1

    # Assemble the user-data image, then raw-encode every sector.
    user = {}
    user[PVD_LBA] = pvd
    user[TERM_LBA] = bytes(term)
    user[PATH_L_LBA] = pt_l.ljust(SECTOR_USER, b"\0")
    user[PATH_L2_LBA] = pt_l.ljust(SECTOR_USER, b"\0")
    user[PATH_M_LBA] = pt_m.ljust(SECTOR_USER, b"\0")
    user[PATH_M2_LBA] = pt_m.ljust(SECTOR_USER, b"\0")
    user[ROOT_LBA] = root_dir
    for i in range(cnf_sectors):
        chunk = system_cnf[i * SECTOR_USER:(i + 1) * SECTOR_USER]
        user[cnf_lba + i] = chunk.ljust(SECTOR_USER, b"\0")
    for i in range(exe_sectors):
        chunk = exe[i * SECTOR_USER:(i + 1) * SECTOR_USER]
        user[exe_lba + i] = chunk.ljust(SECTOR_USER, b"\0")

    out_bin = pathlib.Path(args.out + ".BIN")
    blank = b"\0" * SECTOR_USER
    with out_bin.open("wb") as f:
        for lba in range(total):
            data = user.get(lba, blank)
            last = (lba == cnf_lba + cnf_sectors - 1) or (lba == exe_lba + exe_sectors - 1)
            f.write(build_sector(lba, data, last))

    out_cue = pathlib.Path(args.out + ".CUE")
    out_cue.write_text('FILE "%s" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n'
                       % out_bin.name)

    digest = hashlib.sha256(out_bin.read_bytes()).hexdigest()
    print("mkiso: %s" % out_bin)
    print("       volume   : %s" % args.volume)
    print("       boot     : cdrom:\\%s;1" % args.name)
    print("       iso entry: %s" % iso_name.decode())
    print("       exe lba  : %d (%d sectors, %d bytes)" % (exe_lba, exe_sectors, len(exe)))
    print("       sectors  : %d  size %d bytes" % (total, total * SECTOR_RAW))
    print("       sha256   : %s" % digest)


if __name__ == "__main__":
    main()
