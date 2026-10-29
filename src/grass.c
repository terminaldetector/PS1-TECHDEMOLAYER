/* grass.c - the living bank.
 *
 * Placement is a ClusterRule: a jittered lattice filtered by spatial masks
 * (above the waterline, clear of the plant, denser on the damp ground near
 * the shore). Positions come from stateless hashes on the grass stream, so a
 * clump's position depends only on its cell, never on evaluation order, and
 * changing the material seed cannot move a single tuft.
 *
 * Three levels of detail, chosen per clump per frame by distance:
 *   LOD0  full ribbon blades, two quads each, with a fold ridge
 *   LOD1  one tapered triangle per blade
 *   LOD2  a single triangle standing in for the whole clump
 *
 * Wind comes from the shared field, and every clump carries its own phase
 * offset, so the field never swings as one body.
 */
#include "grass.h"
#include "world.h"
#include "scene.h"

#define CLUMP_MAX   260
#define CELL        250
#define AREA_X0     (-2700)
#define AREA_X1     2700
#define AREA_Z0     (-900)
#define AREA_Z1     2500

typedef struct {
    s16 x, z;
    s16 y;        /* ground height, cached */
    s16 h;        /* clump height          */
    u8  blades;
    u8  kind;     /* 0 = fine bank grass, 1 = coarse reed */
    s16 phase;
    s16 yaw;
} Clump;

static Clump g_clump[CLUMP_MAX];
static int   g_nclump;
static s32   g_lit0[3], g_lit1[3];

void grass_init(void)
{
    s32 x, z;
    g_nclump = 0;
    for (z = AREA_Z0; z < AREA_Z1 && g_nclump < CLUMP_MAX; z += CELL) {
        for (x = AREA_X0; x < AREA_X1 && g_nclump < CLUMP_MAX; x += CELL) {
            u32 hsh = di_hash2(DI_RS_GRASS, x / CELL, z / CELL);
            s32 jx = x + (s32)((hsh >> 4) % CELL);
            s32 jz = z + (s32)((hsh >> 14) % CELL);
            s32 gy = ground_h(jx, jz);
            s32 dist, density, roll;
            Clump *c;

            if (gy < 18) continue;                       /* no grass in the lake */
            dist = di_len3(jx, 0, jz);
            if (dist < 150) continue;                    /* keep the plant clear */

            /* Damp ground near the shoreline carries more growth; the dry
             * crown of the bank carries less. */
            density = 950
                    + fmul(1500, fband(jz, 500, SHORE_Z - 250))
                    + fmul(900, di_noise2(DI_RS_GRASS + 3, jx, jz, 10));
            roll = (s32)((hsh >> 22) & 0xFFF);
            if (roll > density) continue;

            c = &g_clump[g_nclump++];
            c->x = (s16)jx;
            c->z = (s16)jz;
            c->y = (s16)gy;
            c->kind = (u8)(((hsh >> 9) & 7) < 2 ? 1 : 0);
            c->h = (s16)(c->kind ? 150 + (s32)((hsh >> 6) % 150)
                                 : 78 + (s32)((hsh >> 6) % 80));
            c->blades = (u8)(3 + ((hsh >> 19) & 3));
            c->phase = (s16)((hsh >> 11) & 2047);
            c->yaw = (s16)((hsh >> 2) & (ANG_360 - 1));
        }
    }
}

