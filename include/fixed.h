/* fixed.h - Q12 fixed point, trig, integer sqrt, hash noise and the
 * deterministic per-subsystem PRNG streams required by the seed contract.
 */
#ifndef DI_FIXED_H
#define DI_FIXED_H

#include "di_types.h"

/* One full turn is 4096 angle units, so angles wrap with a plain mask. */
#define ANG_360 4096
#define ANG_180 2048
#define ANG_90  1024

extern const s16 di_sin_tab[ANG_360];

/* Q12 multiply. On MIPS I this becomes mult/mfhi/mflo with no libgcc call. */
static inline s32 fmul(s32 a, s32 b) { return (s32)(((s64)a * (s64)b) >> QBITS); }
/* Q12 divide, (a << 12) / b.
 *
 * Deliberately 32 bit. The R3000A divides 32 by 32 in hardware; a 64-bit
 * division would pull __divdi3 out of libgcc, and this ROM links no library
 * at all. Where `a` is too large to shift by 12 without overflowing, the
 * shift is split so precision degrades in the low bits instead of the result
 * wrapping. Every caller in this program stays in the first branch. */
static inline s32 fdiv(s32 a, s32 b)
{
    if (b == 0) return 0;
    if (a > -524288 && a < 524288) return (a << QBITS) / b;
    if (a > -33554432 && a < 33554432) return ((a << 6) / b) << 6;
    return (a / b) << QBITS;
}

static inline s32 fsin(s32 a) { return di_sin_tab[(u32)a & (ANG_360 - 1)]; }
static inline s32 fcos(s32 a) { return di_sin_tab[((u32)a + ANG_90) & (ANG_360 - 1)]; }

/* Linear interpolation, t in Q12 over [0,ONE]. */
static inline s32 flerp(s32 a, s32 b, s32 t) { return a + fmul(b - a, t); }

/* Hermite smoothstep on Q12 t. */
static inline s32 fsmooth(s32 t)
{
    if (t <= 0) return 0;
    if (t >= ONE) return ONE;
    return fmul(fmul(t, t), 3 * ONE - 2 * t);
}

/* Map v from [lo,hi] onto [0,ONE] and smoothstep it. Used all over the
 * timeline and the morphogenesis envelopes. */
static inline s32 fband(s32 v, s32 lo, s32 hi)
{
    if (hi == lo) return v >= hi ? ONE : 0;
    {
        s32 t = fdiv(v - lo, hi - lo);
        return fsmooth(DI_CLAMP(t, 0, ONE));
    }
}

u32 di_isqrt(u32 v);
/* Four-quadrant arc tangent in angle units (4096 to the turn). */
s32 di_atan2(s32 y, s32 x);
/* Length of a 3D vector given in raw world units (not Q12). */
s32 di_len3(s32 x, s32 y, s32 z);
/* Normalise to Q12 unit length in place. */
void di_norm3(s32 *x, s32 *y, s32 *z);

/* ---- deterministic streams ------------------------------------------- */
/* Every subsystem draws from its own stream so that changing the material
 * seed cannot move a single mountain vertex (handoff section 3). */
enum {
    DI_RS_TERRAIN = 0,
    DI_RS_IRIS,
    DI_RS_GRASS,
    DI_RS_FIREFLY,
    DI_RS_MATERIAL,
    DI_RS_MOTION,
    DI_RS_COUNT
};

void di_seed_world(u32 master_seed);
u32  di_world_seed(void);
/* Pull the next value of a named stream. */
u32  di_rand(int stream);
/* Rewind a stream to its seeded start; makes regeneration reproducible. */
void di_rand_reset(int stream);
/* Stateless hashes - preferred wherever an element must be regenerated
 * independently of evaluation order. */
u32  di_hash2(int stream, s32 a, s32 b);
u32  di_hash3(int stream, s32 a, s32 b, s32 c);
/* Signed Q12 noise in [-ONE, ONE] from a stateless hash. */
s32  di_hashn(int stream, s32 a, s32 b);

/* Smooth 2D value noise, Q12 output in [-ONE,ONE]; `shift` is the log2 cell
 * size in world units. */
s32  di_noise2(int stream, s32 x, s32 y, int shift);
/* Fractal sum of di_noise2, `oct` octaves with halving amplitude. */
s32  di_fbm2(int stream, s32 x, s32 y, int shift, int oct);

#endif /* DI_FIXED_H */
