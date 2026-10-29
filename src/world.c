#include "world.h"
#include "gte.h"
#include "platform.h"
#include "scene.h"
#include "materials.h"

void wind_eval(s32 x, s32 z, s32 t_ms, s32 strength, s32 *dx, s32 *dz)
{
    /* Macro swell: one long wave travelling across the bank. */
    s32 travel = (x >> 4) + (z >> 5);
    s32 macro  = fsin((t_ms >> 5) - travel);
    /* Differential term: local, higher frequency, phase-shifted by position. */
    s32 micro  = fsin((t_ms >> 3) + ((x >> 2) + (z >> 3)) * 3);
    s32 drift  = di_noise2(DI_RS_MOTION + 11, x + (t_ms >> 3), z, 9);
    /* Result is a displacement in WORLD UNITS, so callers scale it by a Q12
     * flexibility and by their own along-length falloff. At nominal strength
     * a full gust is worth about +-270 units at the tip of a flexible part. */
    s32 amp = fmul(strength, fmul(2400, macro) + fmul(1100, micro)
                           + fmul(900, drift)) >> 3;
    /* The gust blows roughly along +X with a slight cross component. */
    *dx = amp;
    *dz = fmul(amp, 1200);
}

/* ---- soil height field -------------------------------------------------
 * A bank that sits ~230 units above the waterline, domed under the plant and
 * falling away to the shoreline. The clods come from a 3-octave value noise
 * on the terrain stream, so changing the material seed cannot move them.
 */
s32 ground_h(s32 x, s32 z)
{
    s32 base, dome, n;

    if (z > SHORE_Z)
        base = -fmul(900, fband(z, SHORE_Z, SHORE_Z + 1200));
    else
        base = fmul(236, ONE - fband(z, -1400, SHORE_Z));

    dome = fmul(130, ONE - fband(di_len3(x, 0, z - 120), 240, 1900));

    /* Clods fade out at the waterline so the lake edge stays a clean line. */
    n  = fmul(30, di_fbm2(DI_RS_TERRAIN, x, z, 7, 3));
    n += fmul(52, di_noise2(DI_RS_TERRAIN + 31, x, z, 10));
    n  = fmul(n, ONE - fband(z, SHORE_Z - 900, SHORE_Z + 200));
    return base + dome + n;
}

void ground_normal(s32 x, s32 z, s32 *n)
{
    const s32 e = 90;
    n[0] = ground_h(x - e, z) - ground_h(x + e, z);
    n[1] = 2 * e;
    n[2] = ground_h(x, z - e) - ground_h(x, z + e);
    di_norm3(&n[0], &n[1], &n[2]);
}

/* ---- Mt Fuji ------------------------------------------------------------ */

#define FJ_RINGS 10
#define FJ_SEGS  18

/* Radius as a fraction of the base radius, from summit (0) to base (1).
 * Follows r ~ t^1.45, which gives Fuji its steep crown and long flared skirt
 * instead of a plain cone. */
static const s32 kFujiProfile[FJ_RINGS] = {
    164, 530, 940, 1350, 1760, 2180, 2600, 3030, 3520, 4096
};

typedef struct {
    SVECTOR p;
    SVECTOR n;      /* Q12 normal */
    u8      snow;   /* 0..255 blend between rock and snow */
} FujiVert;

static FujiVert g_fuji[FJ_RINGS][FJ_SEGS];
/* Shading is per vertex, not per quad corner: every interior vertex is shared
 * by four quads, so caching it cuts the lighting work by about 4x. */
static CVECTOR g_fuji_col[FJ_RINGS][FJ_SEGS];

