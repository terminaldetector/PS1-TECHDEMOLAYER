/* gpu_sim.c - software model of the PS1 GPU for the host preview build.
 *
 * It consumes the *same* linked list of GP0 packets that the console's DMA
 * channel 2 would consume, walking 24-bit tag addresses through the simulated
 * main RAM. Nothing in the scene code knows the difference. That makes the
 * preview useful for two things at once: seeing the art, and catching
 * malformed packets, bad texture pages and OT mistakes before they reach a
 * real console.
 *
 * Modelled: 15bpp framebuffer, 4/8/16bpp texture pages with CLUTs, Gouraud
 * and affine texture interpolation, the 4x4 ordered dither, the four
 * semi-transparency modes, the drawing offset and the drawing area clip.
 * Not modelled: timing, FIFO depth, and the GPU's exact edge-fill rule.
 */
#include <stdio.h>
#include <string.h>
#include "render.h"
#include "gpu_sim.h"

u16 g_vram[512][1024];

/* Drawing state owned by GP0(E1..E6). */
static int g_off_x, g_off_y;
static int g_clip_x0, g_clip_y0, g_clip_x1 = 1023, g_clip_y1 = 511;
static int g_dither = 1;
static int g_tp_x, g_tp_y, g_tp_abr, g_tp_bpp;

GpuSimStats g_gpu_stats;

/* The GPU's ordered dither matrix, applied to 8-bit components. */
static const int kDither[4][4] = {
    { -4,  0, -3,  1 },
    {  2, -2,  3, -1 },
    { -3,  1, -4,  0 },
    {  3, -1,  2, -2 },
};

static inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

static inline u16 to555(int r, int g, int b)
{
    return (u16)(((b >> 3) << 10) | ((g >> 3) << 5) | (r >> 3));
}

static void blend(int x, int y, int r, int g, int b, int semi, int abr)
{
    u16 *dst;
    if (x < g_clip_x0 || x > g_clip_x1 || y < g_clip_y0 || y > g_clip_y1) return;
    if (x < 0 || x > 1023 || y < 0 || y > 511) return;
    dst = &g_vram[y][x];

    if (semi) {
        u16 d = *dst;
        int br = (d & 31) << 3, bg = ((d >> 5) & 31) << 3, bb = ((d >> 10) & 31) << 3;
        switch (abr) {
        case 0: r = (br + r) >> 1;  g = (bg + g) >> 1;  b = (bb + b) >> 1;  break;
        case 1: r = br + r;         g = bg + g;         b = bb + b;         break;
        case 2: r = br - r;         g = bg - g;         b = bb - b;         break;
        default:r = br + (r >> 2);  g = bg + (g >> 2);  b = bb + (b >> 2);  break;
        }
    }
    if (g_dither) {
        int d = kDither[y & 3][x & 3];
        r += d; g += d; b += d;
    }
    *dst = to555(clamp255(r), clamp255(g), clamp255(b));
    g_gpu_stats.pixels++;
}

/* Fetch one texel through the current texture page and CLUT. Returns 0 if the
 * texel is the transparent index/value. */
static int texel(int u, int v, int clut, int *r, int *g, int *b)
{
    int tx = g_tp_x, ty = g_tp_y + (v & 255);
    u16 px;
    u &= 255;
    switch (g_tp_bpp) {
    case TEX_4BIT: {
        u16 w = g_vram[ty & 511][(tx + (u >> 2)) & 1023];
        int idx = (w >> ((u & 3) * 4)) & 15;
        int cx = (clut & 63) << 4, cy = (clut >> 6) & 511;
        px = g_vram[cy][(cx + idx) & 1023];
        break;
    }
    case TEX_8BIT: {
        u16 w = g_vram[ty & 511][(tx + (u >> 1)) & 1023];
        int idx = (w >> ((u & 1) * 8)) & 255;
        int cx = (clut & 63) << 4, cy = (clut >> 6) & 511;
        px = g_vram[cy][(cx + idx) & 1023];
        break;
    }
    default:
        px = g_vram[ty & 511][(tx + u) & 1023];
        break;
    }
    if (px == 0) return 0;
    *r = (px & 31) << 3; *g = ((px >> 5) & 31) << 3; *b = ((px >> 10) & 31) << 3;
    return 1;
}

