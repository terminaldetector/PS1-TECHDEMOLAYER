/* crt0.s - entry point for the PS-X EXE.
 *
 * The BIOS loads the image at 0x80010000 and jumps to the header's PC with
 * SP already set from the header. It does NOT clear .bss, so we do.
 */
    .section .text.startup, "ax"
    .global _start
    .type _start, @function
_start:
    /* Enable COP2 (the GTE) and keep interrupts masked: the demo polls. */
    mfc0    $t0, $12
    nop
    lui     $t1, 0x4000              /* SR bit 30 = CU2 */
    or      $t0, $t0, $t1
    li      $t2, 0xFFFFFFFE          /* clear IEc, we never take an IRQ */
    and     $t0, $t0, $t2
    mtc0    $t0, $12
    nop

    la      $gp, _gp
    la      $sp, _stack_top
    move    $fp, $sp

    /* Zero .bss. */
    la      $t0, __bss_start
    la      $t1, __bss_end
1:
    sltu    $t2, $t0, $t1
    beqz    $t2, 2f
    nop
    sw      $zero, 0($t0)
    addiu   $t0, $t0, 4
    b       1b
    nop
2:
    jal     main
    nop

    /* main() never returns; park the CPU if it ever does. */
3:
    b       3b
    nop
    .size _start, .-_start
