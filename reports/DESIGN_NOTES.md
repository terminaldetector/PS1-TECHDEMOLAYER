# DIGITAL IRIS — how it is built

## The FVA grammar, concretely

Every part of the plant is the same primitive: a **ribbon**. A ribbon is a
centreline integrated step by step from an elevation angle that varies along
its own length, carrying a width profile, a twist and a fold ridge. One rule,
applied at different levels of the structure with different parameters:

```
Primitive : one segment of a ribbon
Node      : one sample along a centreline — position, local frame, width
Branch    : a whole ribbon — a leaf, a tepal, the scape, a grass blade
Organism  : the ordered set of ribbons that make the plant
Structure : the plant plus its clump of grass
Environment: bank, lake, ridges, mountain, sky
```

`ribbon_eval()` in `src/iris.c` is the whole generative core. Leaves, the
scape, the bud's sheath bracts, the three falls, the three standards and the
beards are all calls into it with different parameters. Grass blades are a
cut-down version of the same integration in `src/grass.c`.

The recursion is bounded by construction: a ribbon is 7 segments x 3 columns,
and the depth of the structure is fixed at four levels. There is no unbounded
recursion anywhere, and no dynamic allocation at all.

**Nothing is ever switched on.** The bud becomes the bloom because the same
six tepals change elevation, bend and width over time. At every instant the
geometry is evaluated from the clock, so stopping at any moment gives a valid
intermediate form rather than a blend between two stored models. Each tepal
opens on its own schedule, and while `open` is 0 they are pressed into a
spindle — the bud silhouette *is* the flower, closed.

Other applications of the same idea:

- **TerrainRule** — the soil bank is a bank profile plus a dome under the
  plant plus hierarchical value noise whose amplitude falls to zero at the
  waterline, so the lake edge stays a clean line.
- **ClusterRule** — grass placement is a jittered lattice filtered by spatial
  masks: above the waterline, clear of the plant, denser on the damp ground
  near the shore.
- **VeinRule** — the petal texture is a vein field in the ribbon's own u/v,
  fanning from the claw at the base of the blade.

## Determinism

One master seed feeds six independent streams
(`terrain / iris / grass / firefly / material / motion`). Placement uses
*stateless* hashes keyed on the element's own coordinates, not a running
generator, so a clump's position depends only on its cell and never on
evaluation order. Changing the material seed cannot move a mountain vertex.
Verified by T13.

## What is computed when

| | at boot | per frame |
|---|---|---|
| Mt Fuji vertices, normals, snow mask | yes | — |
| soil grid, normals, **albedo** | yes | — |
| grass clump positions, heights, phases | yes | — |
| firefly home positions | yes | — |
| soil and petal textures, CLUTs | yes | — |
| vertex lighting | — | yes |
| day/night CLUT | — | only when the night factor moves |
| ribbon geometry (plant, grass) | — | yes |
| lake ripple, reflection, glitter | — | yes |
| camera, light keyframes | — | yes |

Moving the soil's albedo from "per frame" to "at boot" was the single largest
performance win in the project; see PERFORMANCE.md.

## The light path to the GPU

There are no hardware lights and no pixel shaders. One directional light plus
ambient, keyframed through blue hour → late afternoon → sunset → dusk →
moonlight, with the sun/moon disc placed from the *same* azimuth and elevation
that lights the scene, so the disc can never be out of step with the shading.

For each vertex: a wrapped lambert term against the light direction, modulated
into the material's base colour, then blended toward the fog colour by view
depth. The result is a Gouraud colour in the GP0 packet. For textured
polygons the GPU computes `texel * vertex_colour / 128`, so the textures are
authored as modulation maps centred on 128 and the vertex colour carries the
whole of the lighting. That is why one texture stays valid from the blue hour
to moonlight, and why the day/night palette change is a 16-entry CLUT
rewrite rather than a re-synthesis.

Foliage uses a wrapped terminator and flips its normal toward the light, so a
thin leaf lit from behind still reads instead of going flat black.

## Procedural materials

Two 64x64 4bpp textures, generated on the console at boot by **deliberately
different algorithms**:

