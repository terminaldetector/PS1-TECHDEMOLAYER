/* draw.h - submission helpers shared by every scene module.
 *
 * Responsibilities: GTE projection, backface culling via NCLIP, OT bucket
 * selection from AVSZ, screen rejection, and near-plane clipping.
 *
 * The near-plane case matters more than it looks. The GTE's divider saturates
 * as soon as H >= 2*SZ, so with H = 280 anything closer than 140 units
 * projects to garbage coordinates and tears across the screen. Triangles that
 * come near that boundary are therefore re-done on the CPU: transformed to
 * view space, clipped against z = NEAR_Z, and projected by hand. Everything
 * else goes through the GTE untouched.
 */
#ifndef DI_DRAW_H
#define DI_DRAW_H

#include "camera.h"
#include "lighting.h"
#include "render.h"

#define DRAW_CULL_NONE  0
#define DRAW_CULL_BACK  1   /* author faces clockwise seen from the front */
#define DRAW_CULL_FRONT 2

typedef struct {
    RenderList     *rl;
    const Camera   *cam;
    const Lighting *lt;
    s32 near_z;         /* hard near plane in world units */
    s32 far_z;
    /* per-frame counters, reported honestly in PERFORMANCE.md */
    u32 submitted, culled_back, culled_near, culled_screen, clipped, dropped;
} DrawCtx;

void draw_begin(DrawCtx *d, RenderList *rl, const Camera *c, const Lighting *l);

void draw_tri(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
              int cull, int semi, int abr);
/* Vertices in strip order: v0 v1 / v2 v3. */
void draw_quad(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
               int cull, int semi, int abr);
/* Gouraud + textured triangle. uv are 0..255 texel coordinates. */
void draw_tri_tex(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
                  const u8 *uv, u32 tpage, u32 clut, int cull, int semi);

/* Gouraud + textured quad, strip order. uv holds 8 bytes: u,v per corner. */
void draw_quad_tex(DrawCtx *d, const SVECTOR *v, const CVECTOR *col,
                   const u8 *uv, u32 tpage, u32 clut, int cull, int semi);

/* Screen-space helpers used by the sky, the sun disc and the fireflies.
 * These take an OT bucket directly: their depth is a composition decision,
 * not something the GTE computed. */
void draw_flat_tri_screen(DrawCtx *d, const DVECTOR *p, const CVECTOR *col,
                          u32 bucket, int semi, int abr);
void draw_gouraud_quad_screen(DrawCtx *d, const DVECTOR *p, const CVECTOR *col,
                              u32 bucket, int semi, int abr);
void draw_tile_screen(DrawCtx *d, s32 x, s32 y, s32 w, s32 h,
                      const CVECTOR *col, u32 bucket, int semi, int abr);
/* Set the semi-transparency mode for the primitives that follow in this
 * bucket; emitted as a real GP0(E1) packet. */
void draw_set_abr(DrawCtx *d, u32 bucket, int abr);

#endif
