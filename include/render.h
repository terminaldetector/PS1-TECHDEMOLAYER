/* render.h - GPU packet construction and the ordering table.
 *
 * The packets built here are literal GP0 command words. The console DMAs the
 * linked list straight to the GPU; the host preview feeds the identical list
 * to a software model of the GPU. Nothing is rendered through a second,
 * "nicer" path, so what the preview shows is what the packet stream says.
 */
#ifndef DI_RENDER_H
#define DI_RENDER_H

#include "di_types.h"

/* 2048 buckets over the 16-bit SZ range: 32 world units of depth resolution,
 * which is still finer than the spacing between iris petals, and keeps the
 * linked list the DMA walks every frame down to 8 KiB. */
#define OT_SIZE      2048
#define OT_SHIFT     5
#define PRIM_BUF_SZ  (144 * 1024)

#define SCREEN_W 320
#define SCREEN_H 240

/* GP0 primitive opcodes (bit 24..31 of the first word). */
#define GPU_POLY_F3   0x20
#define GPU_POLY_FT3  0x24
#define GPU_POLY_G3   0x30
#define GPU_POLY_GT3  0x34
#define GPU_POLY_F4   0x28
#define GPU_POLY_G4   0x38
#define GPU_LINE_G2   0x50
#define GPU_TILE      0x60
#define GPU_SEMITRANS 0x02   /* OR into the opcode */

/* Semi-transparency modes, as used by the E1 draw-mode word. */
#define ABR_HALF      0   /* B/2 + F/2  */
#define ABR_ADD       1   /* B + F      */
#define ABR_SUB       2   /* B - F      */
#define ABR_ADD_QTR   3   /* B + F/4    */

#define TEX_4BIT  0
#define TEX_8BIT  1
#define TEX_16BIT 2

/* ---- address space ----------------------------------------------------
 * OT tags carry 24-bit addresses. On the console those are plain pointers
 * into main RAM. The host build carves its render lists out of a simulated
 * 2 MiB RAM at the same base address, so the tag values are byte-identical
 * and the GPU model resolves them exactly the way the DMA controller does.
 */
#define DI_RAM_BASE 0x80010000u
#define DI_RAM_SIZE (2u * 1024u * 1024u)

#ifdef DI_HOST
extern u8 *di_ram;
static inline u32 di_addr(const void *p)
{
    return (DI_RAM_BASE + (u32)((const u8 *)p - di_ram)) & 0x00FFFFFFu;
}
static inline void *di_ptr24(u32 a)
{
    return di_ram + (a - (DI_RAM_BASE & 0x00FFFFFFu));
}
#else
static inline u32 di_addr(const void *p) { return ((u32)p) & 0x00FFFFFFu; }
static inline void *di_ptr24(u32 a) { return (void *)(a | (DI_RAM_BASE & ~0x00FFFFFFu)); }
#endif

static inline u32 xy_pack(s32 x, s32 y)
{
    return ((u32)(u16)(s16)y << 16) | (u32)(u16)(s16)x;
}
static inline u32 rgb_cmd(u32 cmd, u32 r, u32 g, u32 b)
{
    return (cmd << 24) | (b << 16) | (g << 8) | r;
}
static inline u32 uv_pack(u32 u, u32 v, u32 hi)
{
    return (hi << 16) | ((v & 0xFF) << 8) | (u & 0xFF);
}
/* GP0(E1)-style page word: tx in 64px units, ty in 256px units. */
static inline u32 tpage_word(u32 tx, u32 ty, u32 abr, u32 bpp)
{
    return ((tx >> 6) & 15) | (((ty >> 8) & 1) << 4) | ((abr & 3) << 5) | ((bpp & 3) << 7);
}
static inline u32 clut_word(u32 cx, u32 cy)
{
    return (((cy & 511) << 6) | ((cx >> 4) & 63));
}

/* ---- packet layouts --------------------------------------------------- */
typedef struct { u32 tag, code, xy0, xy1, xy2; } PolyF3;
typedef struct { u32 tag, code, xy0, xy1, xy2, xy3; } PolyF4;
typedef struct { u32 tag, rgb0, xy0, rgb1, xy1, rgb2, xy2; } PolyG3;
typedef struct { u32 tag, rgb0, xy0, rgb1, xy1, rgb2, xy2, rgb3, xy3; } PolyG4;
typedef struct { u32 tag, rgb0, xy0, uv0, rgb1, xy1, uv1, rgb2, xy2, uv2; } PolyGT3;
typedef struct { u32 tag, rgb0, xy0, uv0, rgb1, xy1, uv1,
                     rgb2, xy2, uv2, rgb3, xy3, uv3; } PolyGT4;
typedef struct { u32 tag, code, xy0, uv0, xy1, uv1, xy2, uv2; } PolyFT3;
typedef struct { u32 tag, code, xy, wh; } Tile;
typedef struct { u32 tag, code; } GpuCmd1;

typedef struct {
    u32 ot[OT_SIZE];
    u8  prim[PRIM_BUF_SZ];
    u32 used;          /* bytes taken from prim[]      */
    u32 prim_count;    /* primitives linked this frame */
    u32 overflow;      /* non-zero once prim[] is full */
} RenderList;

void render_reset(RenderList *rl);
/* Allocate `bytes` from the primitive arena. Returns NULL (and flags the
 * list) when the arena is exhausted, rather than scribbling past the end. */
void *render_alloc(RenderList *rl, u32 bytes);
/* Link an already-filled packet into OT bucket `idx`. */
void  render_link(RenderList *rl, u32 idx, void *packet, u32 len_words);
/* Convert a GTE OTZ into an OT bucket, clamped into range. */
static inline u32 ot_index(s32 otz)
{
    u32 i = (u32)otz >> OT_SHIFT;
    return i >= OT_SIZE ? OT_SIZE - 1 : (i < 1 ? 1 : i);
}

#endif /* DI_RENDER_H */
