package app.musik.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Bundled on-device model; segmentation and PNG caching never run on the UI thread. */
internal object ArtistCutouts {
    private val gate = Mutex()
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val unsuitable = LruCache<String, Boolean>(32)

    suspend fun load(context: Context, photo: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(photo)?.let { return@withContext it }
        if (unsuitable.get(photo) == true) return@withContext null
        gate.withLock {
            memory.get(photo)?.let { return@withLock it }
            if (unsuitable.get(photo) == true) return@withLock null
            try {
                val folder = File(context.cacheDir, "artist-cutouts-v1")
                val name = MessageDigest.getInstance("SHA-256").digest(photo.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val file = File(folder, "$name.png")
                if (file.isFile) {
                    BitmapFactory.decodeFile(file.path)?.let {
                        file.setLastModified(System.currentTimeMillis())
                        memory.put(photo, it)
                        return@withLock it
                    }
                }
                val request = ImageRequest.Builder(context).data(photo).size(768).allowHardware(false).build()
                val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withLock null
                val source = result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
                if (source.width < 256 || source.height < 256) {
                    unsuitable.put(photo, true)
                    return@withLock null
                }
                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
                val segmenter = Segmentation.getClient(options)
                data class Mask(val width: Int, val height: Int, val confidence: FloatArray)
                val mask = try {
                    // Blocking only this IO worker keeps native work serialized even if the
                    // listener navigates away. The model is closed as soon as processing ends.
                    val resultMask = Tasks.await(segmenter.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
                    val raw = resultMask.buffer
                    val buffer = raw.duplicate().order(raw.order()).apply { rewind() }
                    Mask(resultMask.width, resultMask.height, FloatArray(resultMask.width * resultMask.height) { buffer.float })
                } finally { segmenter.close() }
                currentCoroutineContext().ensureActive()
                val bitmap = withContext(Dispatchers.Default) {
                    PersonMask.cutout(source, mask.confidence, mask.width, mask.height)
                }
                if (bitmap == null) {
                    unsuitable.put(photo, true)
                    return@withLock null
                }
                memory.put(photo, bitmap)
                // Disk-cache failure should never discard a successfully extracted person.
                runCatching {
                    folder.mkdirs()
                    val temp = File(folder, "$name.tmp")
                    temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!temp.renameTo(file)) temp.delete()
                    folder.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }
                        ?.drop(16)?.forEach { it.delete() }
                }
                bitmap
            } catch (e: CancellationException) { throw e
            } catch (_: LinkageError) { null
            } catch (_: Exception) { null }
        }
    }
}
