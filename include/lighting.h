/* lighting.h - one directional light plus ambient, keyframed through the
 * blue hour, sunset, dusk and moonlight.
 *
 * There are no hardware lights on the PS1 and no pixel shaders, so every
 * surface colour below is computed per vertex on the CPU and handed to the
 * GPU as a Gouraud colour. The palette keys are read off the two reference
 * paintings in art/.
 */
#ifndef DI_LIGHTING_H
#define DI_LIGHTING_H

#include "fixed.h"

typedef struct {
    s32 ldir[3];        /* unit Q12, pointing TOWARD the light     */
    s32 lcol[3];        /* Q12 intensity per channel               */
    s32 amb[3];         /* Q12 ambient per channel                 */
    s32 sky_top[3];     /* 0..255                                  */
    s32 sky_mid[3];
    s32 sky_hor[3];
    s32 fog[3];         /* 0..255, aerial perspective target       */
    s32 fog_start, fog_end;
    s32 water[3];       /* base water body colour, 0..255          */
    s32 spec[3];        /* colour of the glitter path on the lake  */
    s32 night;          /* 0..ONE                                  */
    s32 disc[3];        /* sun or moon disc colour                 */
    s32 disc_size;      /* radius in screen pixels                 */
    s32 disc_az, disc_el; /* where the disc sits, angle units      */
} Lighting;

/* phase runs 0..ONE across the whole 120 s programme. */
void light_eval(Lighting *L, s32 phase);

/* Shade a surface. n is a Q12 unit normal, base is 0..255 RGB, wrap softens
 * the terminator for foliage (0 = hard lambert, ONE = fully wrapped). */
void light_shade(const Lighting *L, const s32 n[3], const s32 base[3],
                 s32 wrap, s32 out[3]);

/* Blend toward the fog colour by view depth. */
void light_fog(const Lighting *L, s32 depth, s32 c[3]);

#endif
