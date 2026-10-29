/* iris.c - morphogenesis of the plant.
 *
 * Every part of the plant - sprout, leaves, scape, bud sheath, falls and
 * standards - is the same primitive: a RIBBON. A ribbon is a centreline
 * integrated from an elevation angle that varies along its own length, with a
 * width profile, a twist and a fold ridge. That single rule, applied at
 * different levels of the structure with different parameters, is the FVA
 * grammar for this piece:
 *
 *     Primitive : ribbon segment
 *     Node      : one sample along a centreline (position, frame, width)
 *     Branch    : a whole ribbon (leaf, petal, scape)
 *     Organism  : the ordered set of ribbons that make the plant
 *
 * Nothing is ever switched on. The bud becomes the bloom because the same six
 * ribbons change their elevation, bend and width over time; at every instant
 * the geometry is evaluated from the clock, so stopping at any moment gives a
 * valid intermediate form rather than a blend between two stored models.
 */
#include "iris.h"
#include "world.h"
#include "scene.h"
#include "materials.h"

#define RIB_SEG 7                   /* centreline samples - 1 */
#define RIB_COLS 3                  /* left edge, fold ridge, right edge */

/* Plant anatomy, in world units (1 unit ~ 1 mm). */
#define PLANT_X 0
#define PLANT_Z 0
#define LEAF_COUNT 6

typedef struct {
    s32 yaw;        /* azimuth around the stem        */
    s32 elev0;      /* elevation at the base          */
    s32 bend;       /* elevation lost along the length */
    s32 len;
    s32 wmax;       /* half width at the widest point */
    s32 twist;
    s32 ridge;      /* fold depth along the centre    */
    s32 wshape;     /* 0 = sword taper, ONE = petal blade */
    s32 lift;       /* vertical offset of the base    */
    s32 radial;     /* horizontal offset of the base  */
    s32 phase;      /* wind phase offset              */
    s32 flex;       /* how much the wind bends it     */
} Ribbon;

typedef struct {
    s32 r, g, b;
} Col3;

/* Base colours read off the reference paintings. */
static const Col3 kLeaf     = {  62,  98,  54 };
static const Col3 kLeafTip  = {  92, 132,  70 };
static const Col3 kStem     = {  70, 106,  58 };
static const Col3 kSheath   = {  78, 112,  62 };
static const Col3 kFall     = {  74,  52, 158 };
static const Col3 kFallEdge = { 116,  92, 212 };
static const Col3 kStandard = {  92,  66, 182 };
static const Col3 kBeard    = { 214, 164,  52 };
static const Col3 kSeed     = { 122, 128,  96 };

static s32 g_base_y;

void iris_init(void)
{
    g_base_y = ground_h(PLANT_X, PLANT_Z);
}

/* ---- width profiles -----------------------------------------------------
 * wshape = 0  : ensiform leaf - rises fast, tapers to a point
 * wshape = ONE: petal blade  - narrow claw, wide blade, rounded tip
 */
static s32 ribbon_width(const Ribbon *rb, s32 u)
{
    s32 sword, blade;
    /* sword: min(1, u*7) * (1-u)^0.7, approximated with two multiplies */
    s32 ramp = DI_MIN(ONE, u * 7);
    s32 tail = ONE - u;
    sword = fmul(ramp, fmul(tail, (tail + ONE) >> 1));
    /* blade: narrow claw at the base, broad in the middle, rounded tip */
    blade = fsin(fmul(u, 1900) + 90);
    if (blade < 0) blade = 0;
    return fmul(rb->wmax, flerp(sword, blade, rb->wshape));
}

/* ---- the one evaluator --------------------------------------------------
 * Fills pos[] (RIB_SEG+1) x RIB_COLS and the matching normals.
 */
