/* gte_host.c - software model of the COP2 used by the host preview build.
 *
 * This is deliberately a model of the *hardware*, not a clean reimplementation:
 * it keeps the 16-bit IR saturation, the 0..FFFFh SZ clamp, the -400h..3FFh
 * screen clamp and the UNR reciprocal divider with its 1FFFFh cap. If a scene
 * blows up on the console because a polygon crosses the near plane, it blows
 * up in the preview too, which is the entire point of having it.
 *
 * Reference: psx-spx, "Geometry Transformation Engine".
 */
#include "gte.h"

extern const u8 di_gte_unr_tab[257];

static s16 g_rt[3][3];
static s32 g_tr[3];
static s32 g_ofx, g_ofy;
static s32 g_h;
static u32 g_flag;

/* FIFOs, same shape as the hardware's. */
static s32 g_sxy[3][2];
static s32 g_sz[4];
static s32 g_ir[3];
static s32 g_mac0;

#define FLAG_IR1_SAT   (1u << 24)
#define FLAG_IR2_SAT   (1u << 23)
#define FLAG_IR3_SAT   (1u << 22)
#define FLAG_SZ3_SAT   (1u << 18)
#define FLAG_DIV_OVF   (1u << 17)
#define FLAG_SX2_SAT   (1u << 14)
#define FLAG_SY2_SAT   (1u << 13)
#define FLAG_ERROR     (1u << 31)

void gte_init(void) { g_flag = 0; }

void gte_set_screen(s32 ofx, s32 ofy, s32 h)
{
    /* OFX/OFY live in the GTE's control registers as Q16, exactly as the
     * console path writes them with ctc2. */
    g_ofx = ofx << 16;
    g_ofy = ofy << 16;
    g_h = h;
}

void gte_load_matrix(const MATRIX *m)
{
    int i, j;
    for (i = 0; i < 3; i++) {
        for (j = 0; j < 3; j++) g_rt[i][j] = m->m[i][j];
        g_tr[i] = m->t[i];
    }
}

u32  gte_read_flag(void) { return g_flag; }
void gte_clear_flag(void) { g_flag = 0; }

static s32 lim_ir(s32 v, u32 bit)
{
    if (v < -32768) { g_flag |= bit | FLAG_ERROR; return -32768; }
    if (v >  32767) { g_flag |= bit | FLAG_ERROR; return  32767; }
    return v;
}

static s32 lim_sz(s32 v)
{
    if (v < 0)      { g_flag |= FLAG_SZ3_SAT | FLAG_ERROR; return 0; }
    if (v > 0xFFFF) { g_flag |= FLAG_SZ3_SAT | FLAG_ERROR; return 0xFFFF; }
    return v;
}

static s32 lim_sxy(s32 v, u32 bit)
{
    if (v < -1024) { g_flag |= bit | FLAG_ERROR; return -1024; }
    if (v >  1023) { g_flag |= bit | FLAG_ERROR; return  1023; }
    return v;
}

static int count_leading_zeros16(u32 d)
{
    int n = 0;
    while (n < 16 && !(d & 0x8000u)) { d <<= 1; n++; }
    return n;
}

/* The hardware's "Unsigned Newton-Raphson" divider: (H*20000h/SZ3 + 1)/2,
 * saturated to 1FFFFh. */
static u32 gte_divide(u32 h, u32 sz3)
{
    u32 n, d, u;
    int z;
    if (sz3 == 0 || h >= sz3 * 2) {
        g_flag |= FLAG_DIV_OVF | FLAG_ERROR;
        return 0x1FFFFu;
    }
    z = count_leading_zeros16(sz3);
    n = h << z;
    d = sz3 << z;
    u = di_gte_unr_tab[(d - 0x7FC0u) >> 7] + 0x101u;
    d = ((0x2000080u - (d * u)) >> 8);
    d = ((0x0000080u + (d * u)) >> 8);
    n = (u32)((((u64)n * (u64)d) + 0x8000u) >> 16);
    return n > 0x1FFFFu ? 0x1FFFFu : n;
}