static void fuji_build(void)
{
    int i, j;
    for (i = 0; i < FJ_RINGS; i++) {
        s32 t  = (i * ONE) / (FJ_RINGS - 1);
        s32 rr = fmul(FUJI_R, kFujiProfile[i]);
        s32 y  = FUJI_H - fmul(FUJI_H, t);
        for (j = 0; j < FJ_SEGS; j++) {
            s32 a = (j * ANG_360) / FJ_SEGS;
            /* Ridges: low-frequency radial variation that fades out at the
             * summit so the crown stays clean. */
            s32 ridge = fmul(di_hashn(DI_RS_TERRAIN + 5, j, 0), 300)
                      + fmul(di_hashn(DI_RS_TERRAIN + 6, j * 3, 0), 170);
            s32 r = rr + fmul(ridge, t);
            s32 ydip = fmul(fmul(di_hashn(DI_RS_TERRAIN + 7, j, i), 150), t);
            s32 snowline, snow;

            g_fuji[i][j].p.vx = (s16)fmul(r, fsin(a));
            g_fuji[i][j].p.vy = (s16)(y + ydip);
            g_fuji[i][j].p.vz = (s16)fmul(r, fcos(a));

            /* Ragged snowline with fingers running down the gullies. */
            snowline = fmul(FUJI_H, 2180)
                     + fmul(di_hashn(DI_RS_TERRAIN + 8, j, 0), 1150)
                     + fmul(di_hashn(DI_RS_TERRAIN + 9, j * 5, 0), 700);
            snow = fband(y, snowline - 280, snowline + 1250);
            g_fuji[i][j].snow = (u8)((snow * 255) >> QBITS);
        }
    }
    /* Normals from the ring topology. */
    for (i = 0; i < FJ_RINGS; i++) {
        for (j = 0; j < FJ_SEGS; j++) {
            int ip = i > 0 ? i - 1 : 0, in = i < FJ_RINGS - 1 ? i + 1 : i;
            int jp = (j + FJ_SEGS - 1) % FJ_SEGS, jn = (j + 1) % FJ_SEGS;
            s32 ax = g_fuji[in][j].p.vx - g_fuji[ip][j].p.vx;
            s32 ay = g_fuji[in][j].p.vy - g_fuji[ip][j].p.vy;
            s32 az = g_fuji[in][j].p.vz - g_fuji[ip][j].p.vz;
            s32 bx = g_fuji[i][jn].p.vx - g_fuji[i][jp].p.vx;
            s32 by = g_fuji[i][jn].p.vy - g_fuji[i][jp].p.vy;
            s32 bz = g_fuji[i][jn].p.vz - g_fuji[i][jp].p.vz;
            s32 nx = (ay * bz - az * by) >> 8;
            s32 ny = (az * bx - ax * bz) >> 8;
            s32 nz = (ax * by - ay * bx) >> 8;
            di_norm3(&nx, &ny, &nz);
            /* Make it face out of the cone, i.e. upward and away from the axis. */
            if (ny < 0) { nx = -nx; ny = -ny; nz = -nz; }
            g_fuji[i][j].n.vx = (s16)nx;
            g_fuji[i][j].n.vy = (s16)ny;
            g_fuji[i][j].n.vz = (s16)nz;
        }
    }
}

/* Shade one Fuji vertex: rock or snow, lit, then pushed into the haze. */
static void fuji_shade(DrawCtx *d, const FujiVert *v, CVECTOR *out)
{
    static const s32 kRock[3] = {  66,  68, 104 };
    static const s32 kSnow[3] = { 226, 228, 244 };
    s32 base[3], n[3], c[3];
    s32 s = v->snow;
    base[0] = kRock[0] + (((kSnow[0] - kRock[0]) * s) >> 8);
    base[1] = kRock[1] + (((kSnow[1] - kRock[1]) * s) >> 8);
    base[2] = kRock[2] + (((kSnow[2] - kRock[2]) * s) >> 8);

    n[0] = v->n.vx; n[1] = v->n.vy; n[2] = v->n.vz;
    light_shade(d->lt, n, base, 600, c);
    /* Snow keeps a cold rim even unlit so the cone never goes flat black. */
    light_fog(d->lt, FUJI_Z, c);
    out->r = (u8)c[0]; out->g = (u8)c[1]; out->b = (u8)c[2];
}

