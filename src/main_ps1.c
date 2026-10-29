/* main_ps1.c - the console entry point.
 *
 * One persistent world, one continuous timeline, no level loads. The clock
 * advances by the number of vblanks actually observed, so the piece runs at
 * the right speed even when a frame costs more than one field.
 */
#include "platform.h"
#include "scene.h"
#include "profiler.h"

int main(void)
{
    s32 t_ms = 0;
    u32 pad_prev = 0;

    /* The timing HUD is off in the shipped ROM; SELECT toggles it on. The
     * numbers in reports/PERFORMANCE.md were read off it. */

    plat_init();
    scene_init(0x1715u);

    for (;;) {
        u32 pad;
        prof_frame_begin();
        prof_begin(PROF_PAD);
        pad = plat_pad();
        prof_end(PROF_PAD);
        {
        u32 hit = pad & ~pad_prev;
        pad_prev = pad;

        /* Inspection switches. These exist for the acceptance tests; the
         * piece plays correctly with nothing pressed. */
        if (hit & PAD_SELECT) g_debug ^= DBG_HUD;
        if (hit & PAD_SQUARE) g_debug ^= DBG_NO_GRASS;
        if (hit & PAD_TRI)    g_debug ^= DBG_NO_TEXTURE;
        if (hit & PAD_CIRCLE) g_debug ^= DBG_NO_FIREFLY;
        if (hit & PAD_CROSS)  g_debug ^= DBG_FVA;
        if (hit & PAD_START)  g_debug ^= DBG_FREEZE_SUN;
        if (pad & PAD_L1)     g_orbit -= 2;
        if (pad & PAD_R1)     g_orbit += 2;
        if (hit & PAD_L2)     scene_set_quality(Q_LOW);
        if (hit & PAD_R2)     scene_set_quality(Q_SHOWCASE);

        }
        prof_begin(PROF_RESET);
        plat_frame_begin();
        prof_end(PROF_RESET);
        scene_frame(t_ms);
        prof_begin(PROF_PRESENT);
        plat_frame_end();
        prof_end(PROF_PRESENT);
        prof_frame_end();

        /* Advance the clock by the real elapsed time, measured in hblanks at
         * 15.734 kHz, so the 120 s programme runs at 120 s whatever the frame
         * rate happens to be. */
        t_ms += (s32)((plat_last_frame_hb() * 1000u) / 15734u);
        if (t_ms >= SCENE_LENGTH_MS) t_ms = 0;   /* the piece loops quietly */
    }
    return 0;
}
