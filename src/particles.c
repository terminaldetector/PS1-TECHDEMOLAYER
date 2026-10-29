/* particles.c - the sun/moon disc and the fireflies.
 *
 * The disc is placed from the light's own azimuth and elevation, so it is
 * never out of step with the direction the landscape is lit from: move the
 * light and the disc moves with it. It is projected through the same view
 * matrix as everything else and parked in an OT bucket behind the mountain,
 * which is why Fuji occludes it exactly as it should.
 *
 * Fireflies are real 3D points with deterministic trajectories, projected and
 * depth-sorted like any other geometry. Each carries a small additive flare
 * and a dim pool of light on the ground beneath it: that pool is the cheap
 * stand-in for local illumination, and it costs one extra transparent quad
 * rather than any per-pixel work.
 */
#include "particles.h"
#include "world.h"
#include "scene.h"
#include "gte.h"

#define FLY_MAX 30

typedef struct {
    s16 x, z;        /* home position on the bank */
    s16 r;           /* drift radius              */
    s16 h;           /* hover height above ground */
    s16 sp;          /* drift speed               */
    s16 ph;          /* phase                     */
    s16 blink;       /* blink period              */
} Firefly;

static Firefly g_fly[FLY_MAX];
static int g_nfly;

void particles_init(void)
{
    int i;
    g_nfly = 0;
    for (i = 0; i < FLY_MAX; i++) {
        u32 h = di_hash2(DI_RS_FIREFLY, i, 0);
        s32 x = -2200 + (s32)(h % 4400);
        s32 z = -500 + (s32)((h >> 12) % 2700);
        Firefly *f;
        if (ground_h(x, z) < 20) continue;     /* not over the water */
        f = &g_fly[g_nfly++];
        f->x = (s16)x;
        f->z = (s16)z;
        f->r = (s16)(70 + (s32)((h >> 5) & 255));
        f->h = (s16)(40 + (s32)((h >> 9) & 127));
        f->sp = (s16)(3 + (s32)((h >> 17) & 7));
        f->ph = (s16)((h >> 20) & (ANG_360 - 1));
        f->blink = (s16)(900 + (s32)((h >> 3) & 1023));
    }
}

/* ---- sun / moon --------------------------------------------------------- */

void particles_draw_disc(DrawCtx *d)
{
    const Lighting *L = d->lt;
    s32 ce, wx, wy, wz, v[3];
    s32 cx, cy, rad;
    int i;

    if (L->disc_size <= 0) return;

    ce = fcos(L->disc_el);
    wx = fmul(fsin(L->disc_az), ce);
    wy = fsin(L->disc_el);
    wz = fmul(fcos(L->disc_az), ce);
    cam_dir_to_view(d->cam, wx, wy, wz, v);
    if (v[2] <= 128) return;                   /* behind the camera */

    cx = SCREEN_W_HALF + (v[0] * d->cam->h) / v[2];
    cy = SCREEN_H_HALF + (v[1] * d->cam->h) / v[2];
    rad = L->disc_size;
    if (cx < -80 || cx > SCREEN_W + 80 || cy < -80 || cy > SCREEN_H + 80) return;

    /* Halo first (it must end up underneath), then the disc. Both go in a
     * bucket behind the mountain so Fuji cuts across them. */
    for (i = 0; i < 14; i++) {
        s32 a0 = (i * ANG_360) / 14, a1 = ((i + 1) * ANG_360) / 14;
        DVECTOR p[3];
        CVECTOR c[3];
        s32 hr = rad * 3;
        p[0].x = (s16)cx;                      p[0].y = (s16)cy;
        p[1].x = (s16)(cx + fmul(hr, fsin(a0))); p[1].y = (s16)(cy - fmul(hr, fcos(a0)));
        p[2].x = (s16)(cx + fmul(hr, fsin(a1))); p[2].y = (s16)(cy - fmul(hr, fcos(a1)));
        c[0].r = (u8)(L->disc[0] >> 4); c[0].g = (u8)(L->disc[1] >> 4); c[0].b = (u8)(L->disc[2] >> 4);
        draw_flat_tri_screen(d, p, c, 8000, 1, ABR_ADD);
    }
    draw_set_abr(d, 8000, ABR_ADD);

    for (i = 0; i < 12; i++) {
        s32 a0 = (i * ANG_360) / 12, a1 = ((i + 1) * ANG_360) / 12;
        DVECTOR p[3];
        CVECTOR c[3];
        p[0].x = (s16)cx;                        p[0].y = (s16)cy;
        p[1].x = (s16)(cx + fmul(rad, fsin(a0))); p[1].y = (s16)(cy - fmul(rad, fcos(a0)));
        p[2].x = (s16)(cx + fmul(rad, fsin(a1))); p[2].y = (s16)(cy - fmul(rad, fcos(a1)));
        c[0].r = (u8)L->disc[0]; c[0].g = (u8)L->disc[1]; c[0].b = (u8)L->disc[2];
        draw_flat_tri_screen(d, p, c, 7990, 0, 0);
    }
}