void world_draw_fuji(DrawCtx *d)
{
    int i, j;
    for (i = 0; i < FJ_RINGS; i++)
        for (j = 0; j < FJ_SEGS; j++)
            fuji_shade(d, &g_fuji[i][j], &g_fuji_col[i][j]);

    cam_bind_object(d->cam, FUJI_X, WATER_Y, FUJI_Z);
    for (i = 0; i < FJ_RINGS - 1; i++) {
        for (j = 0; j < FJ_SEGS; j++) {
            int jn = (j + 1) % FJ_SEGS;
            SVECTOR v[4];
            CVECTOR c[4];
            /* Mirrored strip order so the outward face winds clockwise on
             * screen, which is what NCLIP's sign test expects. */
            v[0] = g_fuji[i][jn].p;     c[0] = g_fuji_col[i][jn];
            v[1] = g_fuji[i][j].p;      c[1] = g_fuji_col[i][j];
            v[2] = g_fuji[i + 1][jn].p; c[2] = g_fuji_col[i + 1][jn];
            v[3] = g_fuji[i + 1][j].p;  c[3] = g_fuji_col[i + 1][j];
            draw_quad(d, v, c, DRAW_CULL_BACK, 0, 0);
        }
    }
}

/* ---- far shore ---------------------------------------------------------- */

#define FAR_SEGS 20

/* Two ridges behind the lake. They share vertices along x so adjacent quads
 * cannot pull apart, and the nearer ridge hides the foot of the mountain the
 * way the tree line does in the reference paintings. */
typedef struct { s32 z, lo, hi; s32 col[3]; } FarRidge;

static const FarRidge kRidge[2] = {
    { 35500, 2100, 5200, {  52,  62,  92 } },   /* distant hills  */
    { 31000, 1100, 2900, {  34,  48,  50 } },   /* near tree line */
};

static s32 far_height(int r, s32 x)
{
    s32 a = (di_noise2(DI_RS_TERRAIN + 12 + r * 3, x, 0, 13) + ONE) >> 1;
    s32 b = (di_noise2(DI_RS_TERRAIN + 13 + r * 3, x, 0, 11) + ONE) >> 1;
    s32 c = (di_noise2(DI_RS_TERRAIN + 14 + r * 3, x, 0,  9) + ONE) >> 1;
    s32 t = fmul(a, 2200) + fmul(b, 1400) + fmul(c, 500);
    return kRidge[r].lo + fmul(kRidge[r].hi - kRidge[r].lo, DI_CLAMP(t, 0, ONE));
}

void world_draw_far(DrawCtx *d)
{
    int r, i;
    cam_bind_object(d->cam, 0, WATER_Y, 0);
    for (r = 0; r < 2; r++) {
        s32 z = kRidge[r].z;
        s32 span = 2 * 30000, step = span / FAR_SEGS;
        s32 col[3], dim[3];
        s32 n[3] = { 0, 0, -ONE };
        light_shade(d->lt, n, kRidge[r].col, ONE, col);
        light_fog(d->lt, z, col);
        dim[0] = (col[0] * 5) >> 3; dim[1] = (col[1] * 5) >> 3; dim[2] = (col[2] * 5) >> 3;

        for (i = 0; i < FAR_SEGS; i++) {
            s32 x0 = -30000 + i * step, x1 = x0 + step;
            s32 h0 = far_height(r, x0), h1 = far_height(r, x1);
            SVECTOR v[4];
            CVECTOR c[4];
            /* Mirrored strip order: the top edge is v0/v1 so the face winds
             * clockwise seen from the lake. */
            v[0].vx = (s16)x1; v[0].vy = (s16)h1; v[0].vz = (s16)z; v[0].pad = 0;
            v[1].vx = (s16)x0; v[1].vy = (s16)h0; v[1].vz = (s16)z; v[1].pad = 0;
            v[2].vx = (s16)x1; v[2].vy = 0;       v[2].vz = (s16)z; v[2].pad = 0;
            v[3].vx = (s16)x0; v[3].vy = 0;       v[3].vz = (s16)z; v[3].pad = 0;
            c[0].r = c[1].r = (u8)col[0]; c[0].g = c[1].g = (u8)col[1]; c[0].b = c[1].b = (u8)col[2];
            c[2].r = c[3].r = (u8)dim[0]; c[2].g = c[3].g = (u8)dim[1]; c[2].b = c[3].b = (u8)dim[2];
            draw_quad(d, v, c, DRAW_CULL_NONE, 0, 0);
        }
    }
}

