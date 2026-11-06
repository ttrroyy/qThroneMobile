package app.rawline.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import app.rawline.mobile.database.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Approved wide artwork. Only the current and next small sprite pages are cached. */
class MobileSeasonalView(context:Context):View(context) {
    private companion object { val animationEpoch=SystemClock.uptimeMillis() }
    private data class Artwork(val files:List<String>,val width:Int,val height:Int,
        val columns:Int,val pageFrames:Int,val frames:Int,val interval:Long)
    private val manifest=runCatching {
        context.assets.open("seasonal/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()
    private val paint=Paint().apply { isFilterBitmap=true;isAntiAlias=true }
    private val pages=mutableMapOf<Int,Bitmap>()
    private val pending=mutableSetOf<Int>()
    private val failed=mutableSetOf<Int>()
    private var loadingScope:CoroutineScope?=null
    private var revision=0
    private var kind=""
    private var artwork:Artwork?=null
    private var displayedPage:Bitmap?=null
    private var displayedSlot=0
    private var nextCheck=0L
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false }

    private fun updateTheme(now:Long) {
        if(now<nextCheck) return
        nextCheck=now+1000
        val selected=SeasonalPolicy.resolve(DataStore.seasonalDecoration)
        if(selected==kind) return
        kind=selected;revision++
        pages.clear();pending.clear();failed.clear();displayedPage=null
        artwork=manifest?.optJSONObject(selected)?.let { entry -> runCatching {
            val files=entry.getJSONArray("files")
            Artwork(List(files.length()) { files.getString(it) },entry.getInt("width"),entry.getInt("height"),
                entry.getInt("columns"),entry.getInt("page_frames"),entry.getInt("frames"),entry.getLong("interval"))
                .also { require(it.files.isNotEmpty() && it.width>0 && it.height>0 && it.columns>0 &&
                    it.pageFrames>0 && it.frames>0 && it.interval>0) }
        }.getOrNull() }
    }
    private fun requestPage(index:Int,info:Artwork) {
        val scope=loadingScope ?: return
        if(index in pages || index in pending || index in failed) return
        val version=revision
        pending.add(index)
        scope.launch {
            val image=withContext(Dispatchers.IO) { runCatching {
                context.assets.open("seasonal/"+info.files[index]).use {
                    BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inScaled=false })
                }
            }.getOrNull() }
            if(version!=revision) return@launch
            pending.remove(index)
            if(image==null) failed.add(index) else pages[index]=image
            invalidate()
        }
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val now=SystemClock.uptimeMillis()
        val elapsed=now-animationEpoch
        updateTheme(now)
        val info=artwork
        if(info!=null) {
            val frame=((elapsed/info.interval)%info.frames).toInt()
            val page=frame/info.pageFrames
            val next=(page+1)%info.files.size
            pages.keys.retainAll(setOf(page,next))
            requestPage(page,info);requestPage(next,info)
            pages[page]?.let { displayedPage=it;displayedSlot=frame%info.pageFrames }
            // Keep the previous frame while an uncached page loads; never flash an empty frame.
            displayedPage?.let { image ->
                val scale=minOf(resources.displayMetrics.density*1.35f,width/136f,height/46f)
                val w=(136*scale).toInt();val h=(46*scale).toInt()
                val x=displayedSlot%info.columns*info.width
                val y=displayedSlot/info.columns*info.height
                val destination=Rect(width-w,height-h,width,height)
                canvas.drawBitmap(image,Rect(x,y,x+info.width,y+info.height),destination,paint)
            }
        }
        if(isShown && windowVisibility==VISIBLE) {
            val interval=info?.interval ?: 1000L
            postInvalidateDelayed(interval-elapsed%interval)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadingScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        nextCheck=0;invalidate()
    }
    override fun onDetachedFromWindow() {
        loadingScope?.cancel();loadingScope=null;revision++
        pages.clear();pending.clear();failed.clear();displayedPage=null;artwork=null;kind=""
        // Bitmaps may still belong to a RenderThread display list; let the runtime reclaim them.
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE) { nextCheck=0;invalidate() } }
    override fun onVisibilityChanged(changedView:View,visibility:Int) { super.onVisibilityChanged(changedView,visibility);if(visibility==VISIBLE) { nextCheck=0;invalidate() } }
}
