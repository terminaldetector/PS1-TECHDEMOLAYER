#include "render.h"

void render_reset(RenderList *rl)
{
    int i;
    /* Reverse-ordered table: bucket i points at bucket i-1, so the DMA walk
     * starts at the far end and finishes with the nearest geometry. */
    rl->ot[0] = 0x00FFFFFFu;
    for (i = 1; i < OT_SIZE; i++)
        rl->ot[i] = di_addr(&rl->ot[i - 1]);
    rl->used = 0;
    rl->prim_count = 0;
    rl->overflow = 0;
}

void *render_alloc(RenderList *rl, u32 bytes)
{
    void *p;
    bytes = (bytes + 3u) & ~3u;
    if (rl->used + bytes > PRIM_BUF_SZ) { rl->overflow++; return NULL; }
    p = &rl->prim[rl->used];
    rl->used += bytes;
    return p;
}

void render_link(RenderList *rl, u32 idx, void *packet, u32 len_words)
{
    u32 *p = (u32 *)packet;
    p[0] = (len_words << 24) | (rl->ot[idx] & 0x00FFFFFFu);
    rl->ot[idx] = di_addr(packet);
    rl->prim_count++;
}
