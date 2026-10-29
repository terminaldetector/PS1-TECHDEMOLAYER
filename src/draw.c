#include "draw.h"
#include "gte.h"
#include "platform.h"

/* The matrix cam_bind_* last pushed to the GTE, mirrored so the CPU-side
 * near-plane clipper can transform the same vertices. */
extern MATRIX g_bound_mtx;

void draw_begin(DrawCtx *d, RenderList *rl, const Camera *c, const Lighting *l)
{
    d->rl = rl; d->cam = c; d->lt = l;
    d->near_z = 160;
    d->far_z  = 62000;
    d->submitted = d->culled_back = d->culled_near = 0;
    d->culled_screen = d->clipped = d->dropped = 0;
}

static inline int offscreen3(const DVECTOR *p, int n)
{
    int i, l = 0, r = 0, t = 0, b = 0;
    for (i = 0; i < n; i++) {
        if (p[i].x <  0)            l++;
        if (p[i].x >= SCREEN_W)     r++;
        if (p[i].y <  0)            t++;
        if (p[i].y >= SCREEN_H)     b++;
    }
    return (l == n) || (r == n) || (t == n) || (b == n);
}

/* ---- CPU path for triangles that touch the near plane ------------------ */

typedef struct { s32 x, y, z; s32 r, g, b; s32 u, v; } VVert;

static void to_view(const SVECTOR *v, const CVECTOR *c, VVert *o)
{
    const MATRIX *m = &g_bound_mtx;
    o->x = ((s32)m->m[0][0] * v->vx + (s32)m->m[0][1] * v->vy + (s32)m->m[0][2] * v->vz) >> QBITS;
    o->y = ((s32)m->m[1][0] * v->vx + (s32)m->m[1][1] * v->vy + (s32)m->m[1][2] * v->vz) >> QBITS;
    o->z = ((s32)m->m[2][0] * v->vx + (s32)m->m[2][1] * v->vy + (s32)m->m[2][2] * v->vz) >> QBITS;
    o->x += m->t[0]; o->y += m->t[1]; o->z += m->t[2];
    o->r = c->r; o->g = c->g; o->b = c->b;
    o->u = 0; o->v = 0;
}

static VVert vlerp(const VVert *a, const VVert *b, s32 t)
{
    VVert o;
    o.x = a->x + (s32)(((s64)(b->x - a->x) * t) >> QBITS);
    o.y = a->y + (s32)(((s64)(b->y - a->y) * t) >> QBITS);
    o.z = a->z + (s32)(((s64)(b->z - a->z) * t) >> QBITS);
    o.r = flerp(a->r, b->r, t);
    o.g = flerp(a->g, b->g, t);
    o.b = flerp(a->b, b->b, t);
    o.u = flerp(a->u, b->u, t);
    o.v = flerp(a->v, b->v, t);
    return o;
}

/* Sutherland-Hodgman against the single plane z = near. */
static int clip_near(const VVert *in, int n, s32 nz, VVert *out)
{
    int i, m = 0;
    for (i = 0; i < n; i++) {
        const VVert *a = &in[i];
        const VVert *b = &in[(i + 1) % n];
        int ina = a->z >= nz, inb = b->z >= nz;
        if (ina) out[m++] = *a;
        if (ina != inb) {
            s32 denom = b->z - a->z;
            s32 t = fdiv(nz - a->z, denom);
            out[m++] = vlerp(a, b, DI_CLAMP(t, 0, ONE));
        }
        if (m >= 7) break;
    }
    return m;
}

/* Projected vertex, used by the screen-space clipper. */
typedef struct { s32 x, y, r, g, b, u, v; } PVert;

static PVert plerp(const PVert *a, const PVert *b, s32 t)
{
    PVert o;
    o.x = flerp(a->x, b->x, t);
    o.y = flerp(a->y, b->y, t);
    o.r = flerp(a->r, b->r, t);
    o.g = flerp(a->g, b->g, t);
    o.b = flerp(a->b, b->b, t);
    o.u = flerp(a->u, b->u, t);
    o.v = flerp(a->v, b->v, t);
    return o;
}