static void ribbon_eval(const Ribbon *rb, s32 t_ms, s32 wind,
                        SVECTOR pos[][RIB_COLS], SVECTOR nrm[][RIB_COLS],
                        u8 uv[][RIB_COLS][2])
{
    s32 px = fmul(rb->radial, fsin(rb->yaw));
    s32 py = rb->lift;
    s32 pz = fmul(rb->radial, fcos(rb->yaw));
    s32 ds = rb->len / RIB_SEG;
    s32 sy = fsin(rb->yaw), cy = fcos(rb->yaw);
    s32 wdx, wdz;
    int i, c;

    wind_eval(PLANT_X, PLANT_Z, t_ms + rb->phase, wind, &wdx, &wdz);

    for (i = 0; i <= RIB_SEG; i++) {
        s32 u  = (i * ONE) / RIB_SEG;
        s32 uu = fmul(u, u);
        /* Elevation falls off along the length; the square makes the droop
         * accumulate toward the tip the way a real leaf arches. */
        s32 e  = rb->elev0 - fmul(rb->bend, fmul(uu, (u + ONE) >> 1));
        s32 ce = fcos(e), se = fsin(e);
        s32 tx = fmul(ce, sy), ty = se, tz = fmul(ce, cy);
        s32 tw = fmul(rb->twist, u);
        s32 rx = cy, ry = 0, rz = -sy;
        s32 ux, uy, uz, nx, ny, nz, w;
        s32 bx, by, bz;

        /* up = tangent x right */
        ux = fmul(ty, rz) - fmul(tz, ry);
        uy = fmul(tz, rx) - fmul(tx, rz);
        uz = fmul(tx, ry) - fmul(ty, rx);

        /* twist the cross-section around the tangent */
        {
            s32 ct = fcos(tw), st = fsin(tw);
            s32 nrx = fmul(rx, ct) + fmul(ux, st);
            s32 nry = fmul(ry, ct) + fmul(uy, st);
            s32 nrz = fmul(rz, ct) + fmul(uz, st);
            rx = nrx; ry = nry; rz = nrz;
        }
        /* surface normal = tangent x right */
        nx = fmul(ty, rz) - fmul(tz, ry);
        ny = fmul(tz, rx) - fmul(tx, rz);
        nz = fmul(tx, ry) - fmul(ty, rx);
        di_norm3(&nx, &ny, &nz);

        w = ribbon_width(rb, u);

        /* Wind bends the ribbon; displacement grows with u^2 so the base
         * stays planted and the tip does the moving. */
        bx = px + fmul(fmul(wdx, rb->flex), uu);
        by = py;
        bz = pz + fmul(fmul(wdz, rb->flex), uu);

        for (c = 0; c < RIB_COLS; c++) {
            s32 off = (c - 1) * w;                 /* -w, 0, +w */
            s32 ridge = (c == 1) ? rb->ridge : 0;
            pos[i][c].vx = (s16)(bx + fmul(rx, off) + fmul(nx, ridge));
            pos[i][c].vy = (s16)(by + fmul(ry, off) + fmul(ny, ridge));
            pos[i][c].vz = (s16)(bz + fmul(rz, off) + fmul(nz, ridge));
            pos[i][c].pad = 0;
            nrm[i][c].vx = (s16)nx;
            nrm[i][c].vy = (s16)ny;
            nrm[i][c].vz = (s16)nz;
            /* The petal map lives in a 64x64 corner of the page, so the
             * ribbon's own parameters are scaled into that range: u along
             * the blade, v across it. The veins therefore follow the surface
             * when it bends. */
            uv[i][c][0] = (u8)(mat_petal_u() + ((u * 63) >> QBITS));
            uv[i][c][1] = (u8)(c * 31);
        }
        px += fmul(tx, ds); py += fmul(ty, ds); pz += fmul(tz, ds);
    }
}

/* Shade one ribbon vertex, both faces: a thin leaf lit from behind should
 * still read, so the normal is flipped toward the light. */
static void ribbon_colour(DrawCtx *d, const SVECTOR *n, const Col3 *base,
                          s32 wrap, CVECTOR *out)
{
    s32 nn[3] = { n->vx, n->vy, n->vz };
    s32 bb[3] = { base->r, base->g, base->b };
    s32 c[3];
    if (fmul(nn[0], d->lt->ldir[0]) + fmul(nn[1], d->lt->ldir[1])
      + fmul(nn[2], d->lt->ldir[2]) < 0) {
        nn[0] = -nn[0]; nn[1] = -nn[1]; nn[2] = -nn[2];
    }
    light_shade(d->lt, nn, bb, wrap, c);
    out->r = (u8)c[0]; out->g = (u8)c[1]; out->b = (u8)c[2];
}

