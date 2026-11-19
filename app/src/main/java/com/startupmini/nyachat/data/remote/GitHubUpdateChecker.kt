package com.startupmini.nyachat.data.remote

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Informasi release terbaru dari GitHub. */
data class GitHubRelease(
    val tagName: String,
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
    /** Catatan rilis (body GitHub API), sudah dibersihkan dari sintaks markdown. */
    val body: String = ""
)

/**
 * R2-H4: hasil cek update yang TIDAK menyamarkan error sebagai "tidak ada update".
 * Pemanggil wajib menangani ketiga cabang — terutama [Error] supaya user diberi
 * feedback + aksi Coba Lagi, bukan diam-diam dianggap sudah up-to-date.
 */
sealed interface UpdateCheckResult {
    data class Update(val release: GitHubRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Error : UpdateCheckResult
}

/**
 * Pengecek update otomatis dari GitHub Release (repo ini).
 * - checkLatest(): ambil release terbaru via GitHub API.
 * - isNewer(): bandingkan versi (semantic version) dengan versi terpasang.
 * - downloadApk(): unduh APK ke cache app untuk di-install.
 * - verifyApkSignature(): WAJIB dipanggil sebelum install (audit H4/M10).
 */
object GitHubUpdateChecker {

    private const val TAG = "UpdateChecker"
    // Path repo dari Constants.Links (SATU sumber kebenaran — audit remote/ 2026-08-13):
    // sebelumnya literal diduplikasi di sini, rename repo tidak merambat ke API update.
    private const val API_URL =
        "https://api.github.com/repos/${Constants.Links.GITHUB_OWNER_REPO}/releases/latest"

    // H4 (audit 2026-11-19): hanya host GitHub ini yang boleh menjadi sumber APK.
    // Dipakai untuk URL awal DAN setiap hop redirect (OkHttp redirect otomatis
    // dinonaktifkan — lihat client di bawah).
    private val ALLOWED_APK_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )
    private const val MAX_REDIRECTS = 5

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // H4: jangan ikuti redirect otomatis ke host arbitrer. Redirect masih
            // didukung lewat loop manual di downloadApk() yang memvalidasi host.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Ambil release terbaru dari GitHub. null kalau gagal / belum ada release.
     *
     * @deprecated gunakan [checkLatestResult] — API lama tidak bisa membedakan
     * gagal jaringan dengan "sudah versi terbaru". Dipertahankan agar test lama
     * tetap kompilasi.
     */
    @Deprecated("Gunakan checkLatestResult() yang mengembalikan UpdateCheckResult")
    suspend fun checkLatest(): GitHubRelease? = fetchLatest()

