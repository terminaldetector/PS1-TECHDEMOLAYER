/* scene.h - top level: the director timeline and the per-frame build. */
#ifndef DI_SCENE_H
#define DI_SCENE_H

#include "draw.h"

/* Ablation and inspection switches (T14, T15, the FVA overlay). */
#define DBG_NO_GRASS    0x0001
#define DBG_NO_TEXTURE  0x0002
#define DBG_NO_LIGHT    0x0004
#define DBG_NO_FIREFLY  0x0008
#define DBG_NO_WATER    0x0010
#define DBG_FVA         0x0020   /* draw the generative skeleton over the iris */
#define DBG_FREEZE_SUN  0x0040
#define DBG_HUD         0x0080
#define DBG_NO_IRIS     0x0100
#define DBG_NO_FUJI     0x0200
#define DBG_NO_GROUND   0x0400
#define DBG_NO_FAR      0x0800
#define DBG_NO_SKY      0x1000

/* Quality profiles (T17). */
#define Q_LOW       0
#define Q_BALANCED  1
#define Q_SHOWCASE  2

typedef struct {
    s32 t_ms;
    s32 phase;        /* 0..ONE across the 120 s programme */
    s32 wind;         /* Q12 gust strength                 */
    s32 firefly;      /* 0..ONE                            */
    Camera cam;
    Lighting lt;
} Director;

extern Director g_dir;
extern u32 g_debug;
extern s32 g_orbit;
extern int g_quality;

void scene_init(u32 seed);
void scene_set_debug(u32 flags, s32 orbit_deg);
void scene_set_quality(int q);
void scene_frame(s32 t_ms);

/* Filled in by the last scene_frame(); used by PERFORMANCE.md and the HUD. */
extern DrawCtx g_draw;

#define SCENE_LENGTH_MS 120000

#endif
