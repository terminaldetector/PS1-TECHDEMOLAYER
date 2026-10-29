#include "camera.h"
#include "gte.h"

/* Mirrored for the CPU-side near-plane clipper in draw.c. */
MATRIX g_bound_mtx;

void mat_identity(MATRIX *m)
{
    int i, j;
    for (i = 0; i < 3; i++) {
        for (j = 0; j < 3; j++) m->m[i][j] = (i == j) ? ONE : 0;
        m->t[i] = 0;
    }
}

void mat_mul(const MATRIX *a, const MATRIX *b, MATRIX *out)
{
    int i, j;
    MATRIX r;
    for (i = 0; i < 3; i++)
        for (j = 0; j < 3; j++)
            r.m[i][j] = (s16)((a->m[i][0] * b->m[0][j] +
                               a->m[i][1] * b->m[1][j] +
                               a->m[i][2] * b->m[2][j]) >> QBITS);
    for (i = 0; i < 3; i++) r.t[i] = 0;
    *out = r;
}

void mat_rot_y(MATRIX *m, s32 a)
{
    s32 s = fsin(a), c = fcos(a);
    mat_identity(m);
    m->m[0][0] = (s16)c;  m->m[0][2] = (s16)s;
    m->m[2][0] = (s16)-s; m->m[2][2] = (s16)c;
}

void mat_rot_x(MATRIX *m, s32 a)
{
    s32 s = fsin(a), c = fcos(a);
    mat_identity(m);
    m->m[1][1] = (s16)c;  m->m[1][2] = (s16)-s;
    m->m[2][1] = (s16)s;  m->m[2][2] = (s16)c;
}

void mat_rot_z(MATRIX *m, s32 a)
{
    s32 s = fsin(a), c = fcos(a);
    mat_identity(m);
    m->m[0][0] = (s16)c;  m->m[0][1] = (s16)-s;
    m->m[1][0] = (s16)s;  m->m[1][1] = (s16)c;
}

void cam_build(Camera *c)
{
    s32 sy = fsin(c->yaw),   cy = fcos(c->yaw);
    s32 sp = fsin(c->pitch), cp = fcos(c->pitch);
    /* forward */
    s32 fx = fmul(sy, cp), fy = sp,          fz = fmul(cy, cp);
    /* right = normalize(worldUp x forward) */
    s32 rx = cy,           ry = 0,           rz = -sy;
    /* up = forward x right */
    s32 ux = -fmul(sp, sy), uy = cp,         uz = -fmul(sp, cy);

    /* Screen Y grows downward, so the second row is -up. */
    c->view.m[0][0] = (s16)rx;  c->view.m[0][1] = (s16)ry;  c->view.m[0][2] = (s16)rz;
    c->view.m[1][0] = (s16)-ux; c->view.m[1][1] = (s16)-uy; c->view.m[1][2] = (s16)-uz;
    c->view.m[2][0] = (s16)fx;  c->view.m[2][1] = (s16)fy;  c->view.m[2][2] = (s16)fz;
    c->view.t[0] = c->view.t[1] = c->view.t[2] = 0;

    gte_set_screen(SCREEN_W_HALF, SCREEN_H_HALF, c->h);
}

static void bind(const MATRIX *rot, s32 dx, s32 dy, s32 dz, const Camera *c)
{
    MATRIX m = *rot;
    /* Translation is computed in 32 bits, which is what keeps distant
     * objects addressable with s16 vertices. */
    m.t[0] = (c->view.m[0][0] * (s64)dx + c->view.m[0][1] * (s64)dy + c->view.m[0][2] * (s64)dz) >> QBITS;
    m.t[1] = (c->view.m[1][0] * (s64)dx + c->view.m[1][1] * (s64)dy + c->view.m[1][2] * (s64)dz) >> QBITS;
    m.t[2] = (c->view.m[2][0] * (s64)dx + c->view.m[2][1] * (s64)dy + c->view.m[2][2] * (s64)dz) >> QBITS;
    g_bound_mtx = m;
    gte_load_matrix(&m);
}

void cam_bind_object(const Camera *c, s32 wx, s32 wy, s32 wz)
{
    bind(&c->view, wx - c->px, wy - c->py, wz - c->pz, c);
}

void cam_bind_object_rot(const Camera *c, const MATRIX *model, s32 wx, s32 wy, s32 wz)
{
    MATRIX r;
    mat_mul(&c->view, model, &r);
    bind(&r, wx - c->px, wy - c->py, wz - c->pz, c);
}

void cam_dir_to_view(const Camera *c, s32 x, s32 y, s32 z, s32 *out)
{
    out[0] = (c->view.m[0][0] * x + c->view.m[0][1] * y + c->view.m[0][2] * z) >> QBITS;
    out[1] = (c->view.m[1][0] * x + c->view.m[1][1] * y + c->view.m[1][2] * z) >> QBITS;
    out[2] = (c->view.m[2][0] * x + c->view.m[2][1] * y + c->view.m[2][2] * z) >> QBITS;
}
