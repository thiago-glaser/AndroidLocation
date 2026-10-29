package com.example.locationservice.auto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.example.locationservice.MapPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Renders the OpenStreetMap tiles, session route polyline, start/intermediate/end circle markers,
 * and top telemetry HUD (Session Distance, Speed, Altitude) onto the Android Auto surface.
 */
class AutoMapSurfaceRenderer(
    private val carContext: CarContext
) : SurfaceCallback {

    companion object {
        private const val TAG = "AutoMapRenderer"
        private const val TILE_SIZE = 256.0
        private const val MIN_ZOOM = 2.0
        private const val MAX_ZOOM = 18.0
        private const val DEFAULT_SINGLE_POINT_ZOOM = 16.5
        private const val DEFAULT_EMPTY_LAT = 49.8951
        private const val DEFAULT_EMPTY_LNG = -97.1384
        private const val DEFAULT_EMPTY_ZOOM = 12.0
        private val SUBDOMAINS = arrayOf("a", "b", "c")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val tileCacheDir = File(carContext.cacheDir, "osm_auto_tiles").apply { mkdirs() }
    private val memoryCache = object : LruCache<String, Bitmap>(128) {}
    private val inFlightTiles = Collections.synchronizedSet(mutableSetOf<String>())

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect = Rect()
    private var stableArea: Rect = Rect()

    @Volatile
    private var sessionPoints: List<MapPoint> = emptyList()
    @Volatile
    private var rawPointsCount: Int = 0
    @Volatile
    private var distanceKm: Double = 0.0
    @Volatile
    private var speedKmh: Double = 0.0
    @Volatile
    private var altitudeMeters: Double = 0.0
    @Volatile
    private var durationText: String = "⏱️ 0 min"
    @Volatile
    private var sessionSubtitle: String = "Waiting for GPS..."

    // Camera state (in normalized Web Mercator [0..1] coordinates and continuous zoom)
    @Volatile
    private var centerNormX: Double = lonToNormX(DEFAULT_EMPTY_LNG)
    @Volatile
    private var centerNormY: Double = latToNormY(DEFAULT_EMPTY_LAT)
    @Volatile
    private var currentZoom: Double = DEFAULT_EMPTY_ZOOM

    private val density: Float
        get() = carContext.resources.displayMetrics.density.coerceAtLeast(1f)

    // Paints matching SessionMapActivity style
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#CBD5E1")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        alpha = (255 * 0.85f).toInt()
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val routeOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 180
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val startFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }

    private val endFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }

    private val intermediateFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        alpha = (255 * 0.9f).toInt()
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    private val hudCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        alpha = 232
        style = Paint.Style.FILL
    }

    private val hudBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val hudDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val hudLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val hudValueDistancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val hudValueSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val hudValueAltitudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val hudSubtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val attributionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val attributionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
    }

    fun register() {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set SurfaceCallback", e)
        }
    }

    fun unregister() {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing SurfaceCallback", e)
        }
        scope.cancel()
    }

    /**
     * Updates the session points and stats from the local database, recalculates bounds to fit
     * all points of the session, and redraws the map surface.
     */
    fun updateSessionData(
        filteredPoints: List<MapPoint>,
        totalRawPoints: Int,
        distanceKm: Double,
        speedKmh: Double,
        altitudeMeters: Double,
        durationText: String,
        subtitle: String
    ) {
        this.sessionPoints = filteredPoints
        this.rawPointsCount = totalRawPoints
        this.distanceKm = distanceKm
        this.speedKmh = speedKmh
        if (altitudeMeters != 0.0 || this.altitudeMeters == 0.0) {
            this.altitudeMeters = altitudeMeters
        }
        this.durationText = durationText
        this.sessionSubtitle = subtitle

        fitAllSessionPoints()
        renderFrame()
    }

    /**
     * Updates live GPS speed and altitude on every location tick without resetting map zoom.
     */
    fun updateLiveTelemetry(speedKmh: Double, altitudeMeters: Double) {
        this.speedKmh = speedKmh
        if (altitudeMeters != 0.0) {
            this.altitudeMeters = altitudeMeters
        }
        renderFrame()
    }

    fun fitAllSessionPoints() {
        val pts = sessionPoints
        if (pts.isEmpty()) {
            return
        }

        if (pts.size == 1) {
            centerNormX = lonToNormX(pts[0].lng)
            centerNormY = latToNormY(pts[0].lat)
            currentZoom = DEFAULT_SINGLE_POINT_ZOOM
            return
        }

        var minNormX = Double.POSITIVE_INFINITY
        var maxNormX = Double.NEGATIVE_INFINITY
        var minNormY = Double.POSITIVE_INFINITY
        var maxNormY = Double.NEGATIVE_INFINITY

        for (p in pts) {
            val nx = lonToNormX(p.lng)
            val ny = latToNormY(p.lat)
            if (nx < minNormX) minNormX = nx
            if (nx > maxNormX) maxNormX = nx
            if (ny < minNormY) minNormY = ny
            if (ny > maxNormY) maxNormY = ny
        }

        centerNormX = (minNormX + maxNormX) / 2.0
        centerNormY = (minNormY + maxNormY) / 2.0

        val viewport = getEffectiveViewport()
        val padPx = 48f * density
        val topHudPx = 80f * density
        val availWidth = (viewport.width() - padPx * 2f).coerceAtLeast(80f).toDouble()
        val availHeight = (viewport.height() - padPx * 2f - topHudPx).coerceAtLeast(80f).toDouble()

        val spanX = max(maxNormX - minNormX, 1e-6)
        val spanY = max(maxNormY - minNormY, 1e-6)

        val zoomX = log2(availWidth / (spanX * TILE_SIZE))
        val zoomY = log2(availHeight / (spanY * TILE_SIZE))
        currentZoom = min(zoomX, zoomY).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        synchronized(this) {
            this.surfaceContainer = surfaceContainer
        }
        fitAllSessionPoints()
        renderFrame()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        synchronized(this) {
            this.visibleArea = Rect(visibleArea)
        }
        fitAllSessionPoints()
        renderFrame()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        synchronized(this) {
            this.stableArea = Rect(stableArea)
        }
        renderFrame()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        synchronized(this) {
            if (this.surfaceContainer == surfaceContainer) {
                this.surfaceContainer = null
            }
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val worldSize = TILE_SIZE * 2.0.pow(currentZoom)
        centerNormX = (centerNormX + distanceX / worldSize).coerceIn(0.0, 1.0)
        centerNormY = (centerNormY + distanceY / worldSize).coerceIn(0.0, 1.0)
        renderFrame()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        val deltaZoom = log2(scaleFactor.toDouble())
        currentZoom = (currentZoom + deltaZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        renderFrame()
    }

    private fun getEffectiveViewport(): Rect {
        val container = surfaceContainer
        val width = container?.width ?: 800
        val height = container?.height ?: 480
        return if (!visibleArea.isEmpty && visibleArea.width() > 0 && visibleArea.height() > 0) {
            visibleArea
        } else {
            Rect(0, 0, width, height)
        }
    }

    @Synchronized
    fun renderFrame() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return

        val width = container.width
        val height = container.height
        if (width <= 0 || height <= 0) return

        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to lock surface canvas", e)
            return
        }

        try {
            val d = density
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val viewport = getEffectiveViewport()
            val topHudOffset = (36f * d).toInt()
            val viewCenterX = viewport.exactCenterX()
            val viewCenterY = viewport.exactCenterY() + topHudOffset

            val zoom = currentZoom
            val tileZoom = floor(zoom).toInt().coerceIn(2, 18)
            val numTiles = 1 shl tileZoom
            val worldSize = TILE_SIZE * 2.0.pow(zoom)
            val tileScreenSize = (worldSize / numTiles).toFloat()

            // Calculate visible tile index bounds
            val minNormVisX = centerNormX - (viewCenterX / worldSize)
            val maxNormVisX = centerNormX + ((width - viewCenterX) / worldSize)
            val minNormVisY = centerNormY - (viewCenterY / worldSize)
            val maxNormVisY = centerNormY + ((height - viewCenterY) / worldSize)

            val minTileX = floor(minNormVisX * numTiles).toInt()
            val maxTileX = floor(maxNormVisX * numTiles).toInt()
            val minTileY = floor(minNormVisY * numTiles).toInt().coerceAtLeast(0)
            val maxTileY = floor(maxNormVisY * numTiles).toInt().coerceAtMost(numTiles - 1)

            // 1. Draw OpenStreetMap tiles
            for (ty in minTileY..maxTileY) {
                for (tx in minTileX..maxTileX) {
                    val wrappedTx = ((tx % numTiles) + numTiles) % numTiles
                    val left = (viewCenterX + (tx.toDouble() / numTiles - centerNormX) * worldSize).toFloat()
                    val top = (viewCenterY + (ty.toDouble() / numTiles - centerNormY) * worldSize).toFloat()
                    val dstRect = RectF(left, top, left + tileScreenSize + 0.5f, top + tileScreenSize + 0.5f)

                    val bitmap = getOrFetchTile(tileZoom, wrappedTx, ty)
                    if (bitmap != null && !bitmap.isRecycled) {
                        canvas.drawBitmap(bitmap, null, dstRect, tilePaint)
                    } else {
                        val parentDrawn = drawParentFallbackTile(canvas, dstRect, tileZoom, wrappedTx, ty)
                        if (!parentDrawn) {
                            canvas.drawRect(dstRect, gridPaint)
                        }
                    }
                }
            }

            // 2. Draw route polyline & markers
            val pts = sessionPoints
            if (pts.isNotEmpty()) {
                val screenPts = pts.map { p ->
                    val nx = lonToNormX(p.lng)
                    val ny = latToNormY(p.lat)
                    val sx = (viewCenterX + (nx - centerNormX) * worldSize).toFloat()
                    val sy = (viewCenterY + (ny - centerNormY) * worldSize).toFloat()
                    Pair(sx, sy)
                }

                if (screenPts.size >= 2) {
                    val path = Path()
                    path.moveTo(screenPts[0].first, screenPts[0].second)
                    for (i in 1 until screenPts.size) {
                        path.lineTo(screenPts[i].first, screenPts[i].second)
                    }

                    routeOutlinePaint.strokeWidth = 7f * d
                    routePaint.strokeWidth = 5f * d
                    canvas.drawPath(path, routeOutlinePaint)
                    canvas.drawPath(path, routePaint)
                }

                // Draw intermediate points first so start & end markers stay on top
                val intermediateRadius = 4f * d
                markerStrokePaint.strokeWidth = 1.2f * d
                for (i in 1 until screenPts.size - 1) {
                    val (sx, sy) = screenPts[i]
                    canvas.drawCircle(sx, sy, intermediateRadius, intermediateFillPaint)
                    canvas.drawCircle(sx, sy, intermediateRadius, markerStrokePaint)
                }

                // Start marker (green #10B981 with white border)
                val startEndRadius = 8f * d
                markerStrokePaint.strokeWidth = 2.2f * d
                val (startX, startY) = screenPts.first()
                canvas.drawCircle(startX, startY, startEndRadius, startFillPaint)
                canvas.drawCircle(startX, startY, startEndRadius, markerStrokePaint)

                // End/Latest marker (red #EF4444 with white border)
                if (screenPts.size > 1) {
                    val (endX, endY) = screenPts.last()
                    canvas.drawCircle(endX, endY, startEndRadius, endFillPaint)
                    canvas.drawCircle(endX, endY, startEndRadius, markerStrokePaint)
                }
            }

            // 3. Draw Top Telemetry HUD (Session Distance, Speed, Altitude) at top of screen
            drawHudOverlay(canvas, viewport, d)

            // 4. Draw OpenStreetMap attribution in bottom-right of visibleArea
            drawAttribution(canvas, viewport, d)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlock canvas", e)
            }
        }
    }

    private fun drawParentFallbackTile(
        canvas: Canvas,
        dstRect: RectF,
        tileZoom: Int,
        tx: Int,
        ty: Int
    ): Boolean {
        if (tileZoom <= 2) return false
        val parentZoom = tileZoom - 1
        val parentTx = tx / 2
        val parentTy = ty / 2
        val parentKey = "$parentZoom/$parentTx/$parentTy"
        val parentBitmap = memoryCache.get(parentKey) ?: return false
        if (parentBitmap.isRecycled) return false

        val halfW = parentBitmap.width / 2
        val halfH = parentBitmap.height / 2
        val srcLeft = (tx % 2) * halfW
        val srcTop = (ty % 2) * halfH
        val srcRect = Rect(srcLeft, srcTop, srcLeft + halfW, srcTop + halfH)
        canvas.drawBitmap(parentBitmap, srcRect, dstRect, tilePaint)
        return true
    }

    /**
     * Draws the top telemetry bar showing Session Distance, Speed, and Altitude prominently
     * across the top of the Android Auto screen.
     */
    private fun drawHudOverlay(canvas: Canvas, viewport: Rect, d: Float) {
        hudLabelPaint.textSize = 9.5f * d
        hudValueDistancePaint.textSize = 16.5f * d
        hudValueSpeedPaint.textSize = 16.5f * d
        hudValueAltitudePaint.textSize = 16.5f * d
        hudSubtitlePaint.textSize = 11f * d

        val distLabel = "SESSION DISTANCE"
        val distValue = String.format(Locale.US, "%.2f km", distanceKm)

        val speedLabel = "SPEED"
        val speedValue = String.format(Locale.US, "%.0f km/h", speedKmh)

        val altLabel = "ALTITUDE"
        val altValue = String.format(Locale.US, "%.0f m", altitudeMeters)

        val subLine = String.format(
            Locale.US,
            "%s   •   📍 %d pts   •   %s",
            durationText,
            sessionPoints.size,
            sessionSubtitle
        )

        val colPadH = 14f * d
        val col1W = max(hudLabelPaint.measureText(distLabel), hudValueDistancePaint.measureText(distValue)) + colPadH * 2
        val col2W = max(hudLabelPaint.measureText(speedLabel), hudValueSpeedPaint.measureText(speedValue)) + colPadH * 2
        val col3W = max(hudLabelPaint.measureText(altLabel), hudValueAltitudePaint.measureText(altValue)) + colPadH * 2

        val metricsTotalW = col1W + col2W + col3W
        val subtitleTotalW = hudSubtitlePaint.measureText(subLine) + colPadH * 2
        val desiredBoxW = max(metricsTotalW, subtitleTotalW).coerceAtLeast(320f * d)
        val maxBoxW = (viewport.width() - 20f * d).coerceAtLeast(240f * d)
        val boxW = desiredBoxW.coerceAtMost(maxBoxW)

        val topRowH = 44f * d
        val bottomRowH = 22f * d
        val boxH = topRowH + bottomRowH

        val left = viewport.left + 10f * d
        val top = viewport.top.coerceAtLeast((6f * d).toInt()).toFloat() + 6f * d
        val cardRect = RectF(left, top, left + boxW, top + boxH)

        // Draw dark translucent HUD card with border
        canvas.drawRoundRect(cardRect, 12f * d, 12f * d, hudCardPaint)
        canvas.drawRoundRect(cardRect, 12f * d, 12f * d, hudBorderPaint)

        // Equal/proportional 3-column layout inside boxW
        val scale = boxW / metricsTotalW
        val c1W = col1W * scale
        val c2W = col2W * scale

        val col1Left = left
        val col2Left = col1Left + c1W
        val col3Left = col2Left + c2W

        // Vertical dividers between the 3 columns
        canvas.drawLine(col2Left, top + 6f * d, col2Left, top + topRowH - 6f * d, hudDividerPaint)
        canvas.drawLine(col3Left, top + 6f * d, col3Left, top + topRowH - 6f * d, hudDividerPaint)

        // Horizontal divider above the subtitle row
        val dividerY = top + topRowH
        canvas.drawLine(left + 10f * d, dividerY, left + boxW - 10f * d, dividerY, hudDividerPaint)

        val labelBaselineY = top + 14f * d
        val valueBaselineY = top + 35f * d

        // Column 1: Session Distance
        canvas.drawText(distLabel, col1Left + colPadH, labelBaselineY, hudLabelPaint)
        canvas.drawText(distValue, col1Left + colPadH, valueBaselineY, hudValueDistancePaint)

        // Column 2: Speed
        canvas.drawText(speedLabel, col2Left + colPadH, labelBaselineY, hudLabelPaint)
        canvas.drawText(speedValue, col2Left + colPadH, valueBaselineY, hudValueSpeedPaint)

        // Column 3: Altitude
        canvas.drawText(altLabel, col3Left + colPadH, labelBaselineY, hudLabelPaint)
        canvas.drawText(altValue, col3Left + colPadH, valueBaselineY, hudValueAltitudePaint)

        // Bottom row: Duration, Points, Active/Live Session status
        val subBaselineY = dividerY + 15.5f * d
        canvas.drawText(subLine, left + colPadH, subBaselineY, hudSubtitlePaint)
    }

    private fun drawAttribution(canvas: Canvas, viewport: Rect, d: Float) {
        attributionTextPaint.textSize = 10f * d
        val text = "© OpenStreetMap France | © OSM contributors"
        val tw = attributionTextPaint.measureText(text)
        val padH = 6f * d
        val padV = 4f * d
        val right = viewport.right - 8f * d
        val bottom = viewport.bottom - 6f * d
        val rect = RectF(right - tw - padH * 2, bottom - 14f * d - padV, right, bottom)
        canvas.drawRoundRect(rect, 4f * d, 4f * d, attributionBgPaint)
        canvas.drawText(text, rect.left + padH, bottom - padV - 2f * d, attributionTextPaint)
    }

    private fun getOrFetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memoryCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val diskFile = File(tileCacheDir, "${z}_${x}_${y}.png")
        if (diskFile.exists() && diskFile.length() > 0) {
            val diskBmp = BitmapFactory.decodeFile(diskFile.absolutePath)
            if (diskBmp != null) {
                memoryCache.put(key, diskBmp)
                return diskBmp
            }
        }

        if (inFlightTiles.add(key)) {
            scope.launch {
                try {
                    val bmp = downloadTile(z, x, y, diskFile)
                    if (bmp != null) {
                        memoryCache.put(key, bmp)
                        renderFrame()
                    }
                } finally {
                    inFlightTiles.remove(key)
                }
            }
        }
        return null
    }

    private fun downloadTile(z: Int, x: Int, y: Int, diskFile: File): Bitmap? {
        val sub = SUBDOMAINS[(x + y) % SUBDOMAINS.size]
        val urls = listOf(
            "https://$sub.tile.openstreetmap.fr/osmfr/$z/$x/$y.png",
            "https://tile.openstreetmap.org/$z/$x/$y.png"
        )

        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LocationServicesApp/1.0 (Android; TravelAccess)")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                try {
                                    FileOutputStream(diskFile).use { it.write(bytes) }
                                } catch (_: Exception) {
                                }
                                return bmp
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tile fetch failed for $url: ${e.message}")
            }
        }
        return null
    }

    private fun lonToNormX(lng: Double): Double {
        return ((lng + 180.0) / 360.0).coerceIn(0.0, 1.0)
    }

    private fun latToNormY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        val sinLat = sin(rad)
        return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)).coerceIn(0.0, 1.0)
    }
}