/* ---- soil bank ---------------------------------------------------------- */

#define GN_X 11
#define GN_Z 16

static s16 g_gx[GN_X];
static s16 g_gz[GN_Z];
static SVECTOR g_gp[GN_Z][GN_X];
static SVECTOR g_gn[GN_Z][GN_X];
/* The soil's albedo comes from a 3-octave noise that never changes. It used
 * to be re-evaluated for every quad corner of every frame, which was by far
 * the most expensive thing in the renderer. It is now computed once. */
static u8      g_gbase[GN_Z][GN_X][3];
static CVECTOR g_gcol[GN_Z][GN_X];

static void ground_build(void)
{
    int i, j;
    /* Non-uniform spacing: fine where the camera starts, coarse toward the
     * shore. Keeping the near cells small is also what keeps the near-plane
     * clipper's workload to a handful of triangles per frame. */
    static const s16 kZ[GN_Z] = {
        -1200, -800, -560, -390, -260, -150, -40, 80,
         240, 440, 700, 1050, 1500, 2000, 2550, 3200
    };
    static const s16 kX[GN_X] = {
        -2400, -1550, -980, -560, -250, 0, 250, 560, 980, 1550, 2400
    };
    for (i = 0; i < GN_Z; i++) g_gz[i] = kZ[i];
    for (j = 0; j < GN_X; j++) g_gx[j] = kX[j];

    for (i = 0; i < GN_Z; i++) {
        for (j = 0; j < GN_X; j++) {
            s32 x = g_gx[j], z = g_gz[i];
            s32 n[3];
            g_gp[i][j].vx = (s16)x;
            g_gp[i][j].vy = (s16)ground_h(x, z);
            g_gp[i][j].vz = (s16)z;
            g_gp[i][j].pad = 0;
            ground_normal(x, z, n);
            g_gn[i][j].vx = (s16)n[0];
            g_gn[i][j].vy = (s16)n[1];
            g_gn[i][j].vz = (s16)n[2];

            {
                /* Damp, dark earth; it lightens on the crumbly high spots and
                 * darkens toward the waterline. */
                s32 wet = fband(z, SHORE_Z - 1300, SHORE_Z);
                s32 v   = di_fbm2(DI_RS_TERRAIN + 20, x, z, 6, 3);
                s32 b0  = 74 + fmul(26, v) - fmul(34, wet);
                s32 b1  = 54 + fmul(20, v) - fmul(24, wet);
                s32 b2  = 38 + fmul(14, v) - fmul(6,  wet);
                g_gbase[i][j][0] = (u8)DI_CLAMP(b0, 10, 255);
                g_gbase[i][j][1] = (u8)DI_CLAMP(b1, 10, 255);
                g_gbase[i][j][2] = (u8)DI_CLAMP(b2, 10, 255);
            }
        }
    }
}

static void ground_shade(DrawCtx *d, int i, int j, CVECTOR *out)
{
    s32 n[3], c[3], base[3];
    s32 z = g_gz[i];
    base[0] = g_gbase[i][j][0];
    base[1] = g_gbase[i][j][1];
    base[2] = g_gbase[i][j][2];
    n[0] = g_gn[i][j].vx; n[1] = g_gn[i][j].vy; n[2] = g_gn[i][j].vz;
    light_shade(d->lt, n, base, 420, c);
    light_fog(d->lt, z > 0 ? z : 0, c);
    out->r = (u8)c[0]; out->g = (u8)c[1]; out->b = (u8)c[2];
}

