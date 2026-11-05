# Phase 3.1 tablet rendering correction

## Confirmed root cause

JournalCanvas declared a private matrix while its dry and wet layers were anonymous
View subclasses. Inside either child's onDraw, unqualified matrix resolved to that
child's inherited View.getMatrix(), not JournalCanvas's page transform. Bytecode from
Phase3/main2c82a17 confirms child getMatrix() calls at Canvas.concat and renderer.draw.
The child property was an identity transform: no View property animation/scale was set.

Input already inverted the correct PageTransform. Paper/live/saved ink then rendered
in unscaled logical units, causing both the narrow paper/grey remainder and contact
misalignment. For example, with a2000px editor/1000-unit page and no pan, touching600px
stores300 logical units. Previously that appeared at300px; correctly it renders at600px.
Displacement therefore varies with position/zoom/pan, rather than being a fixed offset.
No Xiaomi-specific compensation or data-coordinate rewrite is appropriate.

The exact pinned Ink1.0.0 source JAR was inspected. CanvasStrokeRenderer's transform
argument informs stroke-to-screen rendering quality; it does not concatenate a canvas
transform. Removing Canvas.concat would retain the defect. The correction names the
matrix pageToViewMatrix and explicitly qualifies it from both child views. Both paper
and strokes use Canvas.concat(pageToViewMatrix) once, and renderer.draw receives that
same matrix as its scale hint. Stored LJPT1 vectors, pressure/brush/order and planner
layout version1 are unchanged. Software defects are confirmed; physical Xiaomi tip
calibration/latency still needs a repeat device test.

[Ink renderer contract](https://developer.android.com/reference/androidx/ink/rendering/android/canvas/CanvasStrokeRenderer)
([pinned1.0.0 source](https://dl.google.com/dl/android/maven2/androidx/ink/ink-rendering-android/1.0.0/ink-rendering-android-1.0.0-sources.jar)).

## Coordinate pipeline

1. Android supplies MotionEvent.getX/getY in the native editor's local physical pixels.
   Compose dp values are not reused as physical input positions. Parent navigation,
   toolbar/inset offsets are already reflected by native view dispatch.
2. PageTransform.toPage inverts one uniform scale/translation into stored logical units.
3. The same units feed Jetpack Ink and the spatial eraser. Planner capture clamps to
   the starting date cell. Raw screen coordinates and hardcoded offsets are not stored.
4. Dry/wet child origins are0,0 in the FrameLayout; their measured bounds match it.
5. PageTransform.toScreen and pageToViewMatrix map logical geometry into the actual
   native View width/height. Native rendering applies the matrix exactly once.
6. PixelCopy tests read the actual hardware window with getLocationInWindow bounds,
   including nonzero host offsets and real display rotation; they do not substitute
   software bitmap drawing for the native Ink layers.

## Viewport and workspace

Daily/Notebook default to native measured width/logical page width, zoom1. Paper can
extend vertically; finger pan/pinch remains uniform. Fit Width resets zoom, pan and
fit policy explicitly. App title/navigation use less vertical space. Daily date controls
share one row, and the habits link shares its status row, freeing writing space while
keeping navigation/tools accessible. Background grey was not changed to disguise size.

Planner landscape initially fits the full canonical calendar; remaining aspect-ratio
letterboxing is centered. Portrait starts at a readable writing zoom with panning.
Fit Calendar explicitly selects uniform full-page fit in either orientation. Calendar
Writing Width selects width fit, allowing vertical panning. Fit mode is included in
viewport state, retained with zoom/center rather than silently reverting on rebind.
Logical dimensions and date cells are never stretched to fill independent axes.

## Tests and diagnostics

CanvasRenderAlignmentTest compares actual wet, committed and Room-reloaded pixel
centroids to physical contacts (≤5 screen pixels) at1×/1.5×/2×, five corner/center
positions, different translations, all notebook templates, highlighter, calendar cell
clipping/date taps, diagonal strokes, actual display rotation and eraser hits.
JournalPageArchiveUiTest additionally checks real Compose/AndroidView child bounds,
Fit Width and right-edge paper pixels. Existing coordinate/input/UI/PDF tests remain.
Actual execution/counts are recorded in validation.md; compilation is not execution.

Debuggable APKs expose Settings → Input alignment diagnostic. It draws a magenta
local-touch crosshair, cyan logical→screen crosshair and numeric touch/page/expected
coordinates above the ink layer. It is transient, off by default and unavailable in
non-debuggable releases. A physical tester can compare the visible ink to both markers;
no diagnostic values or pen content are logged/stored. Toggle off for ordinary writing.
