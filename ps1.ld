/* ps1.ld - flat layout for a PS-X EXE loaded at 0x80010000.
 *
 * Main RAM is 2 MiB at 0x80000000. The first 64 KiB belong to the BIOS
 * kernel, so the executable starts at 0x80010000 and the stack is parked
 * just below the top of RAM.
 */
OUTPUT_FORMAT("elf32-tradlittlemips", "elf32-tradlittlemips", "elf32-tradlittlemips")
OUTPUT_ARCH(mips)
ENTRY(_start)

SECTIONS
{
    . = 0x80010000;

    .text : {
        KEEP(*(.text.startup))
        *(.text .text.*)
        . = ALIGN(4);
    }

    .rodata : {
        *(.rodata .rodata.*)
        . = ALIGN(4);
    }

    .data : {
        *(.data .data.*)
        *(.sdata .sdata.*)
        . = ALIGN(2048);          /* EXE body must be a multiple of 2048 */
    }
    __text_end = .;

    .bss (NOLOAD) : {
        __bss_start = .;
        *(.bss .bss.*)
        *(.sbss .sbss.*)
        *(COMMON)
        . = ALIGN(4);
        __bss_end = .;
    }

    _end = .;
    _gp = 0;
    _stack_top = 0x801FFF00;

    /DISCARD/ : {
        *(.MIPS.abiflags)
        *(.reginfo)
        *(.comment)
        *(.gnu.attributes)
        *(.pdr)
        *(.note .note.*)
    }
}
