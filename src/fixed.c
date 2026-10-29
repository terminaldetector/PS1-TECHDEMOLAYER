#include "fixed.h"

/* ---- integer sqrt ----------------------------------------------------- */

u32 di_isqrt(u32 v)
{
    u32 rem = 0, root = 0;
    int i;
    for (i = 0; i < 16; i++) {
        root <<= 1;
        rem = (rem << 2) | (v >> 30);
        v <<= 2;
        if (root < rem) {
            rem -= root | 1;
            root += 2;
        }
    }
    return root >> 1;
}

/* atan(z) for |z| <= ONE in angle units.
 *
 *   atan(z) ~ (pi/4)z - z(|z|-1)(0.2447 + 0.0663|z|)      [radians]
 *
 * Scaled to 4096 units per turn: pi/4 rad = 512 units, and the correction
 * term is scaled by 4096/(2*pi) = 652.
 */
static s32 atan_unit(s32 z)
{
    s32 az = DI_ABS(z);
    s32 p  = fmul(z, az - ONE);
    s32 q  = 1002 + fmul(272, az);          /* 0.2447 and 0.0663 in Q12 */
    s32 r  = fmul(p, q);
    return ((512 * z) >> QBITS) - ((652 * r) >> QBITS);
}

s32 di_atan2(s32 y, s32 x)
{
    s32 a;
    if (x == 0 && y == 0) return 0;
    if (DI_ABS(x) >= DI_ABS(y)) {
        a = atan_unit(fdiv(y, x));
        if (x < 0) a += (y >= 0) ? ANG_180 : -ANG_180;
    } else {
        a = ANG_90 - atan_unit(fdiv(x, y));
        if (y < 0) a -= ANG_180;
    }
    return a & (ANG_360 - 1);
}

s32 di_len3(s32 x, s32 y, s32 z)
{
    /* Squares of world coordinates can exceed 32 bits, so scale down first
     * when the inputs are large. The mountain is ~20000 units away. */
    s32 m = DI_MAX(DI_MAX(DI_ABS(x), DI_ABS(y)), DI_ABS(z));
    int sh = 0;
    while (m >= 23000) { m >>= 1; sh++; }
    x >>= sh; y >>= sh; z >>= sh;
    return (s32)di_isqrt((u32)(x * x + y * y + z * z)) << sh;
}

void di_norm3(s32 *x, s32 *y, s32 *z)
{
    s32 l = di_len3(*x, *y, *z);
    if (l <= 0) { *x = 0; *y = ONE; *z = 0; return; }
    *x = fdiv(*x, l);
    *y = fdiv(*y, l);
    *z = fdiv(*z, l);
}

/* ---- deterministic streams -------------------------------------------- */

static u32 g_master = 0x1715u;
static u32 g_state[DI_RS_COUNT];
static u32 g_start[DI_RS_COUNT];

/* Integer avalanche (Murmur3 finaliser). Cheap on R3000A and good enough for
 * placement work; it is never used for anything cryptographic. */
static inline u32 mix32(u32 h)
{
    h ^= h >> 16; h *= 0x85ebca6bu;
    h ^= h >> 13; h *= 0xc2b2ae35u;
    h ^= h >> 16;
    return h;
}

void di_seed_world(u32 master_seed)
{
    int i;
    g_master = master_seed;
    for (i = 0; i < DI_RS_COUNT; i++) {
        g_start[i] = mix32(master_seed + 0x9e3779b9u * (u32)(i + 1)) | 1u;
        g_state[i] = g_start[i];
    }
}

u32 di_world_seed(void) { return g_master; }

void di_rand_reset(int stream) { g_state[stream] = g_start[stream]; }

u32 di_rand(int stream)
{
    u32 x = g_state[stream];
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    g_state[stream] = x;
    return x;
}

u32 di_hash2(int stream, s32 a, s32 b)
{
    return mix32(g_start[stream] ^ ((u32)a * 0x27d4eb2du) ^ ((u32)b * 0x165667b1u));
}

u32 di_hash3(int stream, s32 a, s32 b, s32 c)
{
    return mix32(di_hash2(stream, a, b) ^ ((u32)c * 0x9e3779b9u));
}

s32 di_hashn(int stream, s32 a, s32 b)
{
    /* Keep the top 13 bits: [0,8191] - 4096 -> [-ONE, ONE-1]. */
    return (s32)(di_hash2(stream, a, b) >> 19) - ONE;
}

/* ---- value noise ------------------------------------------------------- */

s32 di_noise2(int stream, s32 x, s32 y, int shift)
{
    s32 cx = x >> shift, cy = y >> shift;
    s32 mask = (1 << shift) - 1;
    /* Fractional position inside the cell, promoted to Q12. */
    s32 fx = ((x & mask) << QBITS) >> shift;
    s32 fy = ((y & mask) << QBITS) >> shift;
    s32 sx = fsmooth(fx), sy = fsmooth(fy);
    s32 n00 = di_hashn(stream, cx,     cy);
    s32 n10 = di_hashn(stream, cx + 1, cy);
    s32 n01 = di_hashn(stream, cx,     cy + 1);
    s32 n11 = di_hashn(stream, cx + 1, cy + 1);
    return flerp(flerp(n00, n10, sx), flerp(n01, n11, sx), sy);
}

s32 di_fbm2(int stream, s32 x, s32 y, int shift, int oct)
{
    s32 sum = 0, amp = ONE, norm = 0;
    int i;
    for (i = 0; i < oct && shift - i > 0; i++) {
        sum  += fmul(di_noise2(stream + i * 7, x, y, shift - i), amp);
        norm += amp;
        amp >>= 1;
    }
    return norm ? fdiv(sum, norm) : 0;
}