/* Clip a convex 2D polygon against one axis-aligned half plane.
 * side: 0 = x>=lim, 1 = x<=lim, 2 = y>=lim, 3 = y<=lim. */
static int clip_plane(const PVert *in, int n, int side, s32 lim, PVert *out)
{
    int i, m = 0;
    for (i = 0; i < n && m < 14; i++) {
        const PVert *a = &in[i];
        const PVert *b = &in[(i + 1) % n];
        s32 da, db;
        int ina, inb;
        switch (side) {
        case 0:  da = a->x - lim; db = b->x - lim; break;
        case 1:  da = lim - a->x; db = lim - b->x; break;
        case 2:  da = a->y - lim; db = b->y - lim; break;
        default: da = lim - a->y; db = lim - b->y; break;
        }
        ina = da >= 0; inb = db >= 0;
        if (ina) out[m++] = *a;
        if (ina != inb && m < 14) {
            s32 den = da - db;
            s32 t = fdiv(da, den);
            out[m++] = plerp(a, b, DI_CLAMP(t, 0, ONE));
        }
    }
    return m;
}

/* The GPU drops polygons wider than 1023 or taller than 511, so the clipped
 * path trims to a slightly enlarged screen rect rather than clamping
 * coordinates and producing a 2047-pixel-wide triangle. */
#define CLIP_MARGIN 48

static int clip_screen(PVert *poly, int n)
{
    PVert tmp[16];
    n = clip_plane(poly, n, 0, -CLIP_MARGIN,            tmp);  if (n < 3) return 0;
    n = clip_plane(tmp,  n, 1, SCREEN_W + CLIP_MARGIN,  poly); if (n < 3) return 0;
    n = clip_plane(poly, n, 2, -CLIP_MARGIN,            tmp);  if (n < 3) return 0;
    n = clip_plane(tmp,  n, 3, SCREEN_H + CLIP_MARGIN,  poly); if (n < 3) return 0;
    return n;
}

static void emit_poly_screen(DrawCtx *d, PVert *poly, int n, s32 otz,
                             int cull, int semi, u32 tpage, u32 clut, int tex)
{
    int i;
    n = clip_screen(poly, n);
    if (n < 3) { d->culled_screen++; return; }

    for (i = 1; i + 1 < n; i++) {
        const PVert *a = &poly[0], *b = &poly[i], *c = &poly[i + 1];
        s64 area = (s64)a->x * (b->y - c->y) + (s64)b->x * (c->y - a->y)
                 + (s64)c->x * (a->y - b->y);
        if (cull == DRAW_CULL_BACK  && area <= 0) { d->culled_back++; continue; }
        if (cull == DRAW_CULL_FRONT && area >= 0) { d->culled_back++; continue; }
        if (area == 0) continue;

        if (tex) {
            PolyGT3 *p = (PolyGT3 *)render_alloc(d->rl, sizeof(PolyGT3));
            if (!p) { d->dropped++; return; }
            p->rgb0 = rgb_cmd(GPU_POLY_GT3 | (semi ? GPU_SEMITRANS : 0),
                              (u32)a->r, (u32)a->g, (u32)a->b);
            p->xy0 = xy_pack(a->x, a->y);
            p->uv0 = uv_pack((u32)a->u, (u32)a->v, clut);
            p->rgb1 = rgb_cmd(0, (u32)b->r, (u32)b->g, (u32)b->b);
            p->xy1 = xy_pack(b->x, b->y);
            p->uv1 = uv_pack((u32)b->u, (u32)b->v, tpage);
            p->rgb2 = rgb_cmd(0, (u32)c->r, (u32)c->g, (u32)c->b);
            p->xy2 = xy_pack(c->x, c->y);
            p->uv2 = uv_pack((u32)c->u, (u32)c->v, 0);
            render_link(d->rl, ot_index(otz), p, 9);
        } else {
            PolyG3 *p = (PolyG3 *)render_alloc(d->rl, sizeof(PolyG3));
            if (!p) { d->dropped++; return; }
            p->rgb0 = rgb_cmd(GPU_POLY_G3 | (semi ? GPU_SEMITRANS : 0),
                              (u32)a->r, (u32)a->g, (u32)a->b);
            p->xy0  = xy_pack(a->x, a->y);
            p->rgb1 = rgb_cmd(0, (u32)b->r, (u32)b->g, (u32)b->b);
            p->xy1  = xy_pack(b->x, b->y);
            p->rgb2 = rgb_cmd(0, (u32)c->r, (u32)c->g, (u32)c->b);
            p->xy2  = xy_pack(c->x, c->y);
            render_link(d->rl, ot_index(otz), p, 6);
        }
        d->submitted++;
    }
}

