#!/usr/bin/env python3
"""Offline static PS1 Mode2/2352 ISO9660 disc boot-target validator.
No emulation, no claim about runtime. Tested with supplied DIGITAL_IRIS.BIN.
"""
import argparse, hashlib, json, re, struct, sys
from pathlib import Path
SECTOR = 2352
USER_OFFSET = 24
USER_SIZE = 2048

def iso_sector(data, lba):
    pos = lba*SECTOR
    if pos+SECTOR > len(data):
        raise ValueError(f'LBA {lba} outside image')
    raw = data[pos:pos+SECTOR]
    if raw[:12] != b'\0'+b'\xff'*10+b'\0':
        raise ValueError(f'bad CD sector sync at LBA {lba}')
    if raw[15] != 2:
        raise ValueError(f'expected Mode 2 sector at LBA {lba}, got {raw[15]}')
    return raw[USER_OFFSET:USER_OFFSET+USER_SIZE]

def read_file(data, lba, size):
    return b''.join(iso_sector(data, i) for i in range(lba,lba+(size+USER_SIZE-1)//USER_SIZE))[:size]

def dir_items(content):
    out=[]; offset=0
    while offset<len(content):
        length=content[offset]
        if not length:
            offset=(offset//USER_SIZE+1)*USER_SIZE
            continue
        rec=content[offset:offset+length]
        offset+=length
        if len(rec)<34:
            continue
        name=rec[33:33+rec[32]].decode('ascii','replace')
        if name in ('\x00','\x01'):
            continue
        out.append({'name':name,'lba':int.from_bytes(rec[2:6],'little'),'size':int.from_bytes(rec[10:14],'little'),'directory':bool(rec[25]&2)})
    return out

def inspect(path):
    raw=path.read_bytes()
    result={'image':str(path),'size_bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(), 'checks':{}, 'files':[]}
    result['checks']['2352_byte_sectors']=len(raw)%SECTOR==0
    if not result['checks']['2352_byte_sectors']:
        return result
    result['sector_count']=len(raw)//SECTOR
    pvd=iso_sector(raw,16)
    result['checks']['iso9660_primary_descriptor']=pvd[:8]==b'\x01CD001\x01\x00'
    if not result['checks']['iso9660_primary_descriptor']:
        return result
    result['volume_id']=pvd[40:72].decode('ascii','replace').strip()
    root=pvd[156:]
    rootlba=int.from_bytes(root[2:6],'little')
    rootsize=int.from_bytes(root[10:14],'little')
    entries=dir_items(read_file(raw,rootlba,rootsize))
    result['files']=entries
    cnf=next((x for x in entries if x['name'].upper()=='SYSTEM.CNF;1'),None)
    result['checks']['system_cnf_exists']=bool(cnf)
    if not cnf:
        return result
    content=read_file(raw,cnf['lba'],cnf['size']).decode('ascii','replace')
    result['system_cnf']=content
    match=re.search(r'^\s*BOOT\s*=\s*cdrom:\\([^\r\n]+)',content,re.I|re.M)
    result['checks']['boot_entry_exists']=bool(match)
    if not match:
        return result
    boot=match.group(1).split()[0].upper()
    result['boot_target']=boot
    normalized=boot.replace('\\','/').lstrip('/')
    filename=normalized.rsplit('/',1)[-1]
    exe=next((x for x in entries if x['name'].upper()==filename),None)
    result['checks']['boot_file_matches_iso_directory']=bool(exe)
    if not exe:
        result['failure']='SYSTEM.CNF BOOT target absent from ISO9660 directory.'
        return result
    payload=read_file(raw,exe['lba'],exe['size'])
    result['checks']['boot_file_has_psx_exe_signature']=payload[:8]==b'PS-X EXE'
    if len(payload)>=52 and result['checks']['boot_file_has_psx_exe_signature']:
        result['executable']={
            'pc':hex(struct.unpack_from('<I',payload,16)[0]),
            'load_address':hex(struct.unpack_from('<I',payload,24)[0]),
            'text_size':struct.unpack_from('<I',payload,28)[0],
            'stack_address':hex(struct.unpack_from('<I',payload,48)[0]),
            'file_size':len(payload)}
        result['checks']['exe_payload_fits_file']=(result['executable']['text_size']+2048<=len(payload))
    return result

if __name__=='__main__':
    ap=argparse.ArgumentParser(description='Verify a raw 2352-byte/sector PS1 CD BIN BOOT reference (not runtime).')
    ap.add_argument('bin',type=Path)
    ap.add_argument('--json',dest='json_path',type=Path,help='Save audit JSON')
    args=ap.parse_args()
    try:
        report=inspect(args.bin)
    except (OSError,ValueError) as ex:
        print(f'ERROR: {ex}',file=sys.stderr)
        sys.exit(2)
    result=json.dumps(report,ensure_ascii=False,indent=2)
    print(result)
    if args.json_path:
        args.json_path.write_text(result+'\n',encoding='utf-8')
    if any(not value for value in report['checks'].values()):
        sys.exit(1)
