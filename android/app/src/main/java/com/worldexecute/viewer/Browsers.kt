package com.worldexecute.viewer

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri

/**
 * Which installed browsers can we hand the film to?
 *
 * "Support picking a different browser engine" means, concretely, an ordered
 * list of packages that answer `http://` intents and are not the WebView shell
 * itself. Two details make this less trivial than querying once:
 *
 *  * **Package visibility.** From API 30 an app cannot see other packages unless
 *    it declares them. The manifest asks for the `http`/`https` VIEW intent plus
 *    `QUERY_ALL_PACKAGES`, and this class additionally resolves against those
 *    explicit intents rather than trusting `getInstalledPackages`.
 *  * **Order.** A phone usually has Chrome, and often a vendor browser that is
 *    a thin skin of Chromium as well. Ranking by a preferred-package list first
 *    (so Chrome and the known Chromium forks lead) and by label afterwards keeps
 *    the list stable between runs, which matters because the choice is persisted.
 */
object Browsers {

    data class Browser(
        val packageName: String,
        val label: String,
        val activity: String,
        val icon: Drawable?,
        val note: String,
    ) {
        /** Stable identity for the picker list, independent of icon or label. */
        val key: String get() = packageName + "/" + activity
    }

    /**
     * Packages that are known-good at rendering the film: full Chromium builds
     * with a mature `<audio>` implementation. Anything matching here sorts
     * first — a browser that ships a Gecko or WebKit engine is still listed,
     * just below these, because it is the ones above that were tested.
     */
    private val PREFERRED = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.sec.android.app.sbrowser",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.vivaldi.browser",
        "com.opera.browser",
        "com.opera.gx",
        "com.duckduckgo.mobile.android",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "us.zoom.browser",
        "com.miui.zeus.browser",
        "com.heytap.browser",
        "com.android.browser",
        "com.quark.browser",
        "com.UCMobile",
        "com.baidu.searchbox",
        "com.tencent.mtt",
    )

    /** A rough engine guess, shown as the subtitle in the picker. */
    private fun noteFor(pkg: String): String = when {
        pkg in PREFERRED && pkg.contains("firefox") -> "Gecko 内核"
        pkg in PREFERRED && pkg.contains("sbrowser") -> "Chromium 内核（三星）"
        pkg in PREFERRED && (pkg.contains("chrome") || pkg.contains("chromium")) ->
            "Chromium 内核"
        pkg in PREFERRED -> "Chromium 内核"
        pkg.contains("firefox", true) || pkg.contains("fennec", true) -> "Gecko 内核"
        pkg.contains("opera", true) || pkg.contains("browser", true) ||
            pkg.contains("chrome", true) || pkg.contains("quark", true) ->
            "疑似 Chromium 内核"
        else -> "第三方浏览器"
    }

    /**
     * Every activity that can open an `http://` URL, deduplicated by package
     * (one entry per browser, its best-ranked activity), ordered by preference.
     */
    fun list(context: Context): List<Browser> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Browser>()

        for (scheme in listOf("https", "http")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val resolved: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                Diag.add("browsers", "查询 $scheme 失败: " + e.message)
                emptyList()
            }
            for (info in resolved) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName ?: continue
                if (pkg == context.packageName) continue
                // Our own debug/WebView shell, and the system's headless ones.
                if (pkg.endsWith(".debug")) continue
                if (found.containsKey(pkg)) continue

                val app: ApplicationInfo = activity.applicationInfo ?: continue
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (e: Exception) {
                    pkg
                }
                found[pkg] = Browser(
                    packageName = pkg,
                    label = label,
                    activity = activity.name,
                    icon = try {
                        pm.getApplicationIcon(app)
                    } catch (e: Exception) {
                        null
                    },
                    note = noteFor(pkg),
                )
            }
        }

        return found.values.sortedWith(
            compareBy(
                { PREFERRED.indexOf(it.packageName).let { i -> if (i < 0) PREFERRED.size else i } },
                { it.label.lowercase() },
            ),
        )
    }

    fun find(context: Context, packageName: String?): Browser? {
        if (packageName.isNullOrBlank()) return null
        return list(context).firstOrNull { it.packageName == packageName }
    }

    /**
     * Opens [url] in [packageName]. Returns false when the browser is gone (the
     * user uninstalled it) so the caller can fall back to a plain chooser
     * instead of crashing on an ActivityNotFoundException.
     */
    fun open(context: Context, packageName: String, url: String): Boolean {
        val browser = find(context, packageName) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setClassName(browser.packageName, browser.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Diag.add("browsers", "已跳转 ${browser.label} -> $url")
            true
        } catch (e: Exception) {
            Diag.add("browsers", "跳转 ${browser.label} 失败: " + e.message)
            false
        }
    }

    /** The system's own chooser, used when nothing specific was picked. */
    fun openChooser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.browser_dialog_title))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Diag.add("browsers", "打开选择器失败: " + e.message)
        }
    }
}
