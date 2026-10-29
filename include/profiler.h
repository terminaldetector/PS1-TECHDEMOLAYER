/* profiler.h - on-console section timing.
 *
 * Timing comes from root counter 1, which counts hblanks at 15.734 kHz: one
 * tick is 63.56 us and one NTSC field is 525 ticks. That is coarse enough to
 * be honest about (about 0.2% of a field) and cheap enough to leave in.
 *
 * Results are drawn as bars rather than text because there is no font in this
 * ROM; one pixel is PROF_TICKS_PER_PX hblanks, and a white tick mark is
 * placed at each field boundary, so a screenshot is a readable measurement.
 */
#ifndef DI_PROFILER_H
#define DI_PROFILER_H

#include "draw.h"

enum {
    PROF_SKY = 0,
    PROF_FUJI,
    PROF_FAR,
    PROF_WATER,
    PROF_GROUND,
    PROF_GRASS,
    PROF_IRIS,
    PROF_PARTICLES,
    PROF_PRESENT,       /* DMA to the GPU plus the wait for vblank */
    PROF_PAD,           /* controller poll                          */
    PROF_RESET,         /* ordering table clear                     */
    PROF_COUNT
};

#define PROF_TICKS_PER_PX 2

void prof_frame_begin(void);
void prof_begin(int slot);
void prof_end(int slot);
void prof_frame_end(void);
/* Smoothed per-section hblank counts from the previous frames. */
u32  prof_ticks(int slot);
u32  prof_total(void);
void prof_draw(DrawCtx *d);

#endif