typedef struct {
    int x, y;
    int r, g, b;
    int u, v;
} SVert;

/* Barycentric fill. The GPU does not cull, so we only normalise the winding
 * to make the inside test uniform. */
static void raster_tri(SVert a, SVert b, SVert c, int textured, int raw,
                       int semi, int clut)
{
    long area;
    int minx, maxx, miny, maxy, x, y;

    a.x += g_off_x; a.y += g_off_y;
    b.x += g_off_x; b.y += g_off_y;
    c.x += g_off_x; c.y += g_off_y;

    /* The hardware rejects polygons wider than 1023 or taller than 511. */
    minx = a.x < b.x ? (a.x < c.x ? a.x : c.x) : (b.x < c.x ? b.x : c.x);
    maxx = a.x > b.x ? (a.x > c.x ? a.x : c.x) : (b.x > c.x ? b.x : c.x);
    miny = a.y < b.y ? (a.y < c.y ? a.y : c.y) : (b.y < c.y ? b.y : c.y);
    maxy = a.y > b.y ? (a.y > c.y ? a.y : c.y) : (b.y > c.y ? b.y : c.y);
    if (maxx - minx > 1023 || maxy - miny > 511) { g_gpu_stats.oversized++; return; }

    area = (long)(b.x - a.x) * (c.y - a.y) - (long)(c.x - a.x) * (b.y - a.y);
    if (area == 0) return;
    if (area < 0) { SVert t = b; b = c; c = t; area = -area; }

    /* The vertices already carry the drawing offset, so the clip rectangle
     * applies to them directly. */
    if (minx < g_clip_x0) minx = g_clip_x0;
    if (miny < g_clip_y0) miny = g_clip_y0;
    if (maxx > g_clip_x1) maxx = g_clip_x1;
    if (maxy > g_clip_y1) maxy = g_clip_y1;

    for (y = miny; y <= maxy; y++) {
        for (x = minx; x <= maxx; x++) {
            long w0 = (long)(b.x - a.x) * (y - a.y) - (long)(x - a.x) * (b.y - a.y);
            long w1 = (long)(c.x - b.x) * (y - b.y) - (long)(x - b.x) * (c.y - b.y);
            long w2 = (long)(a.x - c.x) * (y - c.y) - (long)(x - c.x) * (a.y - c.y);
            long l0, l1, l2;
            int r, g, bl;
            if (w0 < 0 || w1 < 0 || w2 < 0) continue;
            /* w1 is opposite a, w2 opposite b, w0 opposite c. */
            l0 = w1; l1 = w2; l2 = w0;
            r  = (int)((l0 * a.r + l1 * b.r + l2 * c.r) / area);
            g  = (int)((l0 * a.g + l1 * b.g + l2 * c.g) / area);
            bl = (int)((l0 * a.b + l1 * b.b + l2 * c.b) / area);

            if (textured) {
                int tu = (int)((l0 * a.u + l1 * b.u + l2 * c.u) / area);
                int tv = (int)((l0 * a.v + l1 * b.v + l2 * c.v) / area);
                int tr, tg, tb;
                if (!texel(tu, tv, clut, &tr, &tg, &tb)) continue;
                if (!raw) {
                    /* Vertex colour modulates at 2x: 0x80 means "unchanged". */
                    tr = (tr * r) >> 7; tg = (tg * g) >> 7; tb = (tb * bl) >> 7;
                }
                blend(x, y, clamp255(tr), clamp255(tg), clamp255(tb), semi, g_tp_abr);
            } else {
                blend(x, y, r, g, bl, semi, g_tp_abr);
            }
        }
    }
    g_gpu_stats.tris++;
}

static inline int sx_of(u32 w) { return (int)(s16)(w & 0xFFFF); }
static inline int sy_of(u32 w) { return (int)(s16)(w >> 16); }

static void set_tpage(u32 t)
{
    g_tp_x   = (int)(t & 15) * 64;
    g_tp_y   = (int)((t >> 4) & 1) * 256;
    g_tp_abr = (int)((t >> 5) & 3);
    g_tp_bpp = (int)((t >> 7) & 3);
}