/* Full CPU fallback: transform, clip against the near plane, project, clip
 * against the screen rect, fan-triangulate. */
static void draw_poly_clipped(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
                              int n, int cull, int semi,
                              const u8 *uv, u32 tpage, u32 clut)
{
    VVert in[4], out[8];
    PVert poly[16];
    int m, i;
    s32 zsum = 0, h = d->cam->h;

    for (i = 0; i < n; i++) {
        to_view(&v[i], &col[i], &in[i]);
        if (uv) { in[i].u = uv[i * 2]; in[i].v = uv[i * 2 + 1]; }
    }
    m = clip_near(in, n, d->near_z, out);
    if (m < 3) { d->culled_near++; return; }
    d->clipped++;

    for (i = 0; i < m; i++) zsum += out[i].z;
    zsum /= m;
    if (zsum > d->far_z) { d->culled_near++; return; }

    for (i = 0; i < m; i++) {
        s32 z = out[i].z < 1 ? 1 : out[i].z;
        /* h is at most 280 and view coordinates stay well inside +-1e6, so
         * the product cannot overflow s32 and no 64-bit divide is needed. */
        poly[i].x = SCREEN_W_HALF + (DI_CLAMP(out[i].x, -4000000, 4000000) * h) / z;
        poly[i].y = SCREEN_H_HALF + (DI_CLAMP(out[i].y, -4000000, 4000000) * h) / z;
        poly[i].r = out[i].r; poly[i].g = out[i].g; poly[i].b = out[i].b;
        poly[i].u = DI_CLAMP(out[i].u, 0, 255);
        poly[i].v = DI_CLAMP(out[i].v, 0, 255);
    }
    emit_poly_screen(d, poly, m, zsum, cull, semi, tpage, clut, uv != NULL);
}

/* ---- fast GTE path ------------------------------------------------------ */

/* Anything whose projected extent approaches the GPU's 1023x511 primitive
 * limit is re-done on the CPU path, which trims it to the screen rect. */
static int too_big(const DVECTOR *p, int n)
{
    int i, minx = p[0].x, maxx = p[0].x, miny = p[0].y, maxy = p[0].y;
    for (i = 1; i < n; i++) {
        if (p[i].x < minx) minx = p[i].x;
        if (p[i].x > maxx) maxx = p[i].x;
        if (p[i].y < miny) miny = p[i].y;
        if (p[i].y > maxy) maxy = p[i].y;
    }
    return (maxx - minx) > 900 || (maxy - miny) > 450;
}

static int cull_ok(int cull, s32 nclip)
{
    if (cull == DRAW_CULL_BACK)  return nclip > 0;
    if (cull == DRAW_CULL_FRONT) return nclip < 0;
    return 1;
}

void draw_tri(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
              int cull, int semi, int abr)
{
    TriProj tp;
    PolyG3 *p;
    (void)abr;

    gte_project_tri(&v[0], &v[1], &v[2], &tp);

    if (tp.sz[0] < d->near_z * 2 || tp.sz[1] < d->near_z * 2 || tp.sz[2] < d->near_z * 2) {
        draw_poly_clipped(d, v, col, 3, cull, semi, NULL, 0, 0);
        return;
    }
    if (tp.otz > d->far_z) { d->culled_near++; return; }
    if (!cull_ok(cull, tp.nclip)) { d->culled_back++; return; }
    if (offscreen3(tp.s, 3)) { d->culled_screen++; return; }
    if (too_big(tp.s, 3)) { draw_poly_clipped(d, v, col, 3, cull, semi, NULL, 0, 0); return; }

    p = (PolyG3 *)render_alloc(d->rl, sizeof(PolyG3));
    if (!p) { d->dropped++; return; }
    p->rgb0 = rgb_cmd(GPU_POLY_G3 | (semi ? GPU_SEMITRANS : 0), col[0].r, col[0].g, col[0].b);
    p->xy0  = xy_pack(tp.s[0].x, tp.s[0].y);
    p->rgb1 = rgb_cmd(0, col[1].r, col[1].g, col[1].b);
    p->xy1  = xy_pack(tp.s[1].x, tp.s[1].y);
    p->rgb2 = rgb_cmd(0, col[2].r, col[2].g, col[2].b);
    p->xy2  = xy_pack(tp.s[2].x, tp.s[2].y);
    render_link(d->rl, ot_index(tp.otz), p, 6);
    d->submitted++;
}

