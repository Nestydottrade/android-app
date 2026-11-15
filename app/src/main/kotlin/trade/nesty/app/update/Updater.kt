package trade.nesty.app.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import trade.nesty.app.data.AppRelease
import java.io.File
import kotlin.coroutines.coroutineContext

/** Where the updater keeps its few facts: the phone's preferences, or memory in tests. */
interface UpdateDisk {
    fun long(key: String): Long?
    fun putLong(key: String, value: Long)
    fun flag(key: String): Boolean
    fun putFlag(key: String, value: Boolean)
}

class PrefsUpdateDisk(context: Context) : UpdateDisk {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)
    override fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun flag(key: String): Boolean = prefs.getBoolean(key, false)
    override fun putFlag(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

/** What the updater needs of the phone; faked in tests. */
interface UpdatePhone {
    val pkg: String
    val code: Int
    val version: String

    /** Whether the network now costs the user: mobile data, or none at all. */
    fun metered(): Boolean
    fun factsOf(file: File): ApkFacts?
    fun installed(): ApkFacts?
}

/** How an install handed to Android ended, when the app is still there to hear it. */
enum class InstallEnd { ABORTED, BLOCKED, CONFLICT, INCOMPATIBLE, INVALID, STORAGE, OTHER }

sealed interface UpdateState {
    /** Not asked yet. */
    data object Unknown : UpdateState

    data object Asking : UpdateState

    /** nesty.trade has nothing newer than this build. */
    data object Newest : UpdateState

    /** nesty.trade could not be reached. */
    data object Unreachable : UpdateState

    /** A newer version, not fetched yet: it waits for Wi-Fi, or for the Update screen. */
    data class Out(override val release: AppRelease) : UpdateState

    data class Fetching(override val release: AppRelease, val bytes: Long) : UpdateState

    /** Fetched and checked. `signerRead`: Android read its signer, and it is Nesty's. */
    data class Ready(override val release: AppRelease, val file: File, val signerRead: Boolean) : UpdateState

    data class Failed(override val release: AppRelease, val why: String) : UpdateState

    /** Handed to Android, which asks the user (or, with the switch on, may not). */
    data class Installing(override val release: AppRelease) : UpdateState

    /** The newer version this state is about, if any. */
    val release: AppRelease? get() = null
}

/**
 * The app's own updater. It asks nesty.trade for the newest release, fetches the APK in the
 * background (on Wi-Fi by itself, on any network once the Update screen is open), checks its
 * size, hash, package, version and signer, then offers it; Android installs it. Nothing it
 * fetches ever runs inside the app: an update is a new APK, installed as a whole.
 */
class Updater(
    private val scope: CoroutineScope,
    /** GET /app: the release, or null when none is published. Throws when unreachable. */
    private val ask: suspend () -> AppRelease?,
    private val fetch: ApkFetch,
    private val phone: UpdatePhone,
    /** Where fetched APKs are kept: the app's own cache. */
    private val dir: File,
    private val disk: UpdateDisk,
    /** Hands a checked APK to Android (PackageInstaller); `quiet` asks it to skip its question. */
    private val install: (File, Boolean) -> Unit,
    private val host: String = Releases.HOST,
    private val now: () -> Long = System::currentTimeMillis,
    /** A verified update is ready: said once per version (a notification while Nesty is away). */
    private val onReady: (AppRelease) -> Unit = {},
    /** Where files are read and written; a test's own dispatcher in tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state

    /** The version Nesty was just updated to, until its screen has said so. */
    var justUpdated: String? = null
        private set

    /** Install without Android's question (Android 12 and later; some phones ask anyway). */
    var quiet: Boolean
        get() = disk.flag(QUIET)
        set(on) = disk.putFlag(QUIET, on)

    private var job: Job? = null
    /** When nesty.trade last answered; null before the first answer. */
    private var askedAt: Long? = null
    /** The checked file last handed to Android, offered again if the user says no there. */
    private var handed: UpdateState.Ready? = null
    private var known: AppRelease? = null

    /** The Update screen asked for the file: it is fetched on mobile data too. */
    private var wanted = false

    init {
        // A new version's first start; said until its screen has shown it, even if Android ends
        // the app before it is opened.
        val last = disk.long(LAST_RUN)
        if (last != phone.code.toLong()) {
            if (last != null && last < phone.code) disk.putLong(UPDATED_TO, phone.code.toLong())
            disk.putLong(LAST_RUN, phone.code.toLong())
            clearFiles(keep = null)
        }
        if (disk.long(UPDATED_TO) == phone.code.toLong()) justUpdated = phone.version
    }

    fun seenUpdated() {
        justUpdated = null
        disk.putLong(UPDATED_TO, 0)
    }

    /** On start and on each return to the app: asks at most every few hours. */
    fun checkSoon() {
        if (job?.isActive != true && olderThan(CHECK_EVERY_MS)) start(ask = true)
    }

    private fun olderThan(ms: Long) = askedAt.let { it == null || now() - it >= ms }

    /**
     * The menu or the Update screen is open: asks again unless it just did. `fetchNow`: the
     * Update screen, which fetches a newer version on any network.
     */
    fun look(fetchNow: Boolean) {
        if (fetchNow) wanted = true
        val s = _state.value
        val stale = olderThan(LOOK_EVERY_MS)
        when {
            job?.isActive == true -> return
            stale || s is UpdateState.Unknown || s is UpdateState.Unreachable -> start(ask = true)
            fetchNow && s is UpdateState.Out -> start(ask = false)
        }
    }

    /** Try again after a failure, asking nesty.trade afresh. */
    fun retry() {
        wanted = true
        if (job?.isActive != true) start(ask = true)
    }

    private fun start(ask: Boolean) {
        job = scope.launch { guarded { run(ask) } }
    }

    /** Whatever goes wrong in an update stays in the update: it never ends the app. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = _state.value.release ?: known
            _state.value = if (r != null) UpdateState.Failed(r, STUCK) else UpdateState.Unreachable
        }
    }

    private suspend fun run(askNow: Boolean) {
        val r = if (askNow || known == null) {
            if (_state.value.release == null) _state.value = UpdateState.Asking
            try {
                Releases.usable(ask(), phone.code, host).also {
                    known = it
                    askedAt = now()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable: what was known before stays, else the screen says so.
                if (known == null) _state.value = UpdateState.Unreachable
                return
            }
        } else {
            known
        }
        if (r == null) {
            _state.value = UpdateState.Newest
            clearFiles(keep = null)
            return
        }
        clearFiles(keep = r.code)
        val done = fileOf(r)
        if (done.isFile) {
            _state.value = verify(r, done)
            if (_state.value is UpdateState.Ready) return
        }
        if (!wanted && phone.metered()) {
            _state.value = UpdateState.Out(r)
            return
        }
        get(r)
    }

    private suspend fun get(r: AppRelease) {
        val part = File(dir, "nesty-${r.code}.apk.part")
        _state.value = UpdateState.Fetching(r, if (part.isFile) part.length() else 0)
        val ctx = coroutineContext
        try {
            withContext(io) {
                fetch.fetch(r.url, part, r.size, progress = { b -> _state.value = UpdateState.Fetching(r, b) }, goOn = { ctx.isActive })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(r, STOPPED)
            return
        }
        if (!ctx.isActive) return
        val sha = withContext(io) { ApkCheck.sha256(part) }
        if (sha != r.sha256) {
            part.delete()
            _state.value = UpdateState.Failed(r, MISMATCH)
            return
        }
        val done = fileOf(r)
        done.delete()
        if (!part.renameTo(done)) {
            part.delete()
            _state.value = UpdateState.Failed(r, STOPPED)
            return
        }
        val s = verify(r, done)
        _state.value = s
        if (s is UpdateState.Ready && disk.long(NOTIFIED) != r.code.toLong()) {
            disk.putLong(NOTIFIED, r.code.toLong())
            onReady(r)
        }
    }

    /** Size, hash, package, version and signer of a fetched file, checked again; a bad file goes. */
    private suspend fun verify(r: AppRelease, file: File): UpdateState = withContext(io) {
        if (file.length() != r.size || ApkCheck.sha256(file) != r.sha256) {
            file.delete()
            return@withContext UpdateState.Failed(r, MISMATCH)
        }
        val facts = phone.factsOf(file)
        val why = ApkCheck.refusal(facts, phone.pkg, r.code, phone.installed())
        if (why != null) {
            file.delete()
            return@withContext UpdateState.Failed(r, "$why Nesty deleted it.")
        }
        UpdateState.Ready(r, file, signerRead = facts?.signers?.isNotEmpty() == true)
    }

    /** "Install update": the file is checked once more, then handed to Android. */
    fun installNow() {
        val s = _state.value as? UpdateState.Ready ?: return
        if (job?.isActive == true) return
        job = scope.launch { guarded { handOver(s) } }
    }

    private suspend fun handOver(s: UpdateState.Ready) {
        val again = verify(s.release, s.file)
        if (again !is UpdateState.Ready) {
            _state.value = again
            return
        }
        handed = again
        _state.value = UpdateState.Installing(s.release)
        try {
            withContext(io) { install(s.file, quiet) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(s.release, "Android could not start the install. Try again.")
        }
    }

    /** Android's answer, when the app is still running to hear it (a success ends the app). */
    fun installEnded(end: InstallEnd) {
        val s = _state.value as? UpdateState.Installing ?: return
        val r = s.release
        val file = fileOf(r)
        _state.value = when (end) {
            InstallEnd.ABORTED -> handed?.takeIf { file.isFile && it.release == r } ?: UpdateState.Out(r)
            InstallEnd.CONFLICT -> {
                file.delete()
                UpdateState.Failed(r, "Android refused it: it is signed differently from the Nesty on this phone. Nesty deleted it.")
            }
            InstallEnd.INVALID -> {
                file.delete()
                UpdateState.Failed(r, "Android found the file broken. Nesty deleted it.")
            }
            InstallEnd.STORAGE -> UpdateState.Failed(r, "There is not enough room on this phone. Free some space and try again.")
            InstallEnd.INCOMPATIBLE -> UpdateState.Failed(r, "Android says this phone cannot run this version.")
            InstallEnd.BLOCKED -> UpdateState.Failed(r, "Android blocked the install.")
            InstallEnd.OTHER -> UpdateState.Failed(r, "Android did not install it. Try again.")
        }
    }

    private fun fileOf(r: AppRelease) = File(dir, "nesty-${r.code}.apk")

    /** Every fetched file but the one for `keep`'s version. */
    private fun clearFiles(keep: Int?) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (keep != null && (f.name == "nesty-$keep.apk" || f.name == "nesty-$keep.apk.part")) continue
            f.delete()
        }
    }

    companion object {
        /** By itself, nesty.trade is asked at most every 6 hours; an open menu asks at most each minute. */
        const val CHECK_EVERY_MS = 6 * 60 * 60_000L
        const val LOOK_EVERY_MS = 60_000L

        private const val LAST_RUN = "lastRun"
        private const val UPDATED_TO = "updatedTo"
        private const val NOTIFIED = "notified"
        private const val QUIET = "quiet"

        const val STOPPED = "The download stopped. Check your connection and try again."
        const val MISMATCH = "The file did not match the published fingerprint, so Nesty deleted it."
        const val STUCK = "The update stopped. Try again."
    }
}