static Col3 col_lerp(const Col3 *a, const Col3 *b, s32 t)
{
    Col3 o;
    o.r = flerp(a->r, b->r, t);
    o.g = flerp(a->g, b->g, t);
    o.b = flerp(a->b, b->b, t);
    return o;
}

/* Emit a ribbon as two columns of quads. `tipcol` lets the colour run along
 * the length, `edgecol` across it. */
static void ribbon_draw(DrawCtx *d, const Ribbon *rb, s32 t_ms, s32 wind,
                        const Col3 *rootcol, const Col3 *tipcol,
                        const Col3 *edgecol, s32 edgemix, s32 wrap, int textured)
{
    SVECTOR pos[RIB_SEG + 1][RIB_COLS];
    SVECTOR nrm[RIB_SEG + 1][RIB_COLS];
    u8      uv [RIB_SEG + 1][RIB_COLS][2];
    int i, c;

    if (rb->len < 12 || rb->wmax < 2) return;
    ribbon_eval(rb, t_ms, wind, pos, nrm, uv);

    for (i = 0; i < RIB_SEG; i++) {
        for (c = 0; c < RIB_COLS - 1; c++) {
            SVECTOR v[4];
            CVECTOR col[4];
            int k;
            static const int oi[4] = { 0, 0, 1, 1 };
            static const int oc[4] = { 1, 0, 1, 0 };
            for (k = 0; k < 4; k++) {
                s32 ui = ((i + oi[k]) * ONE) / RIB_SEG;
                s32 across = (c + oc[k]) == 1 ? 0 : ONE;   /* ridge vs edge */
                Col3 base = col_lerp(rootcol, tipcol, ui);
                base = col_lerp(&base, edgecol, fmul(across, edgemix));
                v[k] = pos[i + oi[k]][c + oc[k]];
                ribbon_colour(d, &nrm[i + oi[k]][c + oc[k]], &base, wrap, &col[k]);
            }
            /* Leaves and petals are thin: draw both faces. */
            if (textured && !(g_debug & DBG_NO_TEXTURE)) {
                u8 tc[8];
                for (k = 0; k < 4; k++) {
                    tc[k * 2]     = uv[i + oi[k]][c + oc[k]][0];
                    tc[k * 2 + 1] = uv[i + oi[k]][c + oc[k]][1];
                }
                draw_quad_tex(d, v, col, tc, mat_petal_tpage(), mat_petal_clut(),
                              DRAW_CULL_NONE, 0);
            } else {
                draw_quad(d, v, col, DRAW_CULL_NONE, 0, 0);
            }
        }
    }
}

/* ---- the seed -----------------------------------------------------------
 * A smooth ovoid resting in the soil. It is real geometry, not a sprite, and
 * it splits open as the sprout pushes through.
 */
#define SEED_RINGS 5
#define SEED_SEGS  8

static void seed_draw(DrawCtx *d, s32 t_ms)
{
    s32 vis   = ONE - fband(t_ms, 9000, 15000);
    s32 split = fband(t_ms, 5000, 12000);
    s32 rad   = 46, hgt = 34;
    int i, j;
    if (vis <= 0) return;

    for (i = 0; i < SEED_RINGS - 1; i++) {
        for (j = 0; j < SEED_SEGS; j++) {
            SVECTOR v[4];
            CVECTOR c[4];
            int k;
            static const int oi[4] = { 0, 0, 1, 1 };
            static const int oj[4] = { 1, 0, 1, 0 };
            for (k = 0; k < 4; k++) {
                int ri = i + oi[k], rj = (j + oj[k]) % SEED_SEGS;
                s32 th = (ri * ANG_180) / (SEED_RINGS - 1) - ANG_90;
                s32 ph = (rj * ANG_360) / SEED_SEGS;
                s32 cr = fcos(th), sr = fsin(th);
                s32 nx = fmul(cr, fsin(ph)), ny = sr, nz = fmul(cr, fcos(ph));
                /* The two halves part along x as the sprout emerges. */
                s32 gap = fmul(split, 30) * (fsin(ph) > 0 ? 1 : -1);
                SVECTOR nv;
                Col3 base = kSeed;
                v[k].vx = (s16)(fmul(nx, rad) + gap);
                v[k].vy = (s16)(fmul(ny, hgt) + hgt - fmul(vis < ONE ? ONE - vis : 0, 40));
                v[k].vz = (s16)fmul(nz, rad);
                v[k].pad = 0;
                nv.vx = (s16)nx; nv.vy = (s16)ny; nv.vz = (s16)nz;
                base.r = flerp(base.r, 86, fmul(sr < 0 ? -sr : 0, ONE));
                ribbon_colour(d, &nv, &base, 300, &c[k]);
            }
            draw_quad(d, v, c, DRAW_CULL_NONE, 0, 0);
        }
    }
}

