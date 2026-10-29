#include "profiler.h"
#include "platform.h"

static u32 g_start[PROF_COUNT];
static u32 g_acc[PROF_COUNT];
static u32 g_avg[PROF_COUNT];
static u32 g_frame_start;
static u32 g_total_acc, g_total_avg;
static int g_samples;

/* Root counter 1 is 16 bit and free running, so differences are taken modulo
 * 65536. A section longer than 4.1 seconds would alias; nothing here is. */
static inline u32 tick_delta(u32 then, u32 now) { return (now - then) & 0xFFFFu; }

void prof_frame_begin(void)
{
    int i;
    g_frame_start = plat_hblank();
    for (i = 0; i < PROF_COUNT; i++) g_acc[i] = 0;
}

void prof_begin(int slot) { g_start[slot] = plat_hblank(); }

void prof_end(int slot) { g_acc[slot] += tick_delta(g_start[slot], plat_hblank()); }

void prof_frame_end(void)
{
    int i;
    g_total_acc = tick_delta(g_frame_start, plat_hblank());
    /* Exponential smoothing over 8 frames so the bars do not flicker. */
    if (g_samples < 8) {
        for (i = 0; i < PROF_COUNT; i++) g_avg[i] = g_acc[i];
        g_total_avg = g_total_acc;
        g_samples++;
    } else {
        for (i = 0; i < PROF_COUNT; i++)
            g_avg[i] = g_avg[i] - (g_avg[i] >> 3) + (g_acc[i] >> 3);
        g_total_avg = g_total_avg - (g_total_avg >> 3) + (g_total_acc >> 3);
    }
}

u32 prof_ticks(int slot) { return g_avg[slot]; }
u32 prof_total(void) { return g_total_avg; }

/* Bar colours, one per section, chosen to stay distinguishable at 15 bpp. */
static const u8 kBar[PROF_COUNT][3] = {
    { 110, 110, 180 },   /* sky       */
    { 150, 150, 210 },   /* fuji      */
    {  80, 130, 120 },   /* far       */
    {  70, 120, 220 },   /* water     */
    { 170, 110,  60 },   /* ground    */
    {  90, 200,  90 },   /* grass     */
    { 190,  90, 220 },   /* iris      */
    { 230, 200,  80 },   /* particles */
    { 220,  70,  70 },   /* present   */
    { 255, 255, 255 },   /* pad       */
    { 120, 120, 120 },   /* reset     */
};

/* The HUD sits at y=HUD_Y, not at the top of the framebuffer: the first
 * few lines of an NTSC field fall outside what a display actually shows,
 * and anything drawn there is simply lost. */
#define HUD_Y 24

void prof_draw(DrawCtx *d)
{
    int i;
    CVECTOR black, white;

    black.r = black.g = black.b = 0;
    white.r = white.g = white.b = 255;

    /* One row per section: a black rule the full width, with a white bar on
     * top whose length is the section's cost. White on black survives the
     * GPU's ordered dither intact, so reading the measurement off a
     * screenshot is an unambiguous run length. One pixel is one hblank
     * (63.56 us); one NTSC field is 525 of them. */
    for (i = 0; i < PROF_COUNT; i++) {
        s32 w = (s32)(g_avg[i] / PROF_TICKS_PER_PX);
        s32 y = HUD_Y + i * 4;
        if (w > SCREEN_W - 6) w = SCREEN_W - 6;
        draw_tile_screen(d, 2, y, SCREEN_W - 4, 3, &black, 3, 0, 0);
        if (w > 0) draw_tile_screen(d, 2, y, w, 3, &white, 2, 0, 0);
    }
}