void draw_quad(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
               int cull, int semi, int abr)
{
    TriProj tp;
    DVECTOR s3;
    s32 z3, otz;
    PolyG4 *p;
    (void)abr;

    gte_project_tri(&v[0], &v[1], &v[2], &tp);
    z3 = gte_project_point(&v[3], &s3);

    if (tp.sz[0] < d->near_z * 2 || tp.sz[1] < d->near_z * 2 ||
        tp.sz[2] < d->near_z * 2 || z3 < d->near_z * 2) {
        /* Re-order the strip into a loop before clipping. */
        SVECTOR lv[4]; CVECTOR lc[4];
        lv[0] = v[0]; lv[1] = v[1]; lv[2] = v[3]; lv[3] = v[2];
        lc[0] = col[0]; lc[1] = col[1]; lc[2] = col[3]; lc[3] = col[2];
        draw_poly_clipped(d, lv, lc, 4, cull, semi, NULL, 0, 0);
        return;
    }
    otz = (tp.sz[0] + tp.sz[1] + tp.sz[2] + z3) >> 2;
    if (otz > d->far_z) { d->culled_near++; return; }
    if (!cull_ok(cull, tp.nclip)) { d->culled_back++; return; }
    {
        DVECTOR all[4];
        all[0] = tp.s[0]; all[1] = tp.s[1]; all[2] = tp.s[2]; all[3] = s3;
        if (offscreen3(all, 4)) { d->culled_screen++; return; }
        if (too_big(all, 4)) {
            SVECTOR lv[4]; CVECTOR lc[4];
            lv[0] = v[0]; lv[1] = v[1]; lv[2] = v[3]; lv[3] = v[2];
            lc[0] = col[0]; lc[1] = col[1]; lc[2] = col[3]; lc[3] = col[2];
            draw_poly_clipped(d, lv, lc, 4, cull, semi, NULL, 0, 0);
            return;
        }
    }

    p = (PolyG4 *)render_alloc(d->rl, sizeof(PolyG4));
    if (!p) { d->dropped++; return; }
    p->rgb0 = rgb_cmd(GPU_POLY_G4 | (semi ? GPU_SEMITRANS : 0), col[0].r, col[0].g, col[0].b);
    p->xy0  = xy_pack(tp.s[0].x, tp.s[0].y);
    p->rgb1 = rgb_cmd(0, col[1].r, col[1].g, col[1].b);
    p->xy1  = xy_pack(tp.s[1].x, tp.s[1].y);
    p->rgb2 = rgb_cmd(0, col[2].r, col[2].g, col[2].b);
    p->xy2  = xy_pack(tp.s[2].x, tp.s[2].y);
    p->rgb3 = rgb_cmd(0, col[3].r, col[3].g, col[3].b);
    p->xy3  = xy_pack(s3.x, s3.y);
    render_link(d->rl, ot_index(otz), p, 8);
    d->submitted++;
}

