#ifndef DI_GPU_SIM_H
#define DI_GPU_SIM_H
#include "di_types.h"

typedef struct {
    u32 packets, tris, pixels, oversized, unknown_cmds, bad_tags;
} GpuSimStats;

extern GpuSimStats g_gpu_stats;
extern u16 g_vram[512][1024];

void gpu_sim_reset_stats(void);
void gpu_sim_set_env(int fy);
void gpu_sim_run_ot(u32 start_addr);
void gpu_sim_vram_write(int x, int y, int w, int h, const u16 *src);
int  gpu_sim_dump_ppm(const char *path, int fy);
#endif
