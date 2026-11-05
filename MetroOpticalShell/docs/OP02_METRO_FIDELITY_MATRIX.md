# OP02 Metro fidelity matrix

TZ §3.7 requires the chronology to be kept straight: a feature that arrived in
8.1 must not be presented as WP8.0, and nothing from Windows 10 Mobile or
Windows 11 may be passed off as Windows Phone.

`ours` = present in this codebase · `tested` = automated test ·
**no row is device-verified.**

No Microsoft fonts, icons or image assets were copied. Segoe is not
redistributed; the shell uses the platform sans-serif at Metro weights.

| Feature | WP8.0 | WP8.1 | WP8.1 Update | Win10 Mobile | ours | tested | limits |
|---|---|---|---|---|---|---|---|
| Vertical tile canvas | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| Small / medium / wide tiles | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| Large (4×4) tile | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | **Ours is not WP8-authentic.** Kept because OP01 §3.2 specified four sizes; it is a Win10-Mobile-era size. |
| Third tile column | ❌ (2 med) | ✅ on 1080p | ✅ | ✅ | ✅ | ✅ | Derived from width, not hard-coded |
| Black inter-tile gaps | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — |
| Accent palette | ✅ | ✅ | ✅ | ✅ | ✅ | — | 10 WP accents |
| Per-tile accent | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | **Our extension.** WP tiles took the system accent. |
| **Start Background (shared image)** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | Implemented per the two Microsoft articles; one image, one crop/zoom/pan transform |
| **Transparent tiles revealing it** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | Continuity across adjacent tiles pinned by test |
| Per-tile photo | ✅ | ✅ | ✅ | ✅ | ✅ (mode) | — | Mode exists; the shared Start Background has a picker, per-tile does not |
| Translucent accent fill | ❌ | ❌ | ❌ | ❌ | ✅ (mode) | ✅ | **Explicitly labelled as ours**, not a historical imitation |
| Live tile flip / cycle | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Paused when off screen, backgrounded, in battery saver or reduced motion |
| Clock / date / battery tiles | ✅ | ✅ | ✅ | ✅ | ✅ | — | Real data only |
| Notification badge counts | ✅ | ✅ | ✅ | ✅ | ✅ | — | Consent-gated; absent rather than zero without access |
| Weather tile | ✅ | ✅ | ✅ | ✅ | ❌ | — | Deliberately absent: no data source, and faking it is forbidden |
| Live Folders | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | Drag-onto-tile creation, one nesting level, dissolves below two members |
| Start ↔ All Apps swipe | ✅ | ✅ | ✅ | ✅ | ✅ | — | Horizontal pager; disabled in edit mode so a tile drag cannot page |
| Alphabet jump list | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | Full alphabet, empty letters greyed not hidden, `#` last |
| App list search | ✅ | ✅ | ✅ | ✅ | ✅ | — | — |
| Action Center | ❌ | ✅ | ✅ | ✅ | ✅ | — | Our own panel, opened by tap not swipe; hands over to the system shade, which an app cannot replace |
| Panorama / Pivot | ✅ | ✅ | ✅ | partly | ✅ | — | Our own screens only; cannot be injected into third-party APKs |
| App Bar / ellipsis | ✅ | ✅ | ✅ | ✅ | ✅ | — | Four primary commands plus an ellipsis overflow |
| Lock screen | ✅ | ✅ | ✅ | ✅ | ❌ | — | Keyguard is L4. Will not be attempted |
| Large light-weight headers | ✅ | ✅ | ✅ | ✅ | ✅ | — | `displayLarge`, FontWeight.Light |
| No rounding, no blur | ✅ | ✅ | ✅ | ✅ | ✅ | — | Material You shapes deliberately avoided |

## Presets (TZ §3.2)

| Preset | Background mode | Honest description |
|---|---|---|
| `WP8_CLASSIC` | `SOLID_ACCENT` | Opaque accent tiles. No Start Background — correct for 8.0. |
| `WP81_TRANSPARENT` | `WP81_IMAGE_MASK` | Shared Start Background through transparent tiles. |
| `METRO_EXPANDED` | `SOLID_ACCENT` | **Ours.** Denser grid for Expanded. Not a Windows Phone release. |

## The one fidelity detail taken on trust

Neither Microsoft article states the **colour of the inter-tile gaps** in words.
The black-gap appearance is asserted by TZ §3.2 and matches the published
screenshots, so it is implemented that way — but it rests on the TZ plus
imagery, not on a sentence that can be quoted. Recorded here rather than
presented as sourced.
