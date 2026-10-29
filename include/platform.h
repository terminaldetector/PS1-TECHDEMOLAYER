/* platform.h - the only header that differs in meaning between the console
 * build and the host preview build. Everything above this line is shared. */
#ifndef DI_PLATFORM_H
#define DI_PLATFORM_H

#include "render.h"

void plat_init(void);
/* Pick the back buffer and reset its render list. */
void plat_frame_begin(void);
/* Wait for vblank, show the buffer we just drew, DMA the OT to the GPU. */
void plat_frame_end(void);
RenderList *plat_list(void);

/* Upload halfwords into VRAM. w/h are in 16-bit VRAM units, not texels. */
void plat_upload_vram(int x, int y, int w, int h, const u16 *src);

/* Free-running hblank counter (15.734 kHz): one tick is 63.56 us. Used for
 * all timing claims in PERFORMANCE.md. */
u32 plat_hblank(void);
/* Number of vblanks that elapsed during the last frame; 1 means the demo
 * held full refresh, 2 means it dropped to half, and so on. */
u32 plat_last_frame_vblanks(void);
/* Hblanks elapsed during the last frame; the demo clock runs off this so it
 * is independent of how many fields were dropped. 15734 ticks = 1 second. */
u32 plat_last_frame_hb(void);
u32 plat_frame_index(void);

/* Digital pad state, 0 when no pad or not supported. Bits follow the PS1
 * convention (active low is already inverted here). */
#define PAD_SELECT  (1 << 0)
#define PAD_START   (1 << 3)
#define PAD_UP      (1 << 4)
#define PAD_RIGHT   (1 << 5)
#define PAD_DOWN    (1 << 6)
#define PAD_LEFT    (1 << 7)
#define PAD_L2      (1 << 8)
#define PAD_R2      (1 << 9)
#define PAD_L1      (1 << 10)
#define PAD_R1      (1 << 11)
#define PAD_TRI     (1 << 12)
#define PAD_CIRCLE  (1 << 13)
#define PAD_CROSS   (1 << 14)
#define PAD_SQUARE  (1 << 15)
u32 plat_pad(void);

#endif /* DI_PLATFORM_H */
