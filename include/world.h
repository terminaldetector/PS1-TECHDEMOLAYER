/* world.h - the persistent landscape: soil bank, lake, far shore, Mt Fuji
 * and the sky gradient. Everything is generated once from the terrain seed
 * and then only re-shaded per frame.
 */
#ifndef DI_WORLD_H
#define DI_WORLD_H

#include "draw.h"

/* Where the plant stands and where the water surface sits. */
#define WATER_Y     0
#define SHORE_Z     2400

/* Mt Fuji is rendered at a compressed depth scale: a real 3.8 km cone 50 km
 * away cannot be addressed when the GTE clamps view depth to 65535. It is a
 * genuine 3D solid in the same camera and the same light, placed at 40 m and
 * sized to match the angular proportions of the reference paintings. This is
 * the one deliberate scale cheat in the scene and it is documented in
 * DESIGN_NOTES.md. */
#define FUJI_X      900
#define FUJI_Z      40000
#define FUJI_R      17000
#define FUJI_H      11300

/* Wind field shared by the plant and the grass: a slow macro swell plus a
 * faster differential term that depends on position, so nothing in the field
 * swings in phase with anything else. Returns a Q12 displacement direction
 * scaled by `strength`. */
void wind_eval(s32 x, s32 z, s32 t_ms, s32 strength, s32 *dx, s32 *dz);

s32  ground_h(s32 x, s32 z);
void ground_normal(s32 x, s32 z, s32 *n);

void world_init(void);
void world_draw_sky(DrawCtx *d, s32 t_ms);
void world_draw_fuji(DrawCtx *d);
void world_draw_far(DrawCtx *d);
void world_draw_ground(DrawCtx *d);
void world_draw_water(DrawCtx *d, s32 t_ms);

#endif
