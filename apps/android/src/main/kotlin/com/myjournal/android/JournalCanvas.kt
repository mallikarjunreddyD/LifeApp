package com.myjournal.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlinx.datetime.LocalDate
import android.widget.FrameLayout
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.myjournal.domain.*
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.uuid.Uuid

/** Native Android adapter. Domain coordinates/serialization contain no Android/Ink objects. */
class JournalCanvas(context: Context) : FrameLayout(context) {
    private var page: InkSurface? = null
    private var fitWholePage = false
    private var activeCell: LogicalRect? = null
    private var onDateNumberTap: (LocalDate) -> Unit = {}
    private var fingerDown: Pair<Float, Float>? = null
    private var fingerMoved = false
    private var strokes: List<InkStroke> = emptyList()
    private val nativeStrokes = mutableMapOf<Uuid, Stroke>()
    private val constructing = mutableSetOf<Uuid>()
    private var hitIndex = StrokeHitIndex(emptyList())
    private val renderer = CanvasStrokeRenderer.create()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var viewport = Viewport()
    private var transform = PageTransform(1f, 0f, 0f)
    internal fun viewPosition(x: Float, y: Float) = transform.toScreen(x, y)
    private val matrix = Matrix()
    private var selectedTool = EditorTool.PEN
    private var selectedColor = 0xFF252A30L
    private var selectedWidth = 2.8f
    private var onViewport: (Viewport) -> Unit = {}
    private var onStroke: (BrushTool, Long, Float, List<InkPoint>) -> InkStroke? = { _, _, _, _ -> null }
    private var onErase: (Set<Uuid>) -> Unit = {}
    private var pointerId = -1
    private var startedAt = 0L
    private var drawing: InProgressStroke? = null
    private var activeTool = BrushTool.PEN
    private var activeColor = selectedColor
    private var activeWidth = selectedWidth
    private var erasing = false
    private var supportsPressure = false
    private var pressureMaximum = 1f
    private val points = mutableListOf<InkPoint>()
    private val erased = mutableSetOf<Uuid>()
    private var erasePoint: Pair<Float, Float>? = null
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var hoverStylus = false
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dry = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val p = page ?: return
            canvas.drawColor(Color.rgb(230, 229, 222))
            canvas.save(); canvas.concat(matrix); canvas.clipRect(0f, 0f, p.width, p.height)
            PaperDrawing.draw(canvas, paperPaint, p)
            for (stroke in strokes) if (stroke.metadata.id !in erased) nativeStrokes[stroke.metadata.id]?.let { native ->
                canvas.save()
                p.calendar?.let { layout ->
                    layout.dateAt(stroke.points.first().x, stroke.points.first().y)?.let { date ->
                        val cell = layout.cell(date.day); canvas.clipRect(cell.left, cell.top, cell.right, cell.bottom)
                    }
                }
                renderer.draw(canvas, native, matrix)
                canvas.restore()
            }
            canvas.restore()
        }
    }
    private val wet = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            val p = page ?: return
            canvas.save(); canvas.concat(matrix); canvas.clipRect(0f, 0f, p.width, p.height)
            activeCell?.let { canvas.clipRect(it.left, it.top, it.right, it.bottom) }
            drawing?.let { renderer.draw(canvas, it, matrix) }
            if (erasing) erasePoint?.let { (x, y) ->
                paperPaint.color = Color.argb(100, 80, 95, 80)
                paperPaint.style = Paint.Style.STROKE; paperPaint.strokeWidth = 1f
                canvas.drawCircle(x, y, 16f, paperPaint); paperPaint.style = Paint.Style.FILL
            }
            canvas.restore()
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (pointerId != -1 || hoverStylus) return false
            fingerMoved = true
            val anchor = transform.toPage(detector.focusX, detector.focusY)
            val zoom = (viewport.zoom * detector.scaleFactor).coerceIn(1f, 4f)
            val scale = baseScale() * zoom
            viewport = Viewport(zoom, anchor.first + (width / 2f - detector.focusX) / scale,
                anchor.second + (height / 2f - detector.focusY) / scale)
            updateTransform()
            return true
        }
    })

    init {
        contentDescription = "Ruled journal page. Write with a stylus. Use a finger to pan and two fingers to zoom."
        addView(dry, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Separate display lists keep finished ink out of the per-sample rendering path.
        dry.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isSaveEnabled = false
    }

    fun bind(page: JournalPage, strokes: List<InkStroke>, tool: EditorTool, color: Long, width: Float,
        viewport: Viewport, onViewport: (Viewport) -> Unit,
        onStroke: (BrushTool, Long, Float, List<InkPoint>) -> InkStroke?, onErase: (Set<Uuid>) -> Unit) {
        bindSurface(page.inkSurface(), strokes, tool, color, width, viewport, onViewport, onStroke, onErase)
    }

    fun bindSurface(page: InkSurface, strokes: List<InkStroke>, tool: EditorTool, color: Long, width: Float,
        viewport: Viewport, onViewport: (Viewport) -> Unit,
        onStroke: (BrushTool, Long, Float, List<InkPoint>) -> InkStroke?, onErase: (Set<Uuid>) -> Unit,
        fitPage: Boolean = false, onDateTap: (LocalDate) -> Unit = {}) {
        val paperChanged = this.page != page
        val geometryChanged = fitWholePage != fitPage
        fitWholePage = fitPage
        this.onDateNumberTap = onDateTap
        contentDescription = if (page.calendar == null) "Ruled journal page. Write with a stylus. Use a finger to pan and two fingers to zoom."
            else "Monthly calendar. Stylus writes in cells. Tap date numbers with a finger to open the daily journal."
        if (this.page == null) { this.viewport = viewport; this.page = page; updateTransform() }
        this.page = page
        if (paperChanged) dry.invalidate()
        if (geometryChanged) { finishInProgress(); updateTransform() }
        this.selectedTool = tool; this.selectedColor = color; this.selectedWidth = width
        this.onViewport = onViewport; this.onStroke = onStroke; this.onErase = onErase
        if (this.strokes != strokes) {
            this.strokes = strokes
            hitIndex.update(strokes)
            nativeStrokes.keys.retainAll(strokes.map { it.metadata.id }.toSet())
            dry.invalidate()
        }
        ensureNativeStrokes()
    }
    private fun ensureNativeStrokes() {
        for (stroke in strokes) if (stroke.metadata.id !in nativeStrokes && constructing.add(stroke.metadata.id)) {
            scope.launch {
                try {
                    val built = withContext(Dispatchers.Default) { buildStroke(stroke) }
                    if (this@JournalCanvas.strokes.any { it.metadata.id == stroke.metadata.id }) {
                        nativeStrokes[stroke.metadata.id] = built
                        dry.invalidate()
                    }
                } finally { constructing.remove(stroke.metadata.id) }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) finishInProgress()
        updateTransform()
    }
    private fun baseScale(): Float {
        val p = page ?: return 1f
        val horizontal = width.toFloat() / p.width
        return if (fitWholePage) min(horizontal, height.toFloat() / p.height) else horizontal
    }
    private fun updateTransform() {
        val p = page ?: return
        if (width == 0 || height == 0) return
        val scale = baseScale() * viewport.zoom
        val tx = (width / 2f - viewport.centerX * scale).coerceIn(min(0f, width - p.width * scale), 0f)
        val ty = (height / 2f - viewport.centerY * scale).coerceIn(min(0f, height - p.height * scale), 0f)
        transform = PageTransform(scale, tx, ty)
        matrix.setValues(floatArrayOf(scale, 0f, tx, 0f, scale, ty, 0f, 0f, 1f))
        this.viewport = Viewport(viewport.zoom, (width / 2f - tx) / scale, (height / 2f - ty) / scale)
        onViewport(this.viewport)
        dry.invalidate(); wet.invalidate()
    }
    fun resetViewport() { finishInProgress(); viewport = Viewport(); updateTransform() }
    override fun onInterceptTouchEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) { fingerDown = null; cancelInProgress(); return true }
        val actionIndex = event.actionIndex
        val isStylus = event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && isStylus) {
            if (pointerId != -1) return true
            val p = page ?: return true
            val down = transform.toPage(event.getX(actionIndex), event.getY(actionIndex))
            if (down.first !in 0f..p.width || down.second !in 0f..p.height) return true
            val wantsErase = selectedTool == EditorTool.ERASER || event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER
            val cellDate = p.calendar?.dateAt(down.first, down.second)
            if (p.calendar != null && !wantsErase && cellDate == null) return true
            activeCell = if (!wantsErase && cellDate != null) p.calendar?.cell(cellDate.day) else null
            pointerId = event.getPointerId(actionIndex)
            startedAt = event.eventTime
            val range = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source)
            supportsPressure = range != null || event.getPressure(actionIndex) > 0f
            pressureMaximum = range?.max?.takeIf { it.isFinite() && it > 1f } ?: 1f
            erasing = selectedTool == EditorTool.ERASER || event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER
            activeTool = if (selectedTool == EditorTool.HIGHLIGHTER) BrushTool.HIGHLIGHTER else BrushTool.PEN
            activeColor = if (activeTool == BrushTool.HIGHLIGHTER) (selectedColor and 0xFFFFFFL) or 0x55000000L else selectedColor
            activeWidth = selectedWidth
            points.clear(); erased.clear()
            if (!erasing) drawing = InProgressStroke().apply { start(brush(activeTool, activeColor, activeWidth)) }
            parent?.requestDisallowInterceptTouchEvent(true)
            requestUnbufferedDispatch(event)
            sample(event, actionIndex)
            return true
        }
        if (pointerId != -1) {
            val index = event.findPointerIndex(pointerId)
            if (index < 0) { cancelInProgress(); return true }
            if (action == MotionEvent.ACTION_MOVE) sample(event, index)
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && event.getPointerId(actionIndex) == pointerId) {
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) cancelInProgress()
                else { sample(event, index); finishInProgress() }
            }
            return true // Ignore every finger/palm while the stylus is active.
        }
        if (hoverStylus || isStylus || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return true
        if (action == MotionEvent.ACTION_DOWN) { fingerDown = event.x to event.y; fingerMoved = false }
        if (event.pointerCount > 1) fingerMoved = true
        fingerDown?.let { start ->
            if (hypot(event.x - start.first, event.y - start.second) > ViewConfiguration.get(context).scaledTouchSlop) fingerMoved = true
        }
        scaleDetector.onTouchEvent(event)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val index = if (action == MotionEvent.ACTION_POINTER_UP && actionIndex == 0 && event.pointerCount > 1) 1 else 0
                lastFingerX = event.getX(index); lastFingerY = event.getY(index)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && fingerMoved) {
                    viewport = viewport.copy(centerX = viewport.centerX - (event.x - lastFingerX) / transform.scale,
                        centerY = viewport.centerY - (event.y - lastFingerY) / transform.scale)
                    updateTransform()
                }
                lastFingerX = event.x; lastFingerY = event.y
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!fingerMoved && fingerDown != null) {
                val logical = transform.toPage(event.x, event.y)
                page?.calendar?.dateNumberAt(logical.first, logical.second)?.let(onDateNumberTap)
            }
            fingerDown = null
        }
        return true
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
            hoverStylus = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        return true
    }
    private fun sample(event: MotionEvent, index: Int) {
        val p = page ?: return
        val batch = MutableStrokeInputBatch()
        fun add(x: Float, y: Float, time: Long, pressure: Float) {
            if (!x.isFinite() || !y.isFinite()) return
            val position = transform.toPage(x, y)
            val bounds = activeCell ?: LogicalRect(0f, 0f, p.width, p.height)
            val point = InkPoint(position.first.coerceIn(bounds.left, bounds.right - 0.001f),
                position.second.coerceIn(bounds.top, bounds.bottom - 0.001f),
                maxOf(time - startedAt, (points.lastOrNull()?.elapsedMillis ?: -1) + 1),
                if (supportsPressure && pressure.isFinite()) (pressure / pressureMaximum).coerceIn(0f, 1f) else null)
            if (erasing) {
                val old = erasePoint ?: (point.x to point.y)
                val steps = max(1, ceil(hypot(point.x - old.first, point.y - old.second) / 8).toInt())
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    erased.addAll(hitIndex.hit(old.first + (point.x - old.first) * t, old.second + (point.y - old.second) * t, 16f))
                }
                erasePoint = point.x to point.y
            } else batch.add(InputToolType.STYLUS, point.x, point.y, point.elapsedMillis, pressure = point.pressure ?: 1f)
            points.add(point)
        }
        for (history in 0 until event.historySize)
            add(event.getHistoricalX(index, history), event.getHistoricalY(index, history), event.getHistoricalEventTime(history), event.getHistoricalPressure(index, history))
        add(event.getX(index), event.getY(index), event.eventTime, event.getPressure(index))
        if (!erasing && batch.size > 0) {
            drawing?.enqueueInputs(batch, ImmutableStrokeInputBatch.EMPTY)
            drawing?.updateShape()
        }
        if (erasing) dry.invalidate()
        wet.invalidate()
    }
    fun finishInProgress() {
        if (pointerId == -1) return
        if (erasing) {
            if (erased.isNotEmpty()) onErase(erased.toSet())
        } else if (points.isNotEmpty()) {
            drawing?.finishInput(); drawing?.updateShape()
            val completed = drawing?.toImmutable()
            val stroke = onStroke(activeTool, activeColor, activeWidth, points.toList())
            if (stroke != null && completed != null) nativeStrokes[stroke.metadata.id] = completed
        }
        clearActive()
    }
    private fun cancelInProgress() { clearActive() }
    private fun clearActive() {
        pointerId = -1; drawing = null; erasing = false; activeCell = null; points.clear(); erased.clear(); erasePoint = null
        parent?.requestDisallowInterceptTouchEvent(false)
        dry.invalidate(); wet.invalidate()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) finishInProgress()
    }
    override fun onDetachedFromWindow() {
        finishInProgress()
        scope.cancel()
        constructing.clear()
        super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ensureNativeStrokes()
    }
    private fun brush(tool: BrushTool, color: Long, width: Float) = Brush.createWithColorIntArgb(
        family = if (tool == BrushTool.PEN) StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
            else StockBrushes.highlighter(version = StockBrushes.HighlighterVersion.V1),
        colorIntArgb = color.toInt(), size = width, epsilon = 0.1f)
    private fun buildStroke(stroke: InkStroke): Stroke {
        val batch = MutableStrokeInputBatch()
        stroke.points.forEach { batch.add(InputToolType.STYLUS, it.x, it.y, it.elapsedMillis, pressure = it.pressure ?: 1f) }
        return Stroke(brush(stroke.tool, stroke.colorArgb, stroke.width), batch)
    }
}