void draw_tri_tex(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
                  const u8 *uv, u32 tpage, u32 clut, int cull, int semi)
{
    TriProj tp;
    PolyGT3 *p;

    gte_project_tri(&v[0], &v[1], &v[2], &tp);
    if (tp.sz[0] < d->near_z * 2 || tp.sz[1] < d->near_z * 2 || tp.sz[2] < d->near_z * 2) {
        /* Textured near-plane cases fall back to untextured clipping; the
         * affected triangles are at the very bottom of frame. */
        draw_poly_clipped(d, v, col, 3, cull, semi, NULL, 0, 0);
        return;
    }
    if (tp.otz > d->far_z) { d->culled_near++; return; }
    if (!cull_ok(cull, tp.nclip)) { d->culled_back++; return; }
    if (offscreen3(tp.s, 3)) { d->culled_screen++; return; }

    p = (PolyGT3 *)render_alloc(d->rl, sizeof(PolyGT3));
    if (!p) { d->dropped++; return; }
    p->rgb0 = rgb_cmd(GPU_POLY_GT3 | (semi ? GPU_SEMITRANS : 0), col[0].r, col[0].g, col[0].b);
    p->xy0  = xy_pack(tp.s[0].x, tp.s[0].y);
    p->uv0  = uv_pack(uv[0], uv[1], clut);
    p->rgb1 = rgb_cmd(0, col[1].r, col[1].g, col[1].b);
    p->xy1  = xy_pack(tp.s[1].x, tp.s[1].y);
    p->uv1  = uv_pack(uv[2], uv[3], tpage);
    p->rgb2 = rgb_cmd(0, col[2].r, col[2].g, col[2].b);
    p->xy2  = xy_pack(tp.s[2].x, tp.s[2].y);
    p->uv2  = uv_pack(uv[4], uv[5], 0);
    render_link(d->rl, ot_index(tp.otz), p, 9);
    d->submitted++;
}

void draw_quad_tex(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
                   const u8 *uv, u32 tpage, u32 clut, int cull, int semi)
{
    TriProj tp;
    DVECTOR s3;
    s32 z3, otz;
    PolyGT4 *p;

    gte_project_tri(&v[0], &v[1], &v[2], &tp);
    z3 = gte_project_point(&v[3], &s3);

    if (tp.sz[0] < d->near_z * 2 || tp.sz[1] < d->near_z * 2 ||
        tp.sz[2] < d->near_z * 2 || z3 < d->near_z * 2) {
        /* No textured path through the near-plane clipper: these are the few
         * quads at the very bottom of frame, and they fall back to flat
         * Gouraud rather than being dropped. */
        SVECTOR lv[4]; CVECTOR lc[4];
        lv[0] = v[0]; lv[1] = v[1]; lv[2] = v[3]; lv[3] = v[2];
        lc[0] = col[0]; lc[1] = col[1]; lc[2] = col[3]; lc[3] = col[2];
        draw_poly_clipped(d, lv, lc, 4, cull, semi, NULL, 0, 0);
        return;
    }
    otz = (tp.sz[0] + tp.sz[1] + tp.sz[2] + z3) >> 2;
    if (otz > d->far_z) { d->culled_near++; return; }
    if (!cull_ok(cull, tp.nclip)) { d->culled_back++; return; }
    {
        DVECTOR all[4];
        all[0] = tp.s[0]; all[1] = tp.s[1]; all[2] = tp.s[2]; all[3] = s3;
        if (offscreen3(all, 4)) { d->culled_screen++; return; }
        if (too_big(all, 4)) {
            SVECTOR lv[4]; CVECTOR lc[4]; u8 lu[8];
            lv[0] = v[0]; lv[1] = v[1]; lv[2] = v[3]; lv[3] = v[2];
            lc[0] = col[0]; lc[1] = col[1]; lc[2] = col[3]; lc[3] = col[2];
            lu[0] = uv[0]; lu[1] = uv[1]; lu[2] = uv[2]; lu[3] = uv[3];
            lu[4] = uv[6]; lu[5] = uv[7]; lu[6] = uv[4]; lu[7] = uv[5];
            draw_poly_clipped(d, lv, lc, 4, cull, semi, lu, tpage, clut);
            return;
        }
    }

    p = (PolyGT4 *)render_alloc(d->rl, sizeof(PolyGT4));
    if (!p) { d->dropped++; return; }
    p->rgb0 = rgb_cmd(GPU_POLY_GT3 | 0x08u | (semi ? GPU_SEMITRANS : 0),
                      col[0].r, col[0].g, col[0].b);
    p->xy0 = xy_pack(tp.s[0].x, tp.s[0].y);  p->uv0 = uv_pack(uv[0], uv[1], clut);
    p->rgb1 = rgb_cmd(0, col[1].r, col[1].g, col[1].b);
    p->xy1 = xy_pack(tp.s[1].x, tp.s[1].y);  p->uv1 = uv_pack(uv[2], uv[3], tpage);
    p->rgb2 = rgb_cmd(0, col[2].r, col[2].g, col[2].b);
    p->xy2 = xy_pack(tp.s[2].x, tp.s[2].y);  p->uv2 = uv_pack(uv[4], uv[5], 0);
    p->rgb3 = rgb_cmd(0, col[3].r, col[3].g, col[3].b);
    p->xy3 = xy_pack(s3.x, s3.y);            p->uv3 = uv_pack(uv[6], uv[7], 0);
    render_link(d->rl, ot_index(otz), p, 12);
    d->submitted++;
}

