/* di_types.h - base types shared by the PS1 target and the host preview build.
 *
 * DIGITAL IRIS. Everything is fixed point; the R3000A has no FPU and the host
 * build must reproduce the exact same arithmetic, so no float appears anywhere
 * below the tools/ directory.
 */
#ifndef DI_TYPES_H
#define DI_TYPES_H

typedef signed char        s8;
typedef unsigned char      u8;
typedef signed short       s16;
typedef unsigned short     u16;
typedef signed int         s32;
typedef unsigned int       u32;
typedef signed long long   s64;
typedef unsigned long long u64;

/* Q12: 1.0 == 4096. Used for every scalar that is not a raw world coordinate. */
#define ONE   4096
#define QBITS 12

/* World coordinates are plain integers in "scene units" (~1 unit = 1 mm at the
 * flower, see DESIGN_NOTES.md). They must stay inside s16 because that is what
 * the GTE vertex registers hold. */
typedef struct { s16 vx, vy, vz, pad; } SVECTOR;
typedef struct { s32 vx, vy, vz, pad; } VECTOR;
typedef struct { s16 x, y; }            DVECTOR;

/* Row-major 3x3 rotation in Q12 plus a 32-bit translation, matching the layout
 * the GTE control registers expect. */
typedef struct { s16 m[3][3]; s32 t[3]; } MATRIX;

typedef struct { u8 r, g, b, pad; } CVECTOR;

#ifndef NULL
#define NULL ((void *)0)
#endif

#define DI_MIN(a, b) ((a) < (b) ? (a) : (b))
#define DI_MAX(a, b) ((a) > (b) ? (a) : (b))
#define DI_CLAMP(v, lo, hi) DI_MIN(DI_MAX(v, lo), hi)
#define DI_ABS(a) ((a) < 0 ? -(a) : (a))
#define DI_COUNT(a) ((int)(sizeof(a) / sizeof((a)[0])))

#endif /* DI_TYPES_H */
