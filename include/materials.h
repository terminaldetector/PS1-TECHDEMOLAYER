#ifndef DI_MATERIALS_H
#define DI_MATERIALS_H
#include "di_types.h"
#include "fixed.h"
void materials_init(void);
/* Rewrites only the 16-entry CLUTs; the synthesised patterns are untouched. */
void materials_set_night(s32 night);
u32  mat_soil_u(void);
u32  mat_petal_u(void);
u32  mat_soil_tpage(void);
u32  mat_soil_clut(void);
u32  mat_petal_tpage(void);
u32  mat_petal_clut(void);
#endif
