/* gte.h - Geometry Transformation Engine access.
 *
 * On the console these are real COP2 instructions. On the host preview build
 * the same entry points are emulated in gte_host.c, including the saturation
 * behaviour and the UNR reciprocal divider, so that projection artefacts show
 * up identically in both renderers.
 */
#ifndef DI_GTE_H
#define DI_GTE_H

#include "fixed.h"

/* Screen-space result of projecting one triangle. */
typedef struct {
    DVECTOR s[3];   /* projected screen coordinates          */
    s32     sz[3];  /* per-vertex SZ (view depth), 0..65535  */
    s32     otz;    /* AVSZ3 average, used as the OT index   */
    s32     nclip;  /* >0 front facing with our winding      */
} TriProj;

void gte_init(void);
void gte_set_screen(s32 ofx, s32 ofy, s32 h);
void gte_load_matrix(const MATRIX *m);
u32  gte_read_flag(void);
void gte_clear_flag(void);

/* Transform three vertices, then compute the winding test and the average
 * depth. Everything the renderer needs for one triangle in one call. */
void gte_project_tri(const SVECTOR *a, const SVECTOR *b, const SVECTOR *c,
                     TriProj *out);

/* Transform a single point; returns SZ. Used for billboards and particles. */
s32  gte_project_point(const SVECTOR *v, DVECTOR *out);

/* Rotate a model-space normal by the current matrix (no translation). Result
 * is Q12. Used by the lighting stage when normals must be in view space. */
void gte_rotate_normal(const SVECTOR *n, VECTOR *out);

#endif /* DI_GTE_H */