/* ---- screen-space helpers ---------------------------------------------- */

static inline u32 bucket_clamp(u32 b) { return b >= OT_SIZE ? OT_SIZE - 1 : (b < 1 ? 1 : b); }

void draw_set_abr(DrawCtx *d, u32 bucket, int abr)
{
    GpuCmd1 *c = (GpuCmd1 *)render_alloc(d->rl, sizeof(GpuCmd1));
    if (!c) { d->dropped++; return; }
    c->code = 0xE1000000u | (1u << 9) | (1u << 10) | ((u32)(abr & 3) << 5);
    render_link(d->rl, bucket_clamp(bucket), c, 1);
}

void draw_gouraud_quad_screen(DrawCtx *d, const DVECTOR *p, const CVECTOR *col,
                              u32 bucket, int semi, int abr)
{
    PolyG4 *q = (PolyG4 *)render_alloc(d->rl, sizeof(PolyG4));
    (void)abr;
    if (!q) { d->dropped++; return; }
    q->rgb0 = rgb_cmd(GPU_POLY_G4 | (semi ? GPU_SEMITRANS : 0), col[0].r, col[0].g, col[0].b);
    q->xy0  = xy_pack(p[0].x, p[0].y);
    q->rgb1 = rgb_cmd(0, col[1].r, col[1].g, col[1].b);
    q->xy1  = xy_pack(p[1].x, p[1].y);
    q->rgb2 = rgb_cmd(0, col[2].r, col[2].g, col[2].b);
    q->xy2  = xy_pack(p[2].x, p[2].y);
    q->rgb3 = rgb_cmd(0, col[3].r, col[3].g, col[3].b);
    q->xy3  = xy_pack(p[3].x, p[3].y);
    render_link(d->rl, bucket_clamp(bucket), q, 8);
    d->submitted++;
}

void draw_flat_tri_screen(DrawCtx *d, const DVECTOR *p, const CVECTOR *col,
                          u32 bucket, int semi, int abr)
{
    PolyF3 *t = (PolyF3 *)render_alloc(d->rl, sizeof(PolyF3));
    (void)abr;
    if (!t) { d->dropped++; return; }
    t->code = rgb_cmd(GPU_POLY_F3 | (semi ? GPU_SEMITRANS : 0), col[0].r, col[0].g, col[0].b);
    t->xy0  = xy_pack(p[0].x, p[0].y);
    t->xy1  = xy_pack(p[1].x, p[1].y);
    t->xy2  = xy_pack(p[2].x, p[2].y);
    render_link(d->rl, bucket_clamp(bucket), t, 4);
    d->submitted++;
}

void draw_tile_screen(DrawCtx *d, s32 x, s32 y, s32 w, s32 h,
                      const CVECTOR *col, u32 bucket, int semi, int abr)
{
    Tile *t;
    (void)abr;
    if (x >= SCREEN_W || y >= SCREEN_H || x + w <= 0 || y + h <= 0) return;
    t = (Tile *)render_alloc(d->rl, sizeof(Tile));
    if (!t) { d->dropped++; return; }
    t->code = rgb_cmd(GPU_TILE | (semi ? GPU_SEMITRANS : 0), col->r, col->g, col->b);
    t->xy   = xy_pack(x, y);
    t->wh   = ((u32)(u16)h << 16) | (u32)(u16)w;
    render_link(d->rl, bucket_clamp(bucket), t, 3);
    d->submitted++;
}
