/* camera.h - one real camera for the whole demo.
 *
 * World space is right handed with +Y up and +Z into the screen. Vertices are
 * s16 and therefore local to their object; an object's world position is
 * carried in the GTE's 32-bit translation registers instead. That is what lets
 * Mt Fuji sit 30 metres (30000 units) away without overflowing a vertex.
 */
#ifndef DI_CAMERA_H
#define DI_CAMERA_H

#include "fixed.h"
#include "render.h"

#define SCREEN_W_HALF (SCREEN_W / 2)
#define SCREEN_H_HALF (SCREEN_H / 2)

typedef struct {
    s32 px, py, pz;     /* world position                        */
    s32 yaw, pitch;     /* angle units, 4096 = full turn         */
    s32 roll;
    s32 h;              /* projection distance; 280 ~= 60 deg HFOV */
    MATRIX view;        /* world -> view rotation, Q12           */
} Camera;

void cam_build(Camera *c);
/* Load the GTE with "this object at this world position, no extra rotation". */
void cam_bind_object(const Camera *c, s32 wx, s32 wy, s32 wz);
/* Same, but with a model rotation applied first. */
void cam_bind_object_rot(const Camera *c, const MATRIX *model, s32 wx, s32 wy, s32 wz);
/* Rotate a world-space direction into view space (Q12 in, Q12 out). */
void cam_dir_to_view(const Camera *c, s32 x, s32 y, s32 z, s32 *out);

void mat_identity(MATRIX *m);
void mat_mul(const MATRIX *a, const MATRIX *b, MATRIX *out);
void mat_rot_y(MATRIX *m, s32 ang);
void mat_rot_x(MATRIX *m, s32 ang);
void mat_rot_z(MATRIX *m, s32 ang);

#endif
