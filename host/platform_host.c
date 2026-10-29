/* platform_host.c - the preview build's stand-in for the console.
 *
 * Render lists live inside a simulated 2 MiB main RAM at the same base
 * address the console uses, so OT tag words are byte-identical between the
 * two builds.
 */
#include <stdlib.h>
#include <string.h>
#include "platform.h"
#include "gpu_sim.h"

u8 *di_ram;

static RenderList *g_lists[2];
static int g_draw;
static u32 g_frame;
static u32 g_hblank;
u32 g_host_pad;

RenderList *plat_list(void) { return g_lists[g_draw]; }
u32 plat_frame_index(void) { return g_frame; }
u32 plat_last_frame_vblanks(void) { return 1; }
u32 plat_last_frame_hb(void) { return 525; }
u32 plat_hblank(void) { return (g_hblank += 525) & 0xFFFFu; }
u32 plat_pad(void) { return g_host_pad; }

void plat_init(void)
{
    if (!di_ram) di_ram = (u8 *)calloc(1, DI_RAM_SIZE);
    /* Carve the two render lists out of simulated RAM. */
    g_lists[0] = (RenderList *)(di_ram + 0x1000);
    g_lists[1] = (RenderList *)(di_ram + 0x1000 + sizeof(RenderList));
    render_reset(g_lists[0]);
    render_reset(g_lists[1]);
    g_draw = 0;
    g_frame = 0;
    memset(g_vram, 0, sizeof(g_vram));
}

void plat_frame_begin(void) { render_reset(g_lists[g_draw]); }

void plat_frame_end(void)
{
    int fy = g_draw ? 0 : SCREEN_H;
    gpu_sim_set_env(fy);
    gpu_sim_run_ot(di_addr(&g_lists[g_draw]->ot[OT_SIZE - 1]));
    g_draw ^= 1;
    g_frame++;
}

void plat_upload_vram(int x, int y, int w, int h, const u16 *src)
{
    gpu_sim_vram_write(x, y, w, h, src);
}

/* The framebuffer the last plat_frame_end() drew into. */
int plat_host_last_fy(void) { return g_draw ? SCREEN_H : 0; }
