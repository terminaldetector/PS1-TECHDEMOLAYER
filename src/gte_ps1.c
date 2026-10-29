/* gte_ps1.c - COP2 driver for the real hardware.
 *
 * Register conventions follow psx-spx. Data registers are loaded with lwc2
 * straight out of the vertex array, which is why SVECTOR is laid out as
 * {vx,vy,vz,pad}: word 0 is VXYn and word 1 is VZn.
 */
#include "gte.h"

void gte_init(void)
{
    /* Enable COP2 in the status register; the BIOS leaves it off. */
    __asm__ volatile(
        "mfc0 $t0, $12\n\t"
        "lui  $t1, 0x4000\n\t"
        "or   $t0, $t0, $t1\n\t"
        "mtc0 $t0, $12\n\t"
        "nop\n\t"
        ::: "t0", "t1");
    gte_clear_flag();
}

void gte_set_screen(s32 ofx, s32 ofy, s32 h)
{
    __asm__ volatile("ctc2 %0, $24" :: "r"(ofx << 16));
    __asm__ volatile("ctc2 %0, $25" :: "r"(ofy << 16));
    __asm__ volatile("ctc2 %0, $26" :: "r"(h));
    /* No GTE depth cueing: fog is done in software so it can be tinted. */
    __asm__ volatile("ctc2 $0, $27");
    __asm__ volatile("ctc2 $0, $28");
}

void gte_load_matrix(const MATRIX *m)
{
    u32 r0 = ((u32)(u16)m->m[0][0]) | ((u32)(u16)m->m[0][1] << 16);
    u32 r1 = ((u32)(u16)m->m[0][2]) | ((u32)(u16)m->m[1][0] << 16);
    u32 r2 = ((u32)(u16)m->m[1][1]) | ((u32)(u16)m->m[1][2] << 16);
    u32 r3 = ((u32)(u16)m->m[2][0]) | ((u32)(u16)m->m[2][1] << 16);
    u32 r4 = (u32)(s32)m->m[2][2];
    __asm__ volatile("ctc2 %0, $0" :: "r"(r0));
    __asm__ volatile("ctc2 %0, $1" :: "r"(r1));
    __asm__ volatile("ctc2 %0, $2" :: "r"(r2));
    __asm__ volatile("ctc2 %0, $3" :: "r"(r3));
    __asm__ volatile("ctc2 %0, $4" :: "r"(r4));
    __asm__ volatile("ctc2 %0, $5" :: "r"(m->t[0]));
    __asm__ volatile("ctc2 %0, $6" :: "r"(m->t[1]));
    __asm__ volatile("ctc2 %0, $7" :: "r"(m->t[2]));
}

u32 gte_read_flag(void)
{
    u32 f;
    __asm__ volatile("cfc2 %0, $31\n\tnop" : "=r"(f));
    return f;
}

void gte_clear_flag(void) { __asm__ volatile("ctc2 $0, $31"); }

void gte_project_tri(const SVECTOR *a, const SVECTOR *b, const SVECTOR *c,
                     TriProj *out)
{
    /* Load V0..V2 straight from memory, then one RTPT for all three. */
    __asm__ volatile(
        "lwc2 $0,  0(%0)\n\t"   /* VXY0 */
        "lwc2 $1,  4(%0)\n\t"   /* VZ0  */
        "lwc2 $2,  0(%1)\n\t"   /* VXY1 */
        "lwc2 $3,  4(%1)\n\t"   /* VZ1  */
        "lwc2 $4,  0(%2)\n\t"   /* VXY2 */
        "lwc2 $5,  4(%2)\n\t"   /* VZ2  */
        :: "r"(a), "r"(b), "r"(c) : "memory");

    __asm__ volatile(".word 0x4A280030\n\tnop\n\tnop"); /* RTPT */

    /* SXY0..2 are packed x|y<<16 and map directly onto DVECTOR. */
    __asm__ volatile(
        "swc2 $12, 0(%0)\n\t"
        "swc2 $13, 4(%0)\n\t"
        "swc2 $14, 8(%0)\n\t"
        :: "r"(out->s) : "memory");

    {
        s32 z1, z2, z3;
        __asm__ volatile("mfc2 %0, $17\n\tnop" : "=r"(z1));
        __asm__ volatile("mfc2 %0, $18\n\tnop" : "=r"(z2));
        __asm__ volatile("mfc2 %0, $19\n\tnop" : "=r"(z3));
        out->sz[0] = z1; out->sz[1] = z2; out->sz[2] = z3;
    }

    __asm__ volatile(".word 0x4B400006\n\tnop\n\tnop"); /* NCLIP */
    __asm__ volatile("mfc2 %0, $24\n\tnop" : "=r"(out->nclip));

    __asm__ volatile(".word 0x4B58002D\n\tnop\n\tnop"); /* AVSZ3 */
    __asm__ volatile("mfc2 %0, $7\n\tnop" : "=r"(out->otz));
}

s32 gte_project_point(const SVECTOR *v, DVECTOR *out)
{
    s32 z;
    __asm__ volatile(
        "lwc2 $0, 0(%0)\n\t"
        "lwc2 $1, 4(%0)\n\t"
        :: "r"(v) : "memory");
    __asm__ volatile(".word 0x4A180001\n\tnop\n\tnop"); /* RTPS */
    __asm__ volatile("swc2 $14, 0(%0)" :: "r"(out) : "memory");
    __asm__ volatile("mfc2 %0, $19\n\tnop" : "=r"(z));
    return z;
}

void gte_rotate_normal(const SVECTOR *n, VECTOR *out)
{
    /* MVMVA: multiply the rotation matrix by V0 with no translation,
     * sf=1, lm=0 -> cop2 func 0x0400012 with mx=0, v=0, cv=3. */
    __asm__ volatile(
        "lwc2 $0, 0(%0)\n\t"
        "lwc2 $1, 4(%0)\n\t"
        :: "r"(n) : "memory");
    __asm__ volatile(".word 0x4A486012\n\tnop\n\tnop"); /* MVMVA rot,V0,none */
    {
        s32 x, y, z;
        __asm__ volatile("mfc2 %0, $9\n\tnop"  : "=r"(x));
        __asm__ volatile("mfc2 %0, $10\n\tnop" : "=r"(y));
        __asm__ volatile("mfc2 %0, $11\n\tnop" : "=r"(z));
        out->vx = x; out->vy = y; out->vz = z;
    }
}
