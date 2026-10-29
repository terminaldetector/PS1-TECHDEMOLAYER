#include "lighting.h"

typedef struct {
    s32 at;             /* phase position, 0..ONE */
    s32 az, el;         /* light azimuth / elevation in angle units */
    s32 lcol[3], amb[3];
    s32 top[3], mid[3], hor[3];
    s32 fog[3]; s32 fog_start, fog_end;
    s32 water[3], spec[3];
    s32 night;
    s32 disc[3], disc_size, disc_az, disc_el;
} LightKey;

/* Phase keys. 0 = t0, ONE = t120s. The whole piece lives in a cool twilight
 * register, warms hard at the sunset beat, then falls to moonlight. */
static const LightKey kKeys[] = {
    /* blue hour, light from the upper left */
    { 0,
      -440, 360,
      { 3700, 3400, 3100 }, { 1250, 1340, 1850 },
      {  78,  80, 140 }, { 120, 116, 168 }, { 196, 180, 206 },
      { 150, 145, 185 }, 9000, 52000,
      {  54,  62, 104 }, { 215, 210, 235 },
      0,
      { 240, 236, 250 }, 0, -440, 360 },

    /* late afternoon: the light drops and warms slightly */
    { 1900,
      -560, 250,
      { 4000, 3450, 2850 }, { 1180, 1250, 1700 },
      {  70,  72, 134 }, { 124, 112, 166 }, { 212, 182, 198 },
      { 158, 146, 180 }, 9000, 52000,
      {  50,  58, 100 }, { 230, 215, 230 },
      0,
      { 250, 238, 240 }, 0, -560, 250 },

    /* sunset peak: orange rim light, magenta sky, warm path on the water */
    { 2850,
      -680, 70,
      { 5200, 2750, 1150 }, { 1150, 880, 1150 },
      {  74,  40, 108 }, { 186,  78, 118 }, { 252, 158,  78 },
      { 214, 130, 120 }, 7000, 50000,
      {  86,  52,  92 }, { 255, 186, 110 },
      0,
      { 255, 196, 120 }, 13, -680, 70 },

    /* dusk: the warmth drains out, violet takes over */
    { 3400,
      -300, -40,
      { 1800, 1500, 1750 }, { 780, 760, 1150 },
      {  34,  30,  74 }, {  74,  58, 112 }, { 136, 100, 142 },
      { 108,  92, 136 }, 7000, 48000,
      {  44,  40,  82 }, { 150, 130, 160 },
      1500,
      { 220, 190, 190 }, 7, 150, 120 },

    /* night: the moon takes over from the upper right */
    { 3800,
      210, 160,
      { 1150, 1320, 1800 }, { 330, 400, 760 },
      {   9,  11,  32 }, {  17,  23,  58 }, {  42,  54,  98 },
      {  40,  52,  96 }, 6000, 46000,
      {  16,  22,  52 }, { 186, 206, 255 },
      ONE,
      { 232, 240, 255 }, 9, 210, 160 },

    { ONE,
      224, 168,
      { 1120, 1290, 1780 }, { 320, 390, 750 },
      {   8,  10,  30 }, {  16,  22,  56 }, {  40,  52,  95 },
      {  38,  50,  94 }, 6000, 46000,
      {  15,  21,  50 }, { 190, 210, 255 },
      ONE,
      { 234, 242, 255 }, 9, 224, 168 },
};

static void lerp3(const s32 a[3], const s32 b[3], s32 t, s32 out[3])
{
    out[0] = flerp(a[0], b[0], t);
    out[1] = flerp(a[1], b[1], t);
    out[2] = flerp(a[2], b[2], t);
}

void light_eval(Lighting *L, s32 phase)
{
    int i = 0, n = DI_COUNT(kKeys);
    const LightKey *a, *b;
    s32 t, az, el, ce;

    while (i < n - 2 && phase >= kKeys[i + 1].at) i++;
    a = &kKeys[i];
    b = &kKeys[i + 1];
    t = (b->at > a->at) ? DI_CLAMP(fdiv(phase - a->at, b->at - a->at), 0, ONE) : ONE;
    t = fsmooth(t);

    az = flerp(a->az, b->az, t);
    el = flerp(a->el, b->el, t);
    ce = fcos(el);
    L->ldir[0] = fmul(fsin(az), ce);
    L->ldir[1] = fsin(el);
    L->ldir[2] = fmul(fcos(az), ce);

    lerp3(a->lcol, b->lcol, t, L->lcol);
    lerp3(a->amb,  b->amb,  t, L->amb);
    lerp3(a->top,  b->top,  t, L->sky_top);
    lerp3(a->mid,  b->mid,  t, L->sky_mid);
    lerp3(a->hor,  b->hor,  t, L->sky_hor);
    lerp3(a->fog,  b->fog,  t, L->fog);
    lerp3(a->water, b->water, t, L->water);
    lerp3(a->spec, b->spec, t, L->spec);
    lerp3(a->disc, b->disc, t, L->disc);
    L->fog_start = flerp(a->fog_start, b->fog_start, t);
    L->fog_end   = flerp(a->fog_end,   b->fog_end,   t);
    L->night     = flerp(a->night,     b->night,     t);
    L->disc_size = flerp(a->disc_size, b->disc_size, t);
    L->disc_az   = flerp(a->disc_az,   b->disc_az,   t);
    L->disc_el   = flerp(a->disc_el,   b->disc_el,   t);
}

void light_shade(const Lighting *L, const s32 n[3], const s32 base[3],
                 s32 wrap, s32 out[3])
{
    s32 d = (fmul(n[0], L->ldir[0]) + fmul(n[1], L->ldir[1]) + fmul(n[2], L->ldir[2]));
    int i;
    /* Wrapped lambert: foliage and petals keep some shape on the dark side
     * instead of going flat black, which matters a lot at night. */
    if (wrap > 0) d = fmul(d + fmul(wrap, ONE), fdiv(ONE, ONE + wrap));
    if (d < 0) d = 0;
    for (i = 0; i < 3; i++) {
        s32 v = fmul(base[i], L->amb[i] + fmul(L->lcol[i], d));
        out[i] = DI_CLAMP(v, 0, 255);
    }
}

void light_fog(const Lighting *L, s32 depth, s32 c[3])
{
    s32 f;
    if (depth <= L->fog_start) return;
    f = fdiv(depth - L->fog_start, L->fog_end - L->fog_start);
    if (f > ONE) f = ONE;
    f = fmul(f, f);                     /* keep the near field clear */
    c[0] = flerp(c[0], L->fog[0], f);
    c[1] = flerp(c[1], L->fog[1], f);
    c[2] = flerp(c[2], L->fog[2], f);
}