void world_draw_ground(DrawCtx *d)
{
    int i, j;
    for (i = 0; i < GN_Z; i++)
        for (j = 0; j < GN_X; j++)
            ground_shade(d, i, j, &g_gcol[i][j]);

    cam_bind_object(d->cam, 0, 0, 0);
    for (i = 0; i < GN_Z - 1; i++) {
        for (j = 0; j < GN_X - 1; j++) {
            SVECTOR v[4];
            CVECTOR c[4];
            v[0] = g_gp[i][j + 1];     c[0] = g_gcol[i][j + 1];
            v[1] = g_gp[i][j];         c[1] = g_gcol[i][j];
            v[2] = g_gp[i + 1][j + 1]; c[2] = g_gcol[i + 1][j + 1];
            v[3] = g_gp[i + 1][j];     c[3] = g_gcol[i + 1][j];

            if (g_debug & DBG_NO_TEXTURE) {
                draw_quad(d, v, c, DRAW_CULL_BACK, 0, 0);
            } else {
                /* One tile per cell, mirrored from a stable per-cell hash so
                 * the repeat does not read as a grid. UVs stay inside the
                 * soil's 64x64 corner of the page, so no texture window is
                 * needed and no primitive can wander into the petal map. */
                u32 hsh = di_hash2(DI_RS_MATERIAL + 2, i, j);
                u8 uo = (u8)mat_soil_u();
                u8 ua = (hsh & 1) ? 63 : 0, ub = (hsh & 1) ? 0 : 63;
                u8 va = (hsh & 2) ? 63 : 0, vb = (hsh & 2) ? 0 : 63;
                u8 uv[8];
                uv[0] = (u8)(uo + ub); uv[1] = va;
                uv[2] = (u8)(uo + ua); uv[3] = va;
                uv[4] = (u8)(uo + ub); uv[5] = vb;
                uv[6] = (u8)(uo + ua); uv[7] = vb;
                draw_quad_tex(d, v, c, uv, mat_soil_tpage(), mat_soil_clut(),
                              DRAW_CULL_BACK, 0);
            }
        }
    }
}

/* ---- lake --------------------------------------------------------------- */

#define WN_X 9
#define WN_Z 9

static const s16 kWz[WN_Z] = {
    1400, 2250, 3400, 5000, 7600, 11500, 17000, 23500, 30800
};
static const s16 kWx[WN_X] = {
    -26000, -14000, -7600, -3200, 0, 3200, 7600, 14000, 26000
};

/* Ripple height and the glitter term. The light's azimuth defines a band of
 * water that is turned toward the viewer; within it the wave slope decides
 * how bright each vertex gets, which is what makes the path shimmer rather
 * than sit there as a painted stripe. */