/* ---- fireflies ---------------------------------------------------------- */

void particles_draw_fireflies(DrawCtx *d, s32 t_ms)
{
    s32 amount = g_dir.firefly;
    int i;
    int budget = (g_quality == Q_LOW) ? 12 : (g_quality == Q_SHOWCASE ? FLY_MAX : 22);

    if (amount <= 0) return;

    for (i = 0; i < g_nfly && i < budget; i++) {
        const Firefly *f = &g_fly[i];
        s32 ang = f->ph + t_ms * f->sp / 32;
        s32 bob = fsin(f->ph * 3 + (t_ms >> 4));
        s32 wx = f->x + fmul(f->r, fsin(ang));
        s32 wz = f->z + fmul(f->r, fcos(ang + 700));
        s32 gy = ground_h(wx, wz);
        s32 wy = gy + f->h + fmul(26, bob);
        SVECTOR lv;
        DVECTOR sp;
        s32 sz, pulse, size;
        CVECTOR col;

        /* Slow asymmetric blink: bright flash, long dim tail. */
        pulse = fsin((t_ms * 1000) / (f->blink + 1) + f->ph);
        pulse = (pulse + ONE) >> 1;
        pulse = fmul(pulse, pulse);
        pulse = fmul(pulse, pulse);
        pulse = fmul(pulse, amount);
        if (pulse < 700) continue;

        cam_bind_object(d->cam, wx, wy, wz);
        lv.vx = 0; lv.vy = 0; lv.vz = 0; lv.pad = 0;
        sz = gte_project_point(&lv, &sp);
        if (sz < d->near_z * 2 || sz > 9000) continue;
        if (sp.x < -16 || sp.x > SCREEN_W + 16 || sp.y < -16 || sp.y > SCREEN_H + 16)
            continue;

        /* A firefly is a point of light, not a lamp: one or two pixels. */
        size = (sz < 1400) ? 2 : 1;

        col.r = (u8)fmul(210, pulse);
        col.g = (u8)fmul(196, pulse);
        col.b = (u8)fmul(78,  pulse);

        /* The pool of light it throws on the grass below. This is the whole
         * of the "local light": one dim additive rectangle, only for flies
         * close enough for it to mean anything. No per-pixel work, and the
         * budget is one extra primitive per visible fly. */
        if (sz < 2600) {
            SVECTOR gv;
            DVECTOR gp;
            s32 gsz;
            cam_bind_object(d->cam, wx, gy + 4, wz);
            gv.vx = 0; gv.vy = 0; gv.vz = 0; gv.pad = 0;
            gsz = gte_project_point(&gv, &gp);
            if (gsz > d->near_z * 2) {
                s32 gw = size * 3 + 2, gh = size + 1;
                CVECTOR gc;
                gc.r = (u8)fmul(30, pulse);
                gc.g = (u8)fmul(26, pulse);
                gc.b = (u8)fmul(10, pulse);
                draw_tile_screen(d, gp.x - gw, gp.y - gh, gw * 2, gh * 2,
                                 &gc, ot_index(gsz) + 2, 1, ABR_ADD);
                draw_set_abr(d, ot_index(gsz) + 2, ABR_ADD);
            }
        }

        /* The flare itself, as a rectangle so the additive blend has no
         * diagonal seam down the middle. */
        draw_tile_screen(d, sp.x - size, sp.y - size, size * 2, size * 2,
                         &col, ot_index(sz), 1, ABR_ADD);
        draw_set_abr(d, ot_index(sz), ABR_ADD);
    }
}
