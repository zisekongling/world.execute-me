package com.worldexecute.viewer

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Unpacks the film out of the APK and onto the filesystem.
 *
 * The film lives in `assets/web/` and could be served or loaded straight from
 * there, but two things want real files: the HTTP server (which needs a
 * random-access, seekable byte range for audio scrubbing) and the byte-range
 * path in general. Unpacking once costs about nine megabytes and a second, and
 * after that every launch is a single manifest comparison.
 *
 * The copy is guarded by the `id` in `build-manifest.json`, which
 * `tools/build-web-assets.mjs` derives from the hash of every file it wrote.
 * Rebuilding the web assets therefore invalidates the unpacked copy by itself,
 * with no version number to remember to bump.
 */
object WebAssets {

    private const val ASSET_ROOT = "web"
    private const val MANIFEST = "build-manifest.json"

    fun root(context: Context): File = File(context.filesDir, "webroot")

    /** The asset id baked into the APK, or null when the assets are missing. */
    fun packagedId(context: Context): String? = try {
        context.assets.open("$ASSET_ROOT/$MANIFEST").use { input ->
            JSONObject(input.readBytes().decodeToString()).optString("id").ifBlank { null }
        }
    } catch (e: Exception) {
        Diag.add("assets", "读不到打包清单: " + e.message)
        null
    }

    private fun unpackedId(dir: File): String? = try {
        val f = File(dir, MANIFEST)
        if (!f.isFile) null
        else JSONObject(f.readText()).optString("id").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /**
     * Returns the directory holding the film, unpacking it first if what is on
     * disk is not what is in the APK. Safe to call from any thread; the work
     * happens once and later callers fall straight through.
     */
    @Synchronized
    fun ensure(context: Context): File {
        val target = root(context)
        val wanted = packagedId(context)

        if (wanted != null && wanted == unpackedId(target) && File(target, "index.html").isFile) {
            return target
        }

        val reason = if (wanted == null) "APK 里没有打包清单" else "资产 id 变了（$wanted）"
        Diag.add("assets", "解包影片：$reason")

        target.deleteRecursively()
        target.mkdirs()
        val started = System.currentTimeMillis()
        copyAsset(context, ASSET_ROOT, target)
        // Written last on purpose: a half-finished unpack can then never look
        // like a finished one, and the next launch simply tries again.
        copyAssetFile(context, "$ASSET_ROOT/$MANIFEST", File(target, MANIFEST))
        File(target, ".nomedia").writeText("")

        val bytes = target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        Diag.add("assets", "解包完成：" + bytes / 1048576 + " MB，用时 " +
                (System.currentTimeMillis() - started) + " ms")
        return target
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        val children = try {
            context.assets.list(assetPath)
        } catch (e: Exception) {
            null
        }
        if (children == null || children.isEmpty()) {
            copyAssetFile(context, assetPath, dest)
            return
        }
        dest.mkdirs()
        for (child in children) copyAsset(context, "$assetPath/$child", File(dest, child))
    }

    private fun copyAssetFile(context: Context, assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