static void water_vert(DrawCtx *d, s32 x, s32 z, s32 t, SVECTOR *v, CVECTOR *c)
{
    s32 ph  = (x >> 7) + (z >> 6);
    s32 w1  = fsin((t >> 3) + ph * 7);
    s32 w2  = fsin((t >> 4) - ph * 3 + (x >> 9) * 11);
    s32 w3  = di_noise2(DI_RS_MOTION, x + (t >> 2), z, 11);
    s32 h   = (fmul(10, w1) + fmul(7, w2) + fmul(9, w3));
    s32 slope = fmul(ONE, w1) + fmul(2200, w2);
    s32 col[3], base[3], n[3];
    s32 glint, lane, dist;

    v->vx = (s16)x; v->vy = (s16)h; v->vz = (s16)z; v->pad = 0;

    base[0] = d->lt->water[0];
    base[1] = d->lt->water[1];
    base[2] = d->lt->water[2];

    /* Mirror of the cone. Not a screen-space reflection: the strength is a
     * function of how far the vertex is across the lake and how close it sits
     * to the mountain's own x extent, broken up by the wave slope so it reads
     * as a disturbed reflection rather than a painted band. */
    {
        s32 refl = fmul(fband(z, 9000, 30000),
                        ONE - fband(DI_ABS(x - FUJI_X), FUJI_R / 3, FUJI_R));
        s32 mr[3];
        s32 nn[3] = { 0, ONE, 0 };
        static const s32 kConeColour[3] = { 110, 116, 158 };
        light_shade(d->lt, nn, kConeColour, 800, mr);
        refl = fmul(refl, ONE - fmul(600, DI_ABS(w1)) / ONE);
        refl = DI_CLAMP(refl, 0, ONE);
        base[0] = flerp(base[0], mr[0], fmul(refl, 2500));
        base[1] = flerp(base[1], mr[1], fmul(refl, 2500));
        base[2] = flerp(base[2], mr[2], fmul(refl, 2500));
    }

    n[0] = fmul(slope, 300) >> QBITS; n[1] = ONE; n[2] = fmul(slope, 200) >> QBITS;
    di_norm3(&n[0], &n[1], &n[2]);
    light_shade(d->lt, n, base, 700, col);

    /* Grazing-angle sky reflection. Water seen nearly edge-on mirrors the
     * bright horizon; water underfoot shows its own dark body colour. This is
     * what separates the far half of the lake from the near half in the
     * reference paintings. */
    {
        s32 fres = fband(z, 2600, 26000);
        fres = fmul(fres, fres);
        fres = fmul(fres, 3000);
        col[0] = flerp(col[0], d->lt->sky_hor[0], fres);
        col[1] = flerp(col[1], d->lt->sky_hor[1], fres);
        col[2] = flerp(col[2], d->lt->sky_hor[2], fres);
    }

    /* Glitter path under the sun or the moon. */
    /* The path runs away from the viewer toward the light. Its lateral
     * offset is compressed so the band stays in frame when the light is well
     * off to one side - an angular cheat, not a fake reflection: the
     * shimmer itself still comes from the wave slope. */
    lane  = fmul(d->lt->ldir[0], 9000) + FUJI_X / 4;
    dist  = DI_ABS(x - lane);
    glint = ONE - fband(dist, 700, 6500);
    glint = fmul(glint, (fsin((t >> 2) + ph * 23) + ONE) >> 1);
    glint = fmul(glint, ONE - fband(z, 2400, 30000) / 2);
    glint = fmul(glint, glint);
    glint = fmul(glint, 1500);              /* keep it a sheen, not a spotlight */
    col[0] = DI_CLAMP(col[0] + fmul(d->lt->spec[0], glint), 0, 255);
    col[1] = DI_CLAMP(col[1] + fmul(d->lt->spec[1], glint), 0, 255);
    col[2] = DI_CLAMP(col[2] + fmul(d->lt->spec[2], glint), 0, 255);

    light_fog(d->lt, z, col);
    c->r = (u8)col[0]; c->g = (u8)col[1]; c->b = (u8)col[2];
}

static SVECTOR g_wp[WN_Z][WN_X];
static CVECTOR g_wc[WN_Z][WN_X];

void world_draw_water(DrawCtx *d, s32 t_ms)
{
    int i, j;
    s32 t = t_ms;
    /* LOW halves the lake's lateral tessellation: the ripple and the glitter
     * survive, the fill cost drops. */
    int step = (g_quality == Q_LOW) ? 2 : 1;

    /* One evaluation per grid vertex per frame instead of one per quad
     * corner: the ripple, the reflection and the glitter are all computed in
     * water_vert, so this is a 4x saving on the most expensive vertex in the
     * scene. */
    for (i = 0; i < WN_Z; i++)
        for (j = 0; j < WN_X; j += step)
            water_vert(d, kWx[j], kWz[i], t, &g_wp[i][j], &g_wc[i][j]);

    cam_bind_object(d->cam, 0, WATER_Y, 0);
    for (i = 0; i < WN_Z - 1; i++) {
        for (j = 0; j + step <= WN_X - 1; j += step) {
            SVECTOR v[4];
            CVECTOR c[4];
            v[0] = g_wp[i][j];            c[0] = g_wc[i][j];
            v[1] = g_wp[i][j + step];     c[1] = g_wc[i][j + step];
            v[2] = g_wp[i + 1][j];        c[2] = g_wc[i + 1][j];
            v[3] = g_wp[i + 1][j + step]; c[3] = g_wc[i + 1][j + step];
            draw_quad(d, v, c, DRAW_CULL_NONE, 0, 0);
        }
    }
}

