package org.halachmi.polls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Self-update from the repository's latest GitHub release.
 *
 * Every build of `main` is published as a release holding `poll-alarm-<versionCode>.apk`
 * (`.github/workflows/build.yml`). The repository is public, so the release is read without a
 * token. A newer build is downloaded, checked against the SHA-256 digest GitHub reports for the
 * asset, and handed to [PackageInstaller].
 *
 * Android asks once to allow installs from this app, and the first update asks for confirmation
 * because another app installed this one. From Android 12, later updates install without asking,
 * since this app installed the version being replaced.
 */
object Updater {
    private const val CHECK_EVERY_HOURS = 1L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 21
    private const val WORK_NAME = "self-update"
    private val APK = Regex("^poll-alarm-(\\d+)\\.apk$")

    data class Release(val versionCode: Int, val url: String, val sha256: String?)

    /** "owner/repo" the build was made from; empty disables updates. */
    private val repo: String get() = BuildConfig.UPDATE_REPO

    val enabled: Boolean get() = repo.isNotEmpty()

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Schedules the periodic check. Idempotent. */
    fun schedule(context: Context) {
        if (!enabled) return
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(CHECK_EVERY_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun connect(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "poll-alarm")
        }

    /** The newer release, or null when this build is the newest (or there is no release). Blocking. */
    fun check(context: Context): Release? {
        if (!enabled) return null
        val c = connect("https://api.github.com/repos/$repo/releases/latest")
        val body = try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().readText()
                404 -> return null
                else -> throw IOException("GitHub: HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val m = APK.matchEntire(a.optString("name")) ?: continue
            val sha = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            val release = Release(m.groupValues[1].toInt(), a.getString("browser_download_url"), sha)
            return release.takeIf { it.versionCode > installedVersion(context) }
        }
        return null
    }

    /** Downloads, verifies and installs [release]. Throws with a user-facing message. Blocking. */
    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            notify(app, app.getString(R.string.update_ready_title, app.getString(R.string.app_name)),
                app.getString(R.string.update_need_permission), openApp(app))
            throw IllegalStateException(app.getString(R.string.update_need_permission))
        }
        val file = File(app.cacheDir, "update.apk")
        val c = connect(release.url)
        try {
            if (c.responseCode != 200) throw IOException("download: HTTP ${c.responseCode}")
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (release.sha256 != null && sha256(file) != release.sha256.lowercase()) {
            file.delete()
            throw IllegalStateException(app.getString(R.string.update_mismatch))
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val done = PendingIntent.getBroadcast(
                app, sessionId, Intent(app, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(done.intentSender)
        }
        file.delete()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notify(context: Context, title: String, text: String, tap: PendingIntent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        try {
            nm.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_poll)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }

    fun clearNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** The periodic check. Quiet on failure: the next run tries again. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Updater.check(applicationContext)?.let { Updater.install(applicationContext, it) } }
        return Result.success()
    }
}

/**
 * [PackageInstaller]'s answer. When it needs the user's confirmation it hands over its own screen,
 * opened at once when the app is in front and through a notification otherwise.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appName = context.getString(R.string.app_name)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val tap = PendingIntent.getActivity(context, 12, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                Updater.notify(context, context.getString(R.string.update_ready_title, appName), context.getString(R.string.update_tap_to_install), tap)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.clearNotification(context)
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.notify(
                    context, context.getString(R.string.update_not_installed),
                    why.ifEmpty { context.getString(R.string.update_retry_later) }, Updater.openApp(context),
                )
            }
        }
    }
}