- **Soil** — a cellular (Worley) field. Feature points on a jittered lattice;
  each texel takes its two nearest distances and darkens where they are close
  together, which is what draws the crack network between clods. Distances are
  toroidal so the tile repeats seamlessly. Grain from value noise.
- **Petal** — a parametric vein field in UV. The angular coordinate about the
  base of the blade gives veins that fan out from the claw and converge where
  the petal narrows, sharpened by repeated squaring, plus a pale rim and a
  bright midrib.

UVs stay inside each texture's own 64x64 corner of the page, so no texture
window is needed and no primitive can wander into the other map.

## Renderer

Ordering table of 2048 buckets, reverse-linked, indexed by the GTE's AVSZ
output shifted right by 5 — 32 world units of depth resolution, finer than the
spacing between iris petals. The CPU builds a linked list of real GP0 packets
and hands it to DMA channel 2 in linked-list mode.

The GTE does the work: `RTPT` for three vertices at once, `NCLIP` for the
winding test, `AVSZ3`/`AVSZ4` for depth. Vertices are loaded straight out of
the vertex array with `lwc2`, which is why `SVECTOR` is laid out as
`{vx, vy, vz, pad}` — word 0 is VXYn and word 1 is VZn.

### Near-plane handling

The GTE's divider saturates as soon as `H >= 2*SZ`. With H = 280 that means
anything closer than 140 units projects to garbage and tears across the
screen. Triangles that come near that boundary are therefore re-done on the
CPU: transformed to view space, clipped against z = near, projected by hand,
and then clipped against the screen rectangle so nothing exceeds the GPU's
1023x511 primitive limit. UVs are carried through the clipper, so the near
ground stays textured. Everything else goes through the GTE untouched.

This is the single most important piece of defensive code in the renderer.
Without it the opening shot — camera near the soil, ground running under the
lens — is unusable.

### Addressing distant geometry

The GTE's vertex registers are 16-bit, so no vertex can be further than 32767
units from its own object origin. Objects therefore carry a 32-bit world
position which goes into the GTE's translation registers, and their vertices
are local. That is what lets Mt Fuji sit 40 metres away with a 17-metre
radius without overflowing anything.

Mt Fuji is rendered at a **compressed depth scale**, and this is the one
deliberate cheat in the scene. A real 3.8 km cone 50 km away cannot be
addressed when view depth clamps at 65535. It is a genuine 3D solid in the
same camera and the same light, placed at 40 m and sized so that its angular
proportions match the reference paintings. It is not a billboard, and the
orbit test shows it parallax against the plant.

## Host preview

The preview build compiles the same scene sources against a software model of
the GPU and GTE. The model is deliberately a model of the *hardware*: it keeps
the 16-bit IR saturation, the 0..FFFFh SZ clamp, the -400h..3FFh screen clamp
and the UNR reciprocal divider with its 1FFFFh cap. If a scene blows up on the
console because a polygon crosses the near plane, it blows up in the preview
too — which is the entire point of having it.

It consumes the same linked list of GP0 packets the console's DMA consumes,
walking 24-bit tag addresses through a simulated 2 MiB RAM at the same base
address, so it doubles as a packet validator: malformed tags, unknown
commands and oversized primitives are all counted.

It is a development aid. It is never treated as evidence that anything runs
on a PlayStation.

## Artistic cheats, declared

Permitted by the brief, listed so nobody has to discover them:

1. **Mt Fuji's depth scale** — described above.
2. **The glitter path's lateral offset** is compressed so the band stays in
   frame when the light is well off to one side. The shimmer within it still
   comes from the wave slope.
3. **The lake's mountain reflection** is a tinted blend weighted by distance
   across the lake and proximity to the mountain's x extent, broken up by wave
   slope. It is an approximation, not a screen-space reflection.
4. **The backdrop fill below the horizon is dark earth, not sky.** That band
   is only ever visible through a gap in the near-field terrain, and a dark
   gap reads as shadow while a bright one reads as a hole punched in the
   ground.
5. **Distant grass is one triangle per clump**, not individual blades.

None of these affect the claims made about what runs where: the geometry is
real, the animation is computed on the console, and the frame rate is
measured.