/* ---- sky ---------------------------------------------------------------- */

#define SKY_BANDS 9

void world_draw_sky(DrawCtx *d, s32 t_ms)
{
    int i;
    /* Where a horizontal ray lands on screen, so the gradient stays locked to
     * the world as the camera tilts. */
    s32 pitch = d->cam->pitch;
    s32 cp = fcos(pitch);
    s32 horizon = SCREEN_H_HALF + (cp ? (fsin(pitch) * d->cam->h) / cp : 0);
    s32 top = horizon - 300, span = 300;
    (void)t_ms;

    for (i = 0; i < SKY_BANDS; i++) {
        s32 y0 = top + (span * i) / SKY_BANDS;
        s32 y1 = top + (span * (i + 1)) / SKY_BANDS;
        s32 t0 = (i * ONE) / SKY_BANDS, t1 = ((i + 1) * ONE) / SKY_BANDS;
        DVECTOR p[4];
        CVECTOR c[4];
        s32 a[3], b[3];
        int k;
        for (k = 0; k < 3; k++) {
            const s32 *tp = d->lt->sky_top, *md = d->lt->sky_mid, *hr = d->lt->sky_hor;
            a[k] = t0 < ONE / 2 ? flerp(tp[k], md[k], t0 * 2)
                                : flerp(md[k], hr[k], (t0 - ONE / 2) * 2);
            b[k] = t1 < ONE / 2 ? flerp(tp[k], md[k], t1 * 2)
                                : flerp(md[k], hr[k], (t1 - ONE / 2) * 2);
        }
        if (y1 < 0 || y0 > SCREEN_H) continue;
        p[0].x = -8;           p[0].y = (s16)y0;
        p[1].x = SCREEN_W + 8; p[1].y = (s16)y0;
        p[2].x = -8;           p[2].y = (s16)y1;
        p[3].x = SCREEN_W + 8; p[3].y = (s16)y1;
        c[0].r = c[1].r = (u8)a[0]; c[0].g = c[1].g = (u8)a[1]; c[0].b = c[1].b = (u8)a[2];
        c[2].r = c[3].r = (u8)b[0]; c[2].g = c[3].g = (u8)b[1]; c[2].b = c[3].b = (u8)b[2];
        draw_gouraud_quad_screen(d, p, c, OT_SIZE - 2, 0, 0);
    }
    /* Backdrop fills, so the frame is always fully covered without relying on
     * a VRAM clear.
     *
     * Above the gradient the fill is sky. BELOW the horizon it is deliberately
     * NOT sky but a dark earth tone: that band is only ever visible through a
     * gap in the near-field terrain, and a dark gap reads as shadow while a
     * bright one reads as a hole punched in the ground. See ISSUES.md. */
    {
        CVECTOR ct, cb;
        s32 e[3];
        ct.r = (u8)d->lt->sky_top[0]; ct.g = (u8)d->lt->sky_top[1]; ct.b = (u8)d->lt->sky_top[2];
        e[0] = fmul(d->lt->amb[0], 52);
        e[1] = fmul(d->lt->amb[1], 38);
        e[2] = fmul(d->lt->amb[2], 28);
        cb.r = (u8)DI_CLAMP(e[0], 4, 255);
        cb.g = (u8)DI_CLAMP(e[1], 4, 255);
        cb.b = (u8)DI_CLAMP(e[2], 4, 255);
        if (top > 0)
            draw_tile_screen(d, 0, 0, SCREEN_W, top, &ct, OT_SIZE - 1, 0, 0);
        if (top + span < SCREEN_H)
            draw_tile_screen(d, 0, top + span, SCREEN_W, SCREEN_H - (top + span),
                             &cb, OT_SIZE - 1, 0, 0);
    }
}

void world_init(void)
{
    fuji_build();
    ground_build();
}