/* Execute one complete GP0 packet. `w` points at the command word. */
static void gp0_exec(const u32 *w, int n)
{
    u32 cmd = w[0] >> 24;
    int semi = (cmd & GPU_SEMITRANS) != 0;
    int raw  = (cmd & 0x01) != 0;

    switch (cmd & 0xFC) {
    case 0x20: case 0x28: {           /* flat triangle / quad */
        SVert v[4];
        int i, cnt = (cmd & 0x08) ? 4 : 3;
        int r = w[0] & 255, g = (w[0] >> 8) & 255, b = (w[0] >> 16) & 255;
        if (n < cnt + 1) return;
        for (i = 0; i < cnt; i++) {
            v[i].x = sx_of(w[1 + i]); v[i].y = sy_of(w[1 + i]);
            v[i].r = r; v[i].g = g; v[i].b = b; v[i].u = v[i].v = 0;
        }
        raster_tri(v[0], v[1], v[2], 0, 0, semi, 0);
        if (cnt == 4) raster_tri(v[1], v[2], v[3], 0, 0, semi, 0);
        break;
    }
    case 0x30: case 0x38: {           /* Gouraud triangle / quad */
        SVert v[4];
        int i, cnt = (cmd & 0x08) ? 4 : 3;
        if (n < cnt * 2) return;
        for (i = 0; i < cnt; i++) {
            u32 c = w[i * 2], p = w[i * 2 + 1];
            v[i].r = c & 255; v[i].g = (c >> 8) & 255; v[i].b = (c >> 16) & 255;
            v[i].x = sx_of(p); v[i].y = sy_of(p);
            v[i].u = v[i].v = 0;
        }
        raster_tri(v[0], v[1], v[2], 0, 0, semi, 0);
        if (cnt == 4) raster_tri(v[1], v[2], v[3], 0, 0, semi, 0);
        break;
    }
    case 0x24: case 0x2C: {           /* flat textured triangle / quad */
        SVert v[4];
        int i, cnt = (cmd & 0x08) ? 4 : 3, clut;
        int r = w[0] & 255, g = (w[0] >> 8) & 255, b = (w[0] >> 16) & 255;
        if (n < cnt * 2 + 1) return;
        clut = (int)(w[2] >> 16);
        set_tpage(w[4] >> 16);
        for (i = 0; i < cnt; i++) {
            v[i].x = sx_of(w[1 + i * 2]); v[i].y = sy_of(w[1 + i * 2]);
            v[i].u = (int)(w[2 + i * 2] & 255); v[i].v = (int)((w[2 + i * 2] >> 8) & 255);
            v[i].r = r; v[i].g = g; v[i].b = b;
        }
        raster_tri(v[0], v[1], v[2], 1, raw, semi, clut);
        if (cnt == 4) raster_tri(v[1], v[2], v[3], 1, raw, semi, clut);
        break;
    }
    case 0x34: case 0x3C: {           /* Gouraud textured triangle / quad */
        SVert v[4];
        int i, cnt = (cmd & 0x08) ? 4 : 3, clut;
        if (n < cnt * 3) return;
        clut = (int)(w[2] >> 16);
        set_tpage(w[5] >> 16);
        for (i = 0; i < cnt; i++) {
            u32 c = w[i * 3], p = w[i * 3 + 1], t = w[i * 3 + 2];
            v[i].r = c & 255; v[i].g = (c >> 8) & 255; v[i].b = (c >> 16) & 255;
            v[i].x = sx_of(p); v[i].y = sy_of(p);
            v[i].u = (int)(t & 255); v[i].v = (int)((t >> 8) & 255);
        }
        raster_tri(v[0], v[1], v[2], 1, raw, semi, clut);
        if (cnt == 4) raster_tri(v[1], v[2], v[3], 1, raw, semi, clut);
        break;
    }
    case 0x50: {                      /* Gouraud line */
        int x0, y0, x1, y1, dx, dy, steps, i;
        if (n < 4) return;
        x0 = sx_of(w[1]); y0 = sy_of(w[1]);
        x1 = sx_of(w[3]); y1 = sy_of(w[3]);
        dx = x1 - x0; dy = y1 - y0;
        steps = (dx < 0 ? -dx : dx) > (dy < 0 ? -dy : dy)
              ? (dx < 0 ? -dx : dx) : (dy < 0 ? -dy : dy);
        if (steps > 1023) return;
        for (i = 0; i <= steps; i++) {
            int t = steps ? (i * 256) / steps : 0;
            int r = ((w[0] & 255) * (256 - t) + (w[2] & 255) * t) >> 8;
            int g = (((w[0] >> 8) & 255) * (256 - t) + ((w[2] >> 8) & 255) * t) >> 8;
            int b = (((w[0] >> 16) & 255) * (256 - t) + ((w[2] >> 16) & 255) * t) >> 8;
            blend(x0 + dx * i / (steps ? steps : 1) + g_off_x,
                  y0 + dy * i / (steps ? steps : 1) + g_off_y, r, g, b, semi, g_tp_abr);
        }
        break;
    }
    case 0x60: {                      /* flat rectangle */
        int x, y, px, py, ww, hh;
        int r = w[0] & 255, g = (w[0] >> 8) & 255, b = (w[0] >> 16) & 255;
        if (n < 3) return;
        px = sx_of(w[1]); py = sy_of(w[1]);
        ww = (int)(w[2] & 0xFFFF); hh = (int)(w[2] >> 16);
        for (y = 0; y < hh; y++)
            for (x = 0; x < ww; x++)
                blend(px + x + g_off_x, py + y + g_off_y, r, g, b, semi, g_tp_abr);
        break;
    }
    default:
        switch (cmd) {
        case 0xE1:
            set_tpage(w[0] & 0x1FF);
            g_dither = (w[0] >> 9) & 1;
            break;
        case 0xE3:
            g_clip_x0 = (int)(w[0] & 1023); g_clip_y0 = (int)((w[0] >> 10) & 511);
            break;
        case 0xE4:
            g_clip_x1 = (int)(w[0] & 1023); g_clip_y1 = (int)((w[0] >> 10) & 511);
            break;
        case 0xE5:
            g_off_x = (int)((w[0] & 0x7FF) << 21) >> 21;
            g_off_y = (int)(((w[0] >> 11) & 0x7FF) << 21) >> 21;
            break;
        case 0xE2: case 0xE6: case 0x00: case 0x01: case 0x02:
            break;
        default:
            g_gpu_stats.unknown_cmds++;
            break;
        }
        break;
    }
}

