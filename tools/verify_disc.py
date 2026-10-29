#!/usr/bin/env python3
"""Static verification of a PlayStation CD image.

Stricter than the checker shipped with the handoff: besides the ISO9660 and
SYSTEM.CNF structure it walks every raw sector and checks the sync pattern,
the MSF address, the mode byte, the subheader copies and the EDC.

The EDC check uses the standard CRC property that the checksum computed over
the message *including* its own checksum is zero. That validates the
polynomial and the byte range, not merely that the writer is self-consistent.

Exit status 0 means every check passed. This is a static checker: it says
nothing about whether the program runs. Emulator evidence lives in
reports/SCENE_TESTS.md.
"""
import argparse
import hashlib
import json
import pathlib
import struct
import sys

RAW = 2352
USER = 2048


def _edc_table():
    tbl = []
    for i in range(256):
        edc = i
        for _ in range(8):
            edc = (edc >> 1) ^ (0xD8018001 if edc & 1 else 0)
        tbl.append(edc)
    return tbl


EDC_TABLE = _edc_table()


def edc(data):
    v = 0
    for b in data:
        v = (v >> 8) ^ EDC_TABLE[(v ^ b) & 0xFF]
    return v & 0xFFFFFFFF


def from_bcd(v):
    return (v >> 4) * 10 + (v & 0xF)


def check_sectors(img):
    n = len(img) // RAW
    bad_sync = bad_msf = bad_mode = bad_sub = bad_edc = 0
    for lba in range(n):
        s = img[lba * RAW:(lba + 1) * RAW]
        if s[0] != 0 or any(b != 0xFF for b in s[1:11]) or s[11] != 0:
            bad_sync += 1
            continue
        amin, asec, afrac = from_bcd(s[12]), from_bcd(s[13]), from_bcd(s[14])
        if (amin * 60 + asec) * 75 + afrac != lba + 150:
            bad_msf += 1
        if s[15] != 0x02:
            bad_mode += 1
        if s[16:20] != s[20:24]:
            bad_sub += 1
        # CRC over the message plus its own checksum must be zero.
        if edc(s[16:2076]) != 0:
            bad_edc += 1
    return dict(sectors=n, bad_sync=bad_sync, bad_msf=bad_msf,
                bad_mode=bad_mode, bad_subheader=bad_sub, bad_edc=bad_edc)


def user_data(img, lba):
    return img[lba * RAW + 24: lba * RAW + 24 + USER]


def parse_dir(img, lba, length):
    out = []
    data = b""
    for i in range((length + USER - 1) // USER):
        data += user_data(img, lba + i)
    off = 0
    while off < length:
        rlen = data[off]
        if rlen == 0:
            off = (off // USER + 1) * USER
            continue
        extent = struct.unpack_from("<I", data, off + 2)[0]
        size = struct.unpack_from("<I", data, off + 10)[0]
        flags = data[off + 25]
        nlen = data[off + 32]
        name = data[off + 33: off + 33 + nlen]
        out.append(dict(name=name.decode("latin1"), lba=extent, size=size,
                        directory=bool(flags & 0x02)))
        off += rlen
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--json")
    args = ap.parse_args()

    img = pathlib.Path(args.image).read_bytes()
    report = {"image": args.image, "size_bytes": len(img),
              "sha256": hashlib.sha256(img).hexdigest()}
    checks = {}

    checks["raw_2352_sectors"] = (len(img) % RAW == 0 and len(img) > 0)
    if not checks["raw_2352_sectors"]:
        report["checks"] = checks
        print(json.dumps(report, indent=2))
        return 1

    sect = check_sectors(img)
    report["sector_scan"] = sect
    checks["sector_sync_ok"] = sect["bad_sync"] == 0
    checks["sector_msf_ok"] = sect["bad_msf"] == 0
    checks["sector_mode2_ok"] = sect["bad_mode"] == 0
    checks["subheader_duplicated"] = sect["bad_subheader"] == 0
    checks["edc_ok"] = sect["bad_edc"] == 0

    pvd = user_data(img, 16)
    checks["iso9660_pvd"] = (pvd[0] == 1 and pvd[1:6] == b"CD001")
    report["volume_id"] = pvd[40:72].decode("latin1").rstrip() if checks["iso9660_pvd"] else None

    files = []
    if checks["iso9660_pvd"]:
        root_lba = struct.unpack_from("<I", pvd, 156 + 2)[0]
        root_len = struct.unpack_from("<I", pvd, 156 + 10)[0]
        files = [f for f in parse_dir(img, root_lba, root_len)
                 if f["name"] not in ("\x00", "\x01")]
    report["files"] = files

    cnf = next((f for f in files if f["name"].upper().startswith("SYSTEM.CNF")), None)
    checks["system_cnf_exists"] = cnf is not None
    boot_target = None
    if cnf:
        text = user_data(img, cnf["lba"])[:cnf["size"]].decode("latin1")
        report["system_cnf"] = text
        for line in text.splitlines():
            if line.upper().startswith("BOOT"):
                boot_target = line.split("=", 1)[1].strip()
    report["boot_target"] = boot_target
    checks["boot_entry_exists"] = boot_target is not None

    exe_entry = None
    if boot_target:
        want = boot_target.rsplit("\\", 1)[-1].rsplit(":", 1)[-1].upper()
        exe_entry = next((f for f in files if f["name"].upper() == want), None)
    checks["boot_matches_iso_directory"] = exe_entry is not None

    if exe_entry:
        head = user_data(img, exe_entry["lba"])
        checks["psx_exe_signature"] = head[:8] == b"PS-X EXE"
        pc, gp, dest, tsize = struct.unpack_from("<IIII", head, 0x10)
        sp = struct.unpack_from("<I", head, 0x30)[0]
        report["exe"] = dict(pc="0x%08X" % pc, load="0x%08X" % dest,
                             text_size=tsize, sp="0x%08X" % sp,
                             file_size=exe_entry["size"])
        checks["exe_payload_fits_file"] = (tsize + 0x800) <= exe_entry["size"]
        checks["exe_load_in_main_ram"] = 0x80010000 <= dest < 0x80200000
        checks["exe_fits_main_ram"] = (dest + tsize) < 0x80200000
        checks["exe_entry_in_text"] = dest <= pc < dest + tsize

    report["checks"] = checks
    report["result"] = "PASS" if all(checks.values()) else "FAIL"
    out = json.dumps(report, indent=2)
    print(out)
    if args.json:
        pathlib.Path(args.json).write_text(out + "\n")
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
