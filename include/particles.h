#ifndef DI_PARTICLES_H
#define DI_PARTICLES_H
#include "draw.h"
void particles_init(void);
void particles_draw_disc(DrawCtx *d);
void particles_draw_fireflies(DrawCtx *d, s32 t_ms);
#endif
