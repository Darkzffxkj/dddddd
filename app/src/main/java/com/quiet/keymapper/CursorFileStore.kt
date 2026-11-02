package com.quiet.keymapper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Windows CUR import: supports embedded PNG and uncompressed 32-bit DIB entries.
 * Rejects unsupported entries instead of treating arbitrary files as cursors.
 * The imported file is stored in internal app storage, scoped to one game.
 */
object CurImage {
    data class Decoded(val bitmap: Bitmap, val hotspotX: Int, val hotspotY: Int)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun u16(b: ByteArray, i: Int) = u8(b,i) or (u8(b,i+1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b,i).toLong() or (u8(b,i+1).toLong() shl 8) or
         (u8(b,i+2).toLong() shl 16) or (u8(b,i+3).toLong() shl 24)) and 0xffffffffL
    fun decode(bytes: ByteArray): Decoded? {
        if(bytes.size < 22 || u16(bytes,0) != 0 || u16(bytes,2) != 2) return null
        val n=u16(bytes,4)
        if(n < 1 || n > 64 || 6L+n*16L > bytes.size) return null
        val choices=(0 until n).mapNotNull { i ->
            val at=6+i*16
            val w=if(u8(bytes,at)==0)256 else u8(bytes,at)
            val h=if(u8(bytes,at+1)==0)256 else u8(bytes,at+1)
            val length=u32(bytes,at+8)
            val offset=u32(bytes,at+12)
            if(offset>Int.MAX_VALUE || length>Int.MAX_VALUE ||
                offset+length > bytes.size || length<16 || w*h>262144) null
            else Triple(at,offset.toInt(),length.toInt())
        }.sortedByDescending { entry ->
            val at=entry.first
            (if(u8(bytes,at)==0)256 else u8(bytes,at)) *
            (if(u8(bytes,at+1)==0)256 else u8(bytes,at+1))
        }
        for((dir,offset,length) in choices) {
            val w=if(u8(bytes,dir)==0)256 else u8(bytes,dir)
            val h=if(u8(bytes,dir+1)==0)256 else u8(bytes,dir+1)
            val hx=u16(bytes,dir+4).coerceIn(0,w-1)
            val hy=u16(bytes,dir+6).coerceIn(0,h-1)
            val png=length>=8 && bytes.copyOfRange(offset,offset+8).contentEquals(
                byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))
            if(png) {
                val b=BitmapFactory.decodeByteArray(bytes,offset,length)
                if(b!=null && b.width in 1..512 && b.height in 1..512)
                    return Decoded(b,hx.coerceAtMost(b.width-1),hy.coerceAtMost(b.height-1))
                continue
            }
            // CUR DIB: bitmap height contains XOR pixels followed by AND mask.
            if(length<40 || u32(bytes,offset)<40) continue
            val dibW=u32(bytes,offset+4).toInt()
            val dibH=u32(bytes,offset+8).toInt()
            val bpp=u16(bytes,offset+14)
            val compression=u32(bytes,offset+16)
            val headerSize=u32(bytes,offset).toInt()
            if(dibW != w || dibH != h*2 || bpp != 32 ||
                compression != 0L || headerSize !in 40..124) continue
            val pixelOffset=offset+headerSize
            val pixelBytes=w*h*4L
            if(pixelOffset+pixelBytes > offset+length) continue
            val colors=IntArray(w*h)
            var hasAlpha=false
            for(y in 0 until h) {
                for(x in 0 until w) {
                    val i=pixelOffset+((h-1-y)*w+x)*4
                    val b=u8(bytes,i); val g=u8(bytes,i+1)
                    val red=u8(bytes,i+2); val a=u8(bytes,i+3)
                    if(a!=0) hasAlpha=true
                    colors[y*w+x]=(a shl 24) or (red shl 16) or (g shl 8) or b
                }
            }
            val maskAt=pixelOffset+pixelBytes.toInt()
            val maskRow=((w+31)/32)*4
            for(y in 0 until h) for(x in 0 until w) {
                val idx=maskAt+(h-1-y)*maskRow+x/8
                val transparent=idx<offset+length &&
                    (u8(bytes,idx) and (0x80 shr (x%8)))!=0
                val base=colors[y*w+x]
                val alpha=if(transparent)0 else if(hasAlpha)base ushr 24 else 255
                colors[y*w+x]=(base and 0x00ffffff) or (alpha shl 24)
            }
            val bitmap=Bitmap.createBitmap(colors,w,h,Bitmap.Config.ARGB_8888)
            return Decoded(bitmap,hx,hy)
        }
        return null
    }
}
class CursorFileStore(private val context: Context) {
    private val prefs=context.getSharedPreferences("referencia_game_cursors",Context.MODE_PRIVATE)
    private fun key(pkg:String)=pkg.replace(Regex("[^A-Za-z0-9_.]"),"_")
    fun filename(pkg:String):String? = prefs.getString("filename_"+pkg,null)
    fun size(pkg:String):Int = prefs.getInt("size_"+pkg,28).coerceIn(12,128)
    fun setSize(pkg:String,v:Int) { prefs.edit().putInt("size_"+pkg,v.coerceIn(12,128)).apply() }
    fun load(pkg:String):CurImage.Decoded? {
        val file=File(context.filesDir,"cursors/"+key(pkg)+".cur")
        if(!file.exists() || file.length()>2_000_000) return null
        return try { CurImage.decode(file.readBytes()) } catch(_:Exception) { null }
    }
    fun import(pkg:String,uri:Uri,displayName:String):Boolean {
        if(!displayName.lowercase().endsWith(".cur")) return false
        return try {
            val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
                val result=input.readBytes()
                if(result.size>2_000_000) null else result
            } ?: return false
            if(CurImage.decode(bytes)==null) return false
            val dir=File(context.filesDir,"cursors")
            dir.mkdirs()
            File(dir,key(pkg)+".cur").writeBytes(bytes)
            prefs.edit().putString("filename_"+pkg,displayName).apply()
            true
        } catch(_:Exception) { false }
    }
}