/* ---- the plant ---------------------------------------------------------- */

void iris_draw(DrawCtx *d, s32 t_ms)
{
    s32 wind = g_dir.wind;
    s32 i;

    /* Development envelopes. Each one is a smooth band over the clock, so the
     * plant is always mid-transition rather than in a discrete stage. */
    s32 sprout = fband(t_ms,  6000, 16000);
    s32 stemg  = fband(t_ms, 20000, 50000);
    s32 budg   = fband(t_ms, 27000, 44000);
    s32 open   = fband(t_ms, 42000, 62000);

    cam_bind_object(d->cam, PLANT_X, g_base_y, PLANT_Z);

    seed_draw(d, t_ms);

    /* --- leaves: six ensiform blades, staggered in time and azimuth ------ */
    for (i = 0; i < LEAF_COUNT; i++) {
        Ribbon rb;
        s32 st = 6500 + i * 2700;
        s32 g  = fband(t_ms, st, st + 15000);
        s32 jit = di_hashn(DI_RS_IRIS, i, 0);
        /* Iris leaves are equitant: they form a flat fan, not a rosette.
         * Each successive pair leans further out from the centre line. */
        int side = (i & 1) ? 1 : -1;
        int rank = i >> 1;
        if (g <= 0) continue;
        rb.yaw    = ANG_90 + (side > 0 ? 0 : ANG_180) + fmul(jit, 150)
                  + side * rank * 40;
        rb.elev0  = ANG_90 - 24 - rank * 66 - fmul(jit, 40);
        rb.bend   = 300 + rank * 90 + fmul(jit, 120) + fmul(g, 230);
        rb.len    = fmul(g, 820 + fmul(jit, 180));
        rb.wmax   = fmul(g, 34 + fmul(jit, 8));
        rb.twist  = fmul(jit, 260);
        rb.ridge  = -7;
        rb.wshape = 0;
        rb.lift   = 6;
        rb.radial = 14 + fmul(g, 20);
        rb.phase  = i * 430;
        rb.flex   = 900 + fmul(jit, 300);
        ribbon_draw(d, &rb, t_ms, wind, &kLeaf, &kLeafTip, &kLeafTip, 1400, 800, 0);
    }

    /* --- the scape ------------------------------------------------------- */
    if (stemg > 0) {
        Ribbon rb;
        rb.yaw    = 300;
        rb.elev0  = ANG_90 - 26;
        rb.bend   = 70;
        rb.len    = fmul(stemg, 1010);
        rb.wmax   = 13;
        rb.twist  = 0;
        rb.ridge  = 0;
        rb.wshape = 0;
        rb.lift   = 0;
        rb.radial = 0;
        rb.phase  = 0;
        rb.flex   = 420;
        /* Three ribbons at 60 degrees make a stem that still has a silhouette
         * when the camera orbits. */
        for (i = 0; i < 3; i++) {
            rb.twist = (s32)i * (ANG_360 / 6);
            rb.yaw   = 300 + (s32)i * (ANG_360 / 3);
            ribbon_draw(d, &rb, t_ms, wind, &kStem, &kLeafTip, &kStem, 0, 600, 0);
        }
    }

    /* --- bud sheath and flower ------------------------------------------- */
    if (budg > 0) {
        s32 head = fmul(stemg, 1010);          /* height of the flower head */
        s32 shut = ONE - open;                 /* 1 while the bud is closed */

        /* Three sheath bracts that wrap the bud and peel back as it opens. */
        for (i = 0; i < 3; i++) {
            Ribbon rb;
            s32 jit = di_hashn(DI_RS_IRIS + 2, i, 0);
            rb.yaw    = (i * ANG_360) / 3 + 180;
            rb.elev0  = ANG_90 - 120 - fmul(open, 520);
            rb.bend   = 180 + fmul(open, 900);
            rb.len    = fmul(budg, 260 + fmul(jit, 40));
            rb.wmax   = fmul(budg, 26);
            rb.twist  = fmul(jit, 200);
            rb.ridge  = -5;
            rb.wshape = 600;
            rb.lift   = head - 120;
            rb.radial = 10;
            rb.phase  = i * 700;
            rb.flex   = 300;
            ribbon_draw(d, &rb, t_ms, wind, &kSheath, &kLeaf, &kLeaf, 900, 700, 0);
        }

        /* Six tepals. Three falls hang outward and down; three standards stay
         * upright. While `open` is 0 they are pressed together into a spindle,
         * so the bud silhouette IS the flower, just closed. */
        for (i = 0; i < 6; i++) {
            Ribbon rb;
            int outer = (i & 1) == 0;
            int idx = i >> 1;
            s32 jit = di_hashn(DI_RS_IRIS + 4, i, 0);
            /* Each tepal opens on its own schedule. */
            s32 o = fband(t_ms, 42000 + idx * 2400 + (outer ? 0 : 5200),
                                58000 + idx * 2400 + (outer ? 0 : 5200));
            Col3 root, tip, edge;

            rb.yaw    = (idx * ANG_360) / 3 + (outer ? 0 : ANG_360 / 6)
                      + fmul(jit, 90);
            rb.len    = fmul(budg, outer ? 250 : 215) + fmul(o, outer ? 40 : 15);
            rb.wmax   = fmul(budg, 15) + fmul(o, outer ? 92 : 48);
            rb.ridge  = outer ? -9 : -6;
            rb.wshape = flerp(300, ONE, o);
            rb.lift   = head - (outer ? 36 : 10);
            rb.radial = 7 + fmul(o, outer ? 26 : 14);
            rb.phase  = i * 520;
            rb.flex   = 240;
            rb.twist  = fmul(jit, 150) + fmul(o, outer ? 150 : -220);

            if (outer) {
                /* Falls: start vertical, swing out, then hang. The bend is
                 * what turns the blade over - the tip ends up pointing down
                 * and slightly back under the flower. */
                rb.elev0 = ANG_90 - 30 - fmul(o, 790);
                rb.bend  = 110 + fmul(o, 1820);
                root = kFall; tip = kFall; edge = kFallEdge;
            } else {
                /* Standards: stay up and arch inward over the throat. */
                rb.elev0 = ANG_90 - 14 + fmul(o, 40);
                rb.bend  = 80 + fmul(o, 560);
                root = kStandard; tip = kFallEdge; edge = kStandard;
            }
            /* Closed tepals are green-tinged like the bud they came from. */
            root = col_lerp(&kSheath, &root, DI_MAX(o, fmul(open, 2200)));
            tip  = col_lerp(&kSheath, &tip,  DI_MAX(o, fmul(open, 2200)));
            edge = col_lerp(&kSheath, &edge, DI_MAX(o, fmul(open, 2200)));
            (void)shut;

            ribbon_draw(d, &rb, t_ms, wind, &root, &tip, &edge, 1500, 900, 1);

            /* The beard: a short fuzzy ridge of ochre down the middle of each
             * fall, the signature mark of a bearded iris. */
            if (outer && o > 600) {
                Ribbon bd = rb;
                bd.wmax   = fmul(o, 11);
                bd.len    = fmul(rb.len, 1900);   /* only the upper blade */
                bd.ridge  = -15;
                bd.wshape = 200;
                ribbon_draw(d, &bd, t_ms, wind, &kBeard, &kFall, &kFall, 2600, ONE, 0);
            }
        }
    }
    (void)sprout;
}
