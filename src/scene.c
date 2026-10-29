#include "scene.h"
#include "world.h"
#include "iris.h"
#include "grass.h"
#include "particles.h"
#include "materials.h"
#include "platform.h"
#include "gte.h"
#include "profiler.h"

Director g_dir;
DrawCtx  g_draw;
u32      g_debug;
s32      g_orbit;
int      g_quality = Q_BALANCED;

/* ---- camera path --------------------------------------------------------
 * The rig is a position plus a look-at target, both keyframed. Aiming at a
 * target instead of at fixed angles is what keeps the plant framed while it
 * triples in height: the camera rises with the growth because the target
 * rises. There is one continuous move for the whole 120 s and no cut.
 */
typedef struct { s32 t, x, y, z, tx, ty, tz, h; } CamKey;

static const CamKey kCam[] = {
    /*  t ms      camera position        look-at target        h   */
    {      0,  55,  430,  -430,   0,  300,     0, 270 },  /* the seed      */
    {   9000,  62,  455,  -470,   0,  340,     0, 270 },  /* first shoot   */
    {  20000,  75,  530,  -600,   0,  470,     0, 272 },  /* leaves open   */
    {  34000,  92,  700,  -800,   0,  720,     0, 274 },  /* the fan       */
    {  46000, 105,  980,  -980,   0, 1050,     0, 276 },  /* the bud       */
    {  58000, 118, 1210, -1010,   0, 1240,     0, 278 },  /* the bloom     */
    {  70000, 150, 1330, -1350,   0, 1210,   300, 280 },  /* rise begins   */
    {  82000, 200, 1420, -1650,   0, 1150,   900, 280 },  /* lake enters   */
    {  96000, 240, 1470, -1850,   0, 1120,  1300, 280 },  /* wide, sunset  */
    { 120000, 262, 1500, -1980,   0, 1100,  1500, 280 },  /* moonlit hold  */
};

static void camera_eval(Camera *c, s32 t)
{
    int i = 0, n = DI_COUNT(kCam);
    const CamKey *a, *b;
    s32 u, tx, ty, tz, dx, dy, dz, flat;

    if (t < 0) t = 0;
    while (i < n - 2 && t >= kCam[i + 1].t) i++;
    a = &kCam[i]; b = &kCam[i + 1];
    u = (b->t > a->t) ? DI_CLAMP(fdiv(t - a->t, b->t - a->t), 0, ONE) : ONE;
    u = fsmooth(u);

    c->px = flerp(a->x, b->x, u);
    c->py = flerp(a->y, b->y, u);
    c->pz = flerp(a->z, b->z, u);
    c->h  = flerp(a->h, b->h, u);
    c->roll = 0;
    tx = flerp(a->tx, b->tx, u);
    ty = flerp(a->ty, b->ty, u);
    tz = flerp(a->tz, b->tz, u);

    /* A very slow breath so the frame is never mechanically still. */
    c->py += fmul(7, fsin(t >> 6));
    c->px += fmul(5, fsin((t >> 7) + 900));

    /* T15: orbit the whole rig around the plant to prove the flower is a
     * solid and not a billboard. The target is unchanged, so the plant stays
     * centred while the parallax does the talking. */
    if (g_orbit) {
        s32 ang = (g_orbit * ANG_360) / 360;
        s32 sx = c->px - tx, sz = c->pz - tz;
        s32 sn = fsin(ang), cs = fcos(ang);
        c->px = tx + fmul(sx, cs) + fmul(sz, sn);
        c->pz = tz - fmul(sx, sn) + fmul(sz, cs);
    }

    dx = tx - c->px; dy = ty - c->py; dz = tz - c->pz;
    flat = di_len3(dx, 0, dz);
    c->yaw   = di_atan2(dx, dz);
    c->pitch = di_atan2(dy, flat);
}

void scene_init(u32 seed)
{
    di_seed_world(seed);
    gte_init();
    world_init();
    materials_init();
    iris_init();
    grass_init();
    particles_init();
}

void scene_set_debug(u32 flags, s32 orbit_deg) { g_debug = flags; g_orbit = orbit_deg; }
void scene_set_quality(int q) { g_quality = q; }

static void director_eval(Director *d, s32 t_ms)
{
    d->t_ms = t_ms;
    d->phase = DI_CLAMP((t_ms >> 4) * ONE / (SCENE_LENGTH_MS >> 4), 0, ONE);

    /* Wind: a slow swell with a faster gust riding on it. Never zero, so the
     * world is never completely static. */
    d->wind = 1500
            + fmul(900, fsin(t_ms >> 7))
            + fmul(520, fsin((t_ms >> 5) + 1300))
            + fmul(380, di_noise2(DI_RS_MOTION + 3, t_ms >> 2, 0, 9));

    /* Fireflies fade in through dusk and are gone before the sky lightens. */
    d->firefly = fband(t_ms, 88000, 104000);

    camera_eval(&d->cam, t_ms);
    cam_build(&d->cam);

    if (g_debug & DBG_FREEZE_SUN)
        light_eval(&d->lt, 0);
    else
        light_eval(&d->lt, d->phase);
}

void scene_frame(s32 t_ms)
{
    RenderList *rl = plat_list();
    static s32 s_night_applied = -1;

    director_eval(&g_dir, t_ms);

    /* Selective VRAM update: the day/night palette is 16 CLUT entries per
     * texture, rewritten only when the night factor has actually moved.
     * The synthesised patterns themselves are generated once at boot. */
    {
        s32 step = g_dir.lt.night >> 7;
        if (step != s_night_applied) {
            s_night_applied = step;
            materials_set_night(g_dir.lt.night);
        }
    }
    draw_begin(&g_draw, rl, &g_dir.cam, &g_dir.lt);

    prof_begin(PROF_SKY);
    if (!(g_debug & DBG_NO_SKY))    world_draw_sky(&g_draw, t_ms);
    particles_draw_disc(&g_draw);
    prof_end(PROF_SKY);

    prof_begin(PROF_FUJI);
    if (!(g_debug & DBG_NO_FUJI))   world_draw_fuji(&g_draw);
    prof_end(PROF_FUJI);

    prof_begin(PROF_FAR);
    if (!(g_debug & DBG_NO_FAR))    world_draw_far(&g_draw);
    prof_end(PROF_FAR);

    prof_begin(PROF_WATER);
    if (!(g_debug & DBG_NO_WATER))  world_draw_water(&g_draw, t_ms);
    prof_end(PROF_WATER);

    prof_begin(PROF_GROUND);
    if (!(g_debug & DBG_NO_GROUND)) world_draw_ground(&g_draw);
    prof_end(PROF_GROUND);

    prof_begin(PROF_GRASS);
    if (!(g_debug & DBG_NO_GRASS))  grass_draw(&g_draw, t_ms);
    prof_end(PROF_GRASS);

    prof_begin(PROF_IRIS);
    if (!(g_debug & DBG_NO_IRIS))   iris_draw(&g_draw, t_ms);
    prof_end(PROF_IRIS);

    prof_begin(PROF_PARTICLES);
    if (!(g_debug & DBG_NO_FIREFLY)) particles_draw_fireflies(&g_draw, t_ms);
    prof_end(PROF_PARTICLES);

    if (g_debug & DBG_HUD) prof_draw(&g_draw);
}