void gpu_sim_reset_stats(void) { memset(&g_gpu_stats, 0, sizeof(g_gpu_stats)); }

void gpu_sim_set_env(int fy)
{
    g_clip_x0 = 0;            g_clip_y0 = fy;
    g_clip_x1 = SCREEN_W - 1; g_clip_y1 = fy + SCREEN_H - 1;
    g_off_x = 0;              g_off_y = fy;
    g_dither = 1;
}

/* Walk the ordering table exactly as DMA channel 2 in linked-list mode does. */
void gpu_sim_run_ot(u32 start_addr)
{
    u32 addr = start_addr & 0x00FFFFFFu;
    int guard = 4 * 1024 * 1024;
    while (addr != 0x00FFFFFFu && guard-- > 0) {
        const u32 *tag = (const u32 *)di_ptr24(addr);
        u32 len = tag[0] >> 24;
        if (len) {
            if (len > 32) { g_gpu_stats.bad_tags++; break; }
            gp0_exec(tag + 1, (int)len);
            g_gpu_stats.packets++;
        }
        addr = tag[0] & 0x00FFFFFFu;
    }
    if (guard <= 0) g_gpu_stats.bad_tags++;
}

void gpu_sim_vram_write(int x, int y, int w, int h, const u16 *src)
{
    int j, i;
    for (j = 0; j < h; j++)
        for (i = 0; i < w; i++)
            g_vram[(y + j) & 511][(x + i) & 1023] = src[j * w + i];
}

int gpu_sim_dump_ppm(const char *path, int fy)
{
    FILE *f = fopen(path, "wb");
    int x, y;
    if (!f) return -1;
    fprintf(f, "P6\n%d %d\n255\n", SCREEN_W, SCREEN_H);
    for (y = 0; y < SCREEN_H; y++) {
        for (x = 0; x < SCREEN_W; x++) {
            u16 p = g_vram[fy + y][x];
            unsigned char rgb[3];
            rgb[0] = (unsigned char)(((p      ) & 31) << 3);
            rgb[1] = (unsigned char)(((p >>  5) & 31) << 3);
            rgb[2] = (unsigned char)(((p >> 10) & 31) << 3);
            fwrite(rgb, 1, 3, f);
        }
    }
    fclose(f);
    return 0;
}