/* One blade. `samples` is 3 for the ribbon LOD and 2 for the triangle LOD. */
static void blade_draw(DrawCtx *d, const Clump *c, int bi, s32 t_ms, s32 wind,
                       int lod, const s32 *lit0, const s32 *lit1)
{
    u32 h = di_hash3(DI_RS_GRASS + 7, c->x, c->z, bi);
    s32 yaw  = c->yaw + (s32)((h >> 3) & 1023) - 512;
    s32 hgt  = c->h - (s32)((h >> 5) & 31);
    /* Lean has to stay well under the height or the blade lies down. */
    s32 lean = (hgt >> 2) + (s32)((h >> 13) & 31);
    s32 wid  = c->kind ? 9 : 6;
    s32 ox   = ((s32)((h >> 17) & 63) - 32) * (c->kind ? 2 : 1);
    s32 oz   = ((s32)((h >> 23) & 63) - 32) * (c->kind ? 2 : 1);
    s32 wdx, wdz;
    s32 sy = fsin(yaw), cy = fcos(yaw);
    /* Per-blade variation is a cheap scale on the already-lit colour rather
     * than a second trip through the lighting model. */
    s32 shade = ONE - 600 + (s32)((h >> 27) & 15) * 80;
    int i;
    SVECTOR p[3][2];
    CVECTOR cc[3][2];

    wind_eval(c->x, c->z, t_ms + c->phase, wind, &wdx, &wdz);

    /* A distant blade is one triangle: base pair plus tip. Computing the
     * middle sample for it was half the per-blade cost for nothing. */
    {
    int nsamp = (lod == 0) ? 3 : 2;
    for (i = 0; i < nsamp; i++) {
        s32 u  = (nsamp == 3) ? ((i * ONE) / 2) : (i * ONE);
        s32 uu = fmul(u, u);
        s32 flex = c->kind ? 420 : 300;
        s32 ex = fmul(fmul(lean, uu), sy) + fmul(fmul(wdx, flex), uu);
        s32 ez = fmul(fmul(lean, uu), cy) + fmul(fmul(wdz, flex), uu);
        /* Slight shortening as it bends over, so the blade keeps its length. */
        s32 ey = fmul(hgt, u - fmul(uu, 420));
        s32 w  = fmul(wid, ONE - fmul(u, 3400));
        CVECTOR cc_u;
        int k;
        if (w < 1) w = 1;
        cc_u.r = (u8)DI_CLAMP(fmul(flerp(lit0[0], lit1[0], u), shade), 0, 255);
        cc_u.g = (u8)DI_CLAMP(fmul(flerp(lit0[1], lit1[1], u), shade), 0, 255);
        cc_u.b = (u8)DI_CLAMP(fmul(flerp(lit0[2], lit1[2], u), shade), 0, 255);
        for (k = 0; k < 2; k++) {
            s32 off = k ? w : -w;
            int si = (nsamp == 3) ? i : (i * 2);
            p[si][k].vx = (s16)(ox + ex + fmul(cy, off));
            p[si][k].vy = (s16)ey;
            p[si][k].vz = (s16)(oz + ez - fmul(sy, off));
            p[si][k].pad = 0;
            cc[si][k] = cc_u;
            if (nsamp == 2 && i == 1) break;   /* tip needs one vertex only */
        }
    }
    }

    if (lod == 0) {
        for (i = 0; i < 2; i++) {
            SVECTOR v[4];
            CVECTOR q[4];
            v[0] = p[i][1];     q[0] = cc[i][1];
            v[1] = p[i][0];     q[1] = cc[i][0];
            v[2] = p[i + 1][1]; q[2] = cc[i + 1][1];
            v[3] = p[i + 1][0]; q[3] = cc[i + 1][0];
            draw_quad(d, v, q, DRAW_CULL_NONE, 0, 0);
        }
    } else {
        /* One tapered triangle from the base pair to the tip. */
        SVECTOR v[3];
        CVECTOR q[3];
        v[0] = p[0][1]; q[0] = cc[0][1];
        v[1] = p[0][0]; q[1] = cc[0][0];
        v[2] = p[2][0]; q[2] = cc[2][0];
        draw_tri(d, v, q, DRAW_CULL_NONE, 0, 0);
    }
}