/* One RTPS step: transform, project, push the FIFOs. */
static void rtps(const SVECTOR *v, int push_all)
{
    s64 m1, m2, m3;
    s32 mac1, mac2, mac3, div;

    m1 = (s64)g_tr[0] * 0x1000 + (s64)g_rt[0][0] * v->vx
       + (s64)g_rt[0][1] * v->vy + (s64)g_rt[0][2] * v->vz;
    m2 = (s64)g_tr[1] * 0x1000 + (s64)g_rt[1][0] * v->vx
       + (s64)g_rt[1][1] * v->vy + (s64)g_rt[1][2] * v->vz;
    m3 = (s64)g_tr[2] * 0x1000 + (s64)g_rt[2][0] * v->vx
       + (s64)g_rt[2][1] * v->vy + (s64)g_rt[2][2] * v->vz;

    mac1 = (s32)(m1 >> 12);
    mac2 = (s32)(m2 >> 12);
    mac3 = (s32)(m3 >> 12);

    g_ir[0] = lim_ir(mac1, FLAG_IR1_SAT);
    g_ir[1] = lim_ir(mac2, FLAG_IR2_SAT);
    g_ir[2] = lim_ir(mac3, FLAG_IR3_SAT);

    g_sz[0] = g_sz[1]; g_sz[1] = g_sz[2]; g_sz[2] = g_sz[3];
    g_sz[3] = lim_sz(mac3);

    div = (s32)gte_divide((u32)g_h, (u32)g_sz[3]);

    g_sxy[0][0] = g_sxy[1][0]; g_sxy[0][1] = g_sxy[1][1];
    g_sxy[1][0] = g_sxy[2][0]; g_sxy[1][1] = g_sxy[2][1];

    g_mac0 = div * g_ir[0] + g_ofx;
    g_sxy[2][0] = lim_sxy(g_mac0 >> 16, FLAG_SX2_SAT);
    g_mac0 = div * g_ir[1] + g_ofy;
    g_sxy[2][1] = lim_sxy(g_mac0 >> 16, FLAG_SY2_SAT);
    (void)push_all;
}

void gte_project_tri(const SVECTOR *a, const SVECTOR *b, const SVECTOR *c,
                     TriProj *out)
{
    int i;
    rtps(a, 1);
    rtps(b, 1);
    rtps(c, 1);

    for (i = 0; i < 3; i++) {
        out->s[i].x = (s16)g_sxy[i][0];
        out->s[i].y = (s16)g_sxy[i][1];
    }
    out->sz[0] = g_sz[1];
    out->sz[1] = g_sz[2];
    out->sz[2] = g_sz[3];

    /* NCLIP: the signed area of the projected triangle. */
    out->nclip = g_sxy[0][0] * (g_sxy[1][1] - g_sxy[2][1])
               + g_sxy[1][0] * (g_sxy[2][1] - g_sxy[0][1])
               + g_sxy[2][0] * (g_sxy[0][1] - g_sxy[1][1]);

    /* AVSZ3: (SZ1+SZ2+SZ3) * ZSF3, with ZSF3 = 1/3 in Q12. */
    out->otz = (s32)(((s64)(g_sz[1] + g_sz[2] + g_sz[3]) * 1365) >> 12);
    if (out->otz < 0) out->otz = 0;
    if (out->otz > 0xFFFF) out->otz = 0xFFFF;
}

s32 gte_project_point(const SVECTOR *v, DVECTOR *out)
{
    rtps(v, 1);
    out->x = (s16)g_sxy[2][0];
    out->y = (s16)g_sxy[2][1];
    return g_sz[3];
}

void gte_rotate_normal(const SVECTOR *n, VECTOR *out)
{
    s64 m1 = (s64)g_rt[0][0] * n->vx + (s64)g_rt[0][1] * n->vy + (s64)g_rt[0][2] * n->vz;
    s64 m2 = (s64)g_rt[1][0] * n->vx + (s64)g_rt[1][1] * n->vy + (s64)g_rt[1][2] * n->vz;
    s64 m3 = (s64)g_rt[2][0] * n->vx + (s64)g_rt[2][1] * n->vy + (s64)g_rt[2][2] * n->vz;
    out->vx = lim_ir((s32)(m1 >> 12), FLAG_IR1_SAT);
    out->vy = lim_ir((s32)(m2 >> 12), FLAG_IR2_SAT);
    out->vz = lim_ir((s32)(m3 >> 12), FLAG_IR3_SAT);
}
