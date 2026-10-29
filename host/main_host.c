/* main_host.c - offline preview driver.
 *
 * Runs the real scene code against the GPU model and writes one PPM per
 * requested timestamp. This exists to iterate on the art and to validate the
 * packet stream; it is NOT evidence that anything runs on a PlayStation.
 * Console evidence lives in reports/SCENE_TESTS.md.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "platform.h"
#include "scene.h"
#include "gpu_sim.h"

int plat_host_last_fy(void);
extern u32 g_host_pad;

static void usage(void)
{
    fprintf(stderr,
        "usage: preview [--seed N] [--out DIR] [--times ms,ms,...]\n"
        "               [--debug FLAGS] [--orbit DEG] [--warm N] [--stats]\n"
        "               [--quality 0|1|2]\n");
}

int main(int argc, char **argv)
{
    u32 seed = 0x1715u;
    const char *out = "preview";
    s32 times[64];
    int ntimes = 0, i, warm = 0, stats = 0, quality = Q_BALANCED;
    u32 debug = 0;
    s32 orbit = 0;

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--seed") && i + 1 < argc)      seed = (u32)strtoul(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "--out") && i + 1 < argc)  out = argv[++i];
        else if (!strcmp(argv[i], "--debug") && i + 1 < argc) debug = (u32)strtoul(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "--orbit") && i + 1 < argc) orbit = (s32)strtol(argv[++i], 0, 0);
        else if (!strcmp(argv[i], "--warm") && i + 1 < argc)  warm = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--stats"))                 stats = 1;
        else if (!strcmp(argv[i], "--quality") && i + 1 < argc) quality = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--times") && i + 1 < argc) {
            char *s = argv[++i], *tok = strtok(s, ",");
            while (tok && ntimes < 64) { times[ntimes++] = atoi(tok); tok = strtok(0, ","); }
        } else { usage(); return 2; }
    }
    if (!ntimes) { times[ntimes++] = 15000; }

    plat_init();
    scene_init(seed);
    scene_set_debug(debug, orbit);
    scene_set_quality(quality);

    for (i = 0; i < ntimes; i++) {
        char path[512];
        int k;
        /* Optional warm-up frames so any frame-to-frame state settles. */
        for (k = 0; k < warm; k++) {
            plat_frame_begin();
            scene_frame(times[i]);
            plat_frame_end();
        }
        gpu_sim_reset_stats();
        plat_frame_begin();
        scene_frame(times[i]);
        plat_frame_end();

        snprintf(path, sizeof path, "%s/f%06d.ppm", out, times[i]);
        if (gpu_sim_dump_ppm(path, plat_host_last_fy()) != 0) {
            fprintf(stderr, "cannot write %s\n", path);
            return 1;
        }
        if (stats) {
            RenderList *rl = plat_list();
            printf("t=%6dms sub=%-5u pkt=%-5u tri=%-5u px=%-7u kb=%-4u | "
                   "cull b=%-4u near=%-4u scr=%-4u clip=%-4u drop=%u "
                   "| oversz=%u unk=%u badtag=%u ovf=%u\n",
                   times[i], g_draw.submitted, g_gpu_stats.packets,
                   g_gpu_stats.tris, g_gpu_stats.pixels, rl->used / 1024,
                   g_draw.culled_back, g_draw.culled_near, g_draw.culled_screen,
                   g_draw.clipped, g_draw.dropped,
                   g_gpu_stats.oversized, g_gpu_stats.unknown_cmds,
                   g_gpu_stats.bad_tags, rl->overflow);
        }
    }
    return 0;
}