void grass_draw(DrawCtx *d, s32 t_ms)
{
    int i;
    s32 wind = g_dir.wind;
    /* The three profiles move the LOD thresholds and the draw distance, not
     * just a blade count, so the load difference is real rather than cosmetic. */
    s32 budget_lod0 = (g_quality == Q_LOW) ? 5  : (g_quality == Q_SHOWCASE ? 24 : 11);
    s32 used_lod0 = 0;
    s32 far_cut   = (g_quality == Q_LOW) ? 1800 : (g_quality == Q_SHOWCASE ? 5200 : 2400);
    s32 d_lod0    = (g_quality == Q_LOW) ? 600  : (g_quality == Q_SHOWCASE ? 1700 : 950);
    s32 d_lod1    = (g_quality == Q_LOW) ? 1100 : (g_quality == Q_SHOWCASE ? 2800 : 1250);

    /* The grass is lit once per frame, not once per blade. A blade's normal
     * barely varies across the field, so one evaluation of the lighting model
     * for a canonical blade normal - plus a per-blade scale and a per-clump
     * fog blend - is visually indistinguishable from doing it per vertex and
     * is roughly an order of magnitude cheaper. */
    s32 lit_base[3], lit_tip[3];
    {
        static const s32 kBase[3] = {  34,  66,  32 };
        static const s32 kTip[3]  = {  70, 120,  58 };
        s32 n[3] = { 900, 3900, 600 };
        di_norm3(&n[0], &n[1], &n[2]);
        if (fmul(n[0], d->lt->ldir[0]) + fmul(n[1], d->lt->ldir[1])
          + fmul(n[2], d->lt->ldir[2]) < 0) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
        light_shade(d->lt, n, kBase, 1100, lit_base);
        light_shade(d->lt, n, kTip,  1100, lit_tip);
    }

    for (i = 0; i < g_nclump; i++) {
        const Clump *c = &g_clump[i];
        s32 dx = c->x - d->cam->px;
        s32 dy = (s32)c->y - d->cam->py;
        s32 dz = c->z - d->cam->pz;
        s32 dist = di_len3(dx, dy, dz);
        int lod, b, nb;

        if (dist > far_cut) continue;
        /* Rough view-cone reject before any per-blade work. */
        {
            s32 vz = (d->cam->view.m[2][0] * dx + d->cam->view.m[2][1] * dy
                    + d->cam->view.m[2][2] * dz) >> QBITS;
            if (vz < 100) continue;
            {
                s32 vx = (d->cam->view.m[0][0] * dx + d->cam->view.m[0][1] * dy
                        + d->cam->view.m[0][2] * dz) >> QBITS;
                if (DI_ABS(vx) > vz + (vz >> 1) + 400) continue;
            }
        }

        if (dist < d_lod0 && used_lod0 < budget_lod0) { lod = 0; used_lod0++; }
        else if (dist < d_lod1)                        lod = 1;
        else                                           lod = 2;

        {
            /* Aerial perspective applied once per clump. */
            s32 f0[3], f1[3];
            f0[0] = lit_base[0]; f0[1] = lit_base[1]; f0[2] = lit_base[2];
            f1[0] = lit_tip[0];  f1[1] = lit_tip[1];  f1[2] = lit_tip[2];
            light_fog(d->lt, c->z > 0 ? c->z : 0, f0);
            light_fog(d->lt, c->z > 0 ? c->z : 0, f1);
            g_lit0[0] = f0[0]; g_lit0[1] = f0[1]; g_lit0[2] = f0[2];
            g_lit1[0] = f1[0]; g_lit1[1] = f1[1]; g_lit1[2] = f1[2];
        }
        cam_bind_object(d->cam, c->x, c->y, c->z);
        nb = c->blades;
        if (lod == 1) nb = 2;
        if (lod == 2) nb = 1;
        if (g_quality == Q_LOW && lod > 0) nb = 1;
        if (g_quality == Q_SHOWCASE && lod == 0) nb = c->blades + 2;
        for (b = 0; b < nb; b++)
            blade_draw(d, c, b, t_ms, wind, lod == 0 ? 0 : 1, g_lit0, g_lit1);
    }
}
