/* platform_ps1.c - direct hardware driver: GPU, DMA, root counters, SIO0 pad.
 *
 * No SDK is linked in. Register addresses and bit layouts follow psx-spx.
 * I/O is reached through KSEG1 (0xBF80_xxxx) so that nothing is cached.
 */
#include "platform.h"

#define IOW(a) (*(volatile u32 *)(0xBF800000u + (a)))
#define IOH(a) (*(volatile u16 *)(0xBF800000u + (a)))
#define IOB(a) (*(volatile u8  *)(0xBF800000u + (a)))

#define GP0      IOW(0x1810)
#define GP1      IOW(0x1814)
#define GPUSTAT  IOW(0x1814)

#define I_STAT   IOW(0x1070)
#define I_MASK   IOW(0x1074)

#define DPCR     IOW(0x10F0)
#define DICR     IOW(0x10F4)
#define D2_MADR  IOW(0x10A0)
#define D2_BCR   IOW(0x10A4)
#define D2_CHCR  IOW(0x10A8)
#define D6_MADR  IOW(0x10E0)
#define D6_BCR   IOW(0x10E4)
#define D6_CHCR  IOW(0x10E8)

/* Root counter 1 counts hblanks. */
#define T1_VALUE IOW(0x1110)
#define T1_MODE  IOW(0x1114)
#define T1_TGT   IOW(0x1118)

#define HB_PER_FIELD 525

#define GPUSTAT_READY_CMD  (1u << 26)
#define GPUSTAT_READY_DMA  (1u << 28)

/* Two full render lists so the CPU can build frame N+1 while the GPU is
 * still chewing on frame N's linked list. */
static RenderList g_lists[2];
static int  g_draw;          /* which list / framebuffer we are drawing into */
static u32  g_frame;
static u32  g_last_vblanks = 1;
static u32  g_last_hb_delta = HB_PER_FIELD;
static u32  g_pad;

u32 plat_frame_index(void) { return g_frame; }
u32 plat_last_frame_vblanks(void) { return g_last_vblanks; }
u32 plat_last_frame_hb(void) { return g_last_hb_delta; }
RenderList *plat_list(void) { return &g_lists[g_draw]; }
u32 plat_hblank(void) { return T1_VALUE & 0xFFFFu; }

static void gpu_sync_dma(void)
{
    int guard = 0x400000;
    while ((D2_CHCR & 0x01000000u) && --guard) { }
}

static void gpu_wait_cmd(void)
{
    int guard = 0x400000;
    while (!(GPUSTAT & GPUSTAT_READY_CMD) && --guard) { }
}

/* Set up the drawing environment for the buffer at VRAM y offset `fy`. */
static void set_draw_env(int fy)
{
    gpu_wait_cmd();
    /* E1: texpage 0, dither on, drawing to display area permitted. */
    GP0 = 0xE1000000u | (1u << 9) | (1u << 10);
    GP0 = 0xE3000000u | (0u) | ((u32)fy << 10);                       /* area TL */
    GP0 = 0xE4000000u | (SCREEN_W - 1) | ((u32)(fy + SCREEN_H - 1) << 10); /* BR */
    GP0 = 0xE5000000u | (0u & 0x7FF) | ((u32)(fy & 0x7FF) << 11);     /* offset  */
    GP0 = 0xE6000000u;                                                /* mask    */
}

void plat_init(void)
{
    /* --- GPU ---------------------------------------------------------- */
    GP1 = 0x00000000u;                       /* reset GPU                 */
    GP1 = 0x01000000u;                       /* clear command FIFO        */
    GP1 = 0x02000000u;                       /* ack GPU IRQ               */
    GP1 = 0x03000001u;                       /* blank while we set up     */
    GP1 = 0x04000000u;                       /* DMA off for now           */
    GP1 = 0x05000000u;                       /* display starts at (0,0)   */
    GP1 = 0x06000000u | 0x248u | (0xC48u << 12);  /* 320 dots horizontally */
    GP1 = 0x07000000u | 0x010u | (0x100u << 10);  /* 240 lines, NTSC       */
    GP1 = 0x08000001u;                       /* 320x240, 15 bpp, NTSC     */

    /* --- DMA ----------------------------------------------------------- */
    DPCR = 0x0B000000u | (8u << 8) | (8u << 24); /* enable ch2 (GPU), ch6 (OTC) */
    DICR = 0x00000000u;

    /* --- root counter 1 (hblank) for profiling -------------------------- */
    T1_MODE = 0x0100u;   /* free run, source = hblank */
    T1_TGT  = 0;

    I_MASK = 0;          /* everything polled */
    I_STAT = 0;

    render_reset(&g_lists[0]);
    render_reset(&g_lists[1]);
    g_draw = 0;
    set_draw_env(SCREEN_H);   /* draw into the lower buffer first */

    GP1 = 0x03000000u;        /* display on */
}

void plat_frame_begin(void)
{
    render_reset(&g_lists[g_draw]);
}

/* Wait for the next vblank edge.
 *
 * I_STAT bit 0 is a flag, not a counter: if three fields elapse while the
 * frame is being built it still reads as one, so counting it made the piece
 * play in slow motion. Elapsed real time is taken from root counter 1
 * instead, which counts hblanks continuously at 15.734 kHz. */
static u32 g_last_hb;