    /** R2-H4: cek update dengan hasil eksplisit (Update / UpToDate / Error). */
    suspend fun checkLatestResult(): UpdateCheckResult {
        val release = fetchLatest() ?: return UpdateCheckResult.Error
        return if (isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
            UpdateCheckResult.Update(release)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /** Implementasi fetch lama (tanpa perbandingan versi) — dipakai kedua API. */
    private suspend fun fetchLatest(): GitHubRelease? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(API_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub API ${response.code}")
                    null
                } else {
                    val root = JSONObject(response.body?.string().orEmpty())
                    val tag = root.optString("tag_name", "")
                    val releaseUrl = root.optString("html_url", "")
                    val assets = root.optJSONArray("assets") ?: JSONArray()

                    // H4/M10 (audit 2026-11-19): HANYA aset release-signed yang
                    // diterima. Fallback ke APK debug dihapus — debug.keystore
                    // di-commit dengan password standar sehingga siapa pun bisa
                    // menandatangani APK debug. URL juga wajib lulus allowlist
                    // HTTPS; kalau tidak ada kandidat yang valid → apkUrl null
                    // (fail-closed, update tidak akan dipasang).
                    var releaseApk: String? = null
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) &&
                            name.contains("release", ignoreCase = true) &&
                            isAllowedApkUrl(url)
                        ) {
                            releaseApk = url
                        }
                    }
                    GitHubRelease(
                        tagName = tag,
                        versionName = tag.removePrefix("r").removePrefix("v"),
                        apkUrl = releaseApk,
                        releaseUrl = releaseUrl,
                        body = sanitizeReleaseNotes(root.optString("body", ""))
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cek update gagal: ${e.message}")
            null
        }
    }

    /** Bandingkan versi remote vs terpasang (major.minor.patch). */
    fun isNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = parseVersion(remoteVersion) ?: return false
        val current = parseVersion(currentVersion) ?: return false
        return remote[0] > current[0] ||
            (remote[0] == current[0] && remote[1] > current[1]) ||
            (remote[0] == current[0] && remote[1] == current[1] && remote[2] > current[2])
    }

    private fun parseVersion(version: String): List<Int>? {
        val parts = version.trim().removePrefix("r").removePrefix("v").split(".")
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return listOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    /**
     * H4: terima hanya URL HTTPS dengan host yang termasuk allowlist GitHub.
     * Dipakai lagi untuk setiap hop redirect (fail-closed bila host keluar).
     */
    fun isAllowedApkUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme?.equals("https", ignoreCase = true) == true &&
            uri.userInfo == null &&
            uri.host?.lowercase(Locale.US) in ALLOWED_APK_HOSTS
    } catch (e: Exception) {
        false
    }

    /**
     * Unduh APK ke path tujuan (cache app — tanpa izin eksternal).
     * [onProgress] dipanggil dengan progres 0..1 (tidak lebih dari ~4x/detik)
     * — dipakai dialog update untuk menampilkan bar progres.
     *
     * H4: redirect diikuti manual, satu per satu, dan setiap URL divalidasi
     * terhadap allowlist host sebelum request berikutnya.
     */
    suspend fun downloadApk(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {}
    ) {
        withContext(Dispatchers.IO) {
            var currentUrl = url
            var redirects = 0
            while (true) {
                if (!isAllowedApkUrl(currentUrl)) {
                    throw IOException("URL unduhan tidak diizinkan: $currentUrl")
                }
                val request = Request.Builder().url(currentUrl).build()
                var downloaded = false
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location")
                            ?: throw IOException("Redirect tanpa Location")
                        currentUrl = URI(currentUrl).resolve(location).toString()
                        redirects++
                        if (redirects > MAX_REDIRECTS) throw IOException("Terlalu banyak redirect")
                        return@use
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Response kosong")
                    val total = body.contentLength()
                    destination.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var read = 0L
                            // Throttle: laporkan tiap ~256 KB supaya UI tidak spam-recompose
                            // saat progres di-marshal ke main thread.
                            var lastReported = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                                read += n
                                if (total > 0 && (read - lastReported >= 256 * 1024 || read >= total)) {
                                    lastReported = read
                                    onProgress((read.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                            if (total <= 0) onProgress(1f)
                        }
                    }
                    downloaded = true
                }
                if (downloaded) return@withContext
            }
        }
    }

    /**
     * Verifikasi keaslian APK SEBELUM install (audit H4/M10).
     * SHA-256 sertifikat penandatangan pertama pada arsip dibandingkan dengan
     * [BuildConfig.APK_CERT_SHA256]. Fail-closed: fingerprint kosong/blank,
     * file tidak valid, atau tidak ada sertifikat → false (jangan dipasang).
     */
    @Suppress("DEPRECATION", "NewApi")
    fun verifyApkSignature(context: Context, file: File): Boolean {
        return try {
            val expected = BuildConfig.APK_CERT_SHA256.normalizeFingerprint()
            if (expected.isBlank()) {
                Log.e(TAG, "APK_CERT_SHA256 belum dikonfigurasi — update ditolak (fail-closed)")
                return false
            }
            if (!file.exists() || file.length() <= 0L) {
                Log.w(TAG, "File APK tidak ada atau kosong: ${file.path}")
                return false
            }

            // GET_SIGNING_CERTIFICATES (API 28+) lebih lengkap; fallback
            // GET_SIGNATURES untuk API 24-27.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageArchiveInfo(file.path, flags)
            val signingInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info?.signingInfo
            } else {
                null
            }
            val signers: Array<Signature>? = if (signingInfo != null) {
                // Tolak APK dengan lebih dari satu penandatangan (trust ambigu).
                if (signingInfo.hasMultipleSigners()) null else signingInfo.apkContentsSigners
            } else {
                info?.signatures
            }
            if (signers.isNullOrEmpty()) {
                Log.w(TAG, "APK tidak memiliki sertifikat penandatangan")
                return false
            }

            val actual = MessageDigest.getInstance("SHA-256")
                .digest(signers[0].toByteArray())
                .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
            val matched = actual.equals(expected, ignoreCase = true)
            if (!matched) Log.w(TAG, "Sidik jari sertifikat APK tidak cocok: $actual")
            matched
        } catch (e: Exception) {
            Log.w(TAG, "Verifikasi tanda tangan APK gagal: ${e.message}")
            false
        }
    }

    /** Terima sidik jari heksadesimal dengan atau tanpa pemisah ':'. */
    private fun String.normalizeFingerprint(): String =
        trim().replace(":", "").replace("-", "").replace(" ", "").uppercase(Locale.US)

    /**
     * Bersihkan catatan rilis GitHub (markdown) untuk tampil polos di dialog:
     * buang judul/heading, penekanan tebal/miring, dan inline code.
     */
    private fun sanitizeReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("\\*\\*|__"), "")
            .replace(Regex("\\*|`"), "")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