static u32 wait_vblank(void)
{
    int guard = 0x800000;
    u32 now, elapsed;

    I_STAT = ~1u;                      /* drop whatever is already pending */
    while (!(I_STAT & 1u) && --guard) { }
    I_STAT = ~1u;

    now = T1_VALUE & 0xFFFFu;
    g_last_hb_delta = (now - g_last_hb) & 0xFFFFu;
    if (g_last_hb_delta < 1) g_last_hb_delta = 1;
    if (g_last_hb_delta > 16 * HB_PER_FIELD) g_last_hb_delta = HB_PER_FIELD;
    g_last_hb = now;
    elapsed = g_last_hb_delta / HB_PER_FIELD;
    if (elapsed < 1) elapsed = 1;
    return elapsed;
}

void plat_frame_end(void)
{
    RenderList *rl = &g_lists[g_draw];
    int draw_fy = g_draw ? 0 : SCREEN_H;   /* the buffer we just filled */

    /* Hand the linked list to the GPU. */
    gpu_sync_dma();
    gpu_wait_cmd();
    GP1 = 0x04000002u;                      /* DMA direction: CPU -> GP0 */
    {
        int guard = 0x400000;
        while (!(GPUSTAT & GPUSTAT_READY_DMA) && --guard) { }
    }
    D2_MADR = di_addr(&rl->ot[OT_SIZE - 1]) | 0x00000000u;
    D2_BCR  = 0;
    D2_CHCR = 0x01000401u;                  /* linked list, RAM -> device */
    gpu_sync_dma();

    g_last_vblanks = wait_vblank();

    /* Show what we just drew, then start drawing into the other buffer. */
    GP1 = 0x05000000u | ((u32)draw_fy << 10);
    g_draw ^= 1;
    set_draw_env(g_draw ? 0 : SCREEN_H);
    g_frame++;
}

void plat_upload_vram(int x, int y, int w, int h, const u16 *src)
{
    u32 words = ((u32)w * (u32)h) >> 1;
    u32 bs, ba;

    gpu_sync_dma();
    gpu_wait_cmd();
    GP0 = 0xA0000000u;                                   /* VRAM write      */
    GP0 = ((u32)y << 16) | (u32)x;
    GP0 = ((u32)h << 16) | (u32)w;

    /* Pick the largest block size that divides the transfer evenly. */
    bs = 16;
    while (bs > 1 && (words % bs)) bs >>= 1;
    ba = words / bs;

    GP1 = 0x04000002u;
    {
        int guard = 0x400000;
        while (!(GPUSTAT & GPUSTAT_READY_DMA) && --guard) { }
    }
    D2_MADR = di_addr(src);
    D2_BCR  = bs | (ba << 16);
    D2_CHCR = 0x01000201u;                                /* block, RAM->dev */
    gpu_sync_dma();
}

/* ---- SIO0 digital pad --------------------------------------------------
 * Bounded everywhere: a missing or unusual pad must never stall the demo.
 */
#define JOY_DATA IOB(0x1040)
#define JOY_STAT IOW(0x1044)
#define JOY_MODE IOH(0x1048)
#define JOY_CTRL IOH(0x104A)
#define JOY_BAUD IOH(0x104E)

/* Tight guards: with no pad attached every one of these loops runs to
 * completion, and at the old 0x8000 limit that alone cost more than the whole
 * render. The pad only has to answer within a few hundred cycles. */
static int joy_xfer(int out)
{
    int guard = 0x400;
    while (!(JOY_STAT & (1u << 2)) && --guard) { }   /* TX ready */
    if (!guard) return -1;
    JOY_DATA = (u8)out;
    guard = 0x400;
    while (!(JOY_STAT & (1u << 1)) && --guard) { }   /* RX has data */
    return guard ? (int)JOY_DATA : -1;
}

static void joy_delay(int n) { while (n-- > 0) __asm__ volatile("nop"); }

static int g_pad_absent;

u32 plat_pad(void)
{
    int id_lo, id_hi, b0, b1;

    /* Once the port has answered nothing a few times, stop asking every
     * frame; a controller that appears later is picked up within a second. */
    if (g_pad_absent > 0 && (g_frame & 63) != 0) return 0;

    JOY_BAUD = 0x0088;
    JOY_MODE = 0x000D;                 /* 8 bit, MUL1 */
    JOY_CTRL = 0x1003;                 /* TX+RX enable, select port 1 */
    joy_delay(64);

    if (joy_xfer(0x01) < 0) goto fail;
    joy_delay(64);
    id_lo = joy_xfer(0x42);
    joy_delay(64);
    id_hi = joy_xfer(0x00);
    joy_delay(64);
    b0 = joy_xfer(0x00);
    joy_delay(64);
    b1 = joy_xfer(0x00);
    JOY_CTRL = 0;

    if (id_lo < 0 || id_hi < 0 || b0 < 0 || b1 < 0) goto fail;
    if (id_lo != 0x41 && id_lo != 0x73 && id_lo != 0x53) goto fail;
    g_pad_absent = 0;
    g_pad = (~(((u32)b1 << 8) | (u32)b0)) & 0xFFFFu;
    return g_pad;
fail:
    JOY_CTRL = 0;
    if (g_pad_absent < 8) g_pad_absent++;
    g_pad = 0;
    return 0;
}
