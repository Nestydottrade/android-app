package trade.nesty.app.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import trade.nesty.app.data.AppRelease
import trade.nesty.app.data.ReleaseNote
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private fun sha(bytes: ByteArray) = ApkCheck.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** A made-up APK's bytes: only their size and hash matter here. */
private fun apkBytes(n: Int = 1_200_000) = ByteArray(n) { (it * 31 + 7).toByte() }

private fun releaseOf(bytes: ByteArray, code: Int = 2, version: String = "0.1.1", url: String = "https://nesty.trade/app/nesty-$version.apk") =
    AppRelease(version = version, code = code, url = url, sha256 = sha(bytes), size = bytes.size.toLong())

/** Serves `bytes`, honouring a range unless told not to; counts the fetches. */
private class FakeSource(var bytes: ByteArray, var ranges: Boolean = true, var status: Int = 200, var cutAt: Int? = null) : ApkSource {
    var opens = 0
    override fun open(url: String, from: Long): ApkBody {
        opens++
        if (status != 200) return ApkBody(status, null)
        val start = if (ranges) from.toInt() else 0
        val end = cutAt ?: bytes.size
        val body = bytes.copyOfRange(start.coerceAtMost(end), end)
        return ApkBody(if (ranges && from > 0) 206 else 200, ByteArrayInputStream(body))
    }
}

private class MemoryDisk : UpdateDisk {
    val longs = HashMap<String, Long>()
    val flags = HashMap<String, Boolean>()
    override fun long(key: String) = longs[key]
    override fun putLong(key: String, value: Long) {
        longs[key] = value
    }
    override fun flag(key: String) = flags[key] ?: false
    override fun putFlag(key: String, value: Boolean) {
        flags[key] = value
    }
}

private const val KEY_A = "aa11"
private const val KEY_B = "bb22"

private class FakePhone(override val code: Int = 1, override val version: String = "0.1.0") : UpdatePhone {
    override val pkg = "trade.nesty.app"
    var meteredNow = false
    var meteredThrows = false
    /** What Android "reads" of a fetched file: by default Nesty at the file's code, signed with key A. */
    var facts: (File) -> ApkFacts? = { f -> ApkFacts(pkg, f.name.removePrefix("nesty-").removeSuffix(".apk").toLong(), listOf(KEY_A), listOf(KEY_A)) }
    override fun metered() = if (meteredThrows) throw SecurityException("no network permission") else meteredNow
    override fun factsOf(file: File) = facts(file)
    override fun installed() = ApkFacts(pkg, code.toLong(), listOf(KEY_A), listOf(KEY_A))
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Rig(
        val updater: Updater,
        val source: FakeSource,
        val phone: FakePhone,
        val disk: MemoryDisk,
        val dir: File,
        val installs: MutableList<File>,
        val ready: MutableList<AppRelease>,
        val asks: IntArray,
    )

    private fun TestScope.rig(
        release: () -> AppRelease?,
        bytes: ByteArray = apkBytes(),
        phone: FakePhone = FakePhone(),
        disk: MemoryDisk = MemoryDisk(),
        dir: File = tmp.newFolder(),
        clock: () -> Long = { 1_000_000L },
    ): Rig {
        val source = FakeSource(bytes)
        val installs = mutableListOf<File>()
        val ready = mutableListOf<AppRelease>()
        val asks = IntArray(1)
        val io = StandardTestDispatcher(testScheduler)
        val u = Updater(
            scope = this,
            ask = {
                asks[0]++
                release()
            },
            fetch = ApkFetch(source),
            phone = phone,
            dir = dir,
            disk = disk,
            install = { f -> installs += f },
            now = clock,
            onReady = { ready += it },
            io = io,
        )
        return Rig(u, source, phone, disk, dir, installs, ready, asks)
    }

    // ── which release is taken ─────────────────────────────────────────────

    @Test
    fun takesOnlyANewerWellFormedReleaseFromNestyTrade() {
        val good = releaseOf(apkBytes())
        assertNotNull(Releases.usable(good, installedCode = 1))
        assertNull("same version", Releases.usable(good, installedCode = 2))
        assertNull("older", Releases.usable(good, installedCode = 3))
        assertNull(Releases.usable(null, 1))
        assertNull("version shape", Releases.usable(good.copy(version = "1.0"), 1))
        assertNull("hash shape", Releases.usable(good.copy(sha256 = good.sha256.uppercase()), 1))
        assertNull("hash length", Releases.usable(good.copy(sha256 = "ab"), 1))
        assertNull("too small", Releases.usable(good.copy(size = 10), 1))
        assertNull("too large", Releases.usable(good.copy(size = 400_000_000), 1))
        assertNull("other host", Releases.usable(good.copy(url = "https://example.com/nesty.apk"), 1))
        assertNull("look-alike host", Releases.usable(good.copy(url = "https://nesty.trade.example.com/nesty.apk"), 1))
        assertNull("user part", Releases.usable(good.copy(url = "https://nesty.trade@example.com/nesty.apk"), 1))
        assertNull("plain http", Releases.usable(good.copy(url = "http://nesty.trade/app/nesty.apk"), 1))
        assertNull("not a url", Releases.usable(good.copy(url = "nesty.apk"), 1))
        // A debug build's test server: its own host, over plain http.
        val test = good.copy(url = "http://10.0.2.2:8799/app/nesty.apk")
        assertNotNull(Releases.usable(test, 1, host = "10.0.2.2"))
        assertNull(Releases.usable(test, 1))
    }

    @Test
    fun keepsAFewShortNotes() {
        val notes = List(9) { ReleaseNote(if (it == 0) " " else "Note $it " + "x".repeat(80), "Line $it") }
        val r = Releases.usable(releaseOf(apkBytes()).copy(notes = notes), 1)!!
        assertEquals(Releases.MAX_NOTES, r.notes.size)
        assertTrue(r.notes.none { it.title.isBlank() })
        assertTrue(r.notes.all { it.title.length <= 60 })
        assertEquals("Note 1", r.notes[0].title.take(6))
    }

    @Test
    fun saysTheHashAndSizeAsTheBoardDoes() {
        val h = "9f2c81d0" + "0".repeat(48) + "4be7e41a"
        assertEquals("9f2c 81d0 … 4be7 e41a", Releases.shortHash(h))
        assertEquals("21.4 MB", Releases.sizeText(21_400_000))
    }

    // ── fetching ───────────────────────────────────────────────────────────

    @Test
    fun fetchesAWholeFileAndGoesOnFromAPart() {
        val bytes = apkBytes()
        val part = File(tmp.newFolder(), "nesty-2.apk.part")
        val source = FakeSource(bytes, cutAt = 400_000)
        val fetch = ApkFetch(source)
        try {
            fetch.fetch("https://nesty.trade/a.apk", part, bytes.size.toLong())
            fail("a cut download must say so")
        } catch (_: IOException) {
        }
        assertEquals(400_000L, part.length())
        source.cutAt = null
        val seen = mutableListOf<Long>()
        assertEquals(bytes.size.toLong(), fetch.fetch("https://nesty.trade/a.apk", part, bytes.size.toLong(), progress = { seen += it }))
        assertEquals(sha(bytes), ApkCheck.sha256(part))
        assertEquals(400_000L, seen.first())
        assertEquals(bytes.size.toLong(), seen.last())
    }

    @Test
    fun startsAgainWhenTheServerSendsTheWholeFile() {
        val bytes = apkBytes()
        val part = File(tmp.newFolder(), "p")
        part.writeBytes(bytes.copyOfRange(0, 1000))
        ApkFetch(FakeSource(bytes, ranges = false)).fetch("u", part, bytes.size.toLong())
        assertEquals(sha(bytes), ApkCheck.sha256(part))
    }

    @Test
    fun refusesMoreThanPublishedAndARefusedRange() {
        val bytes = apkBytes()
        val part = File(tmp.newFolder(), "p")
        try {
            ApkFetch(FakeSource(bytes)).fetch("u", part, bytes.size - 10L)
            fail("a larger file must be refused")
        } catch (_: IOException) {
        }
        assertFalse(part.exists())
        part.writeBytes(ByteArray(10))
        try {
            ApkFetch(FakeSource(bytes, status = 416)).fetch("u", part, bytes.size.toLong())
            fail("a refused range must be said")
        } catch (_: IOException) {
        }
        assertFalse("the part that no longer fits goes", part.exists())
        try {
            ApkFetch(FakeSource(bytes, status = 302)).fetch("u", part, bytes.size.toLong())
            fail("a redirect is not followed")
        } catch (_: IOException) {
        }
    }

    // ── checking ───────────────────────────────────────────────────────────

    @Test
    fun theSignerMustBeTheInstalledOne() {
        val pkg = "trade.nesty.app"
        val same = ApkFacts(pkg, 2, listOf(KEY_A), listOf(KEY_A))
        val other = ApkFacts(pkg, 2, listOf(KEY_B), listOf(KEY_B))
        val rotated = ApkFacts(pkg, 2, listOf(KEY_B), listOf(KEY_A, KEY_B))
        assertTrue(ApkCheck.sameSigner(listOf(KEY_A), same))
        assertFalse(ApkCheck.sameSigner(listOf(KEY_A), other))
        assertTrue("a rotated key that proves the old one", ApkCheck.sameSigner(listOf(KEY_A), rotated))
        assertFalse(ApkCheck.sameSigner(emptyList(), same))

        val installed = ApkFacts(pkg, 1, listOf(KEY_A), listOf(KEY_A))
        assertNull(ApkCheck.refusal(same, pkg, 2, installed))
        assertEquals("The file is not signed with Nesty's key.", ApkCheck.refusal(other, pkg, 2, installed))
        assertEquals("The file is not Nesty.", ApkCheck.refusal(same.copy(pkg = "com.other"), pkg, 2, installed))
        assertEquals("The file is not the version published.", ApkCheck.refusal(same.copy(code = 3), pkg, 2, installed))
        assertEquals("Android could not read the file.", ApkCheck.refusal(null, pkg, 2, installed))
        // Signers Android could not read are left to Android's own check on install.
        assertNull(ApkCheck.refusal(same.copy(signers = emptyList(), history = emptyList()), pkg, 2, installed))
    }

    // ── the updater ────────────────────────────────────────────────────────

    @Test
    fun nothingNewerSaysSoAndAFailedAskSaysThat() = runTest {
        val none = rig({ null })
        none.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(UpdateState.Newest, none.updater.state.value)

        val down = rig({ throw IOException("offline") })
        down.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(UpdateState.Unreachable, down.updater.state.value)
    }

    @Test
    fun fetchesOnWifiChecksAndSaysReadyOnce() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        val g = rig({ r }, bytes)
        g.updater.checkSoon()
        advanceUntilIdle()
        val s = g.updater.state.value
        assertTrue("$s", s is UpdateState.Ready)
        s as UpdateState.Ready
        assertTrue(s.signerRead)
        assertEquals(File(g.dir, "nesty-2.apk"), s.file)
        assertEquals(listOf(r), g.ready)
        // Asked again later: the file on disk is checked again, not fetched again, and not said twice.
        g.updater.retry()
        advanceUntilIdle()
        assertTrue(g.updater.state.value is UpdateState.Ready)
        assertEquals(1, g.source.opens)
        assertEquals(1, g.ready.size)
    }

    @Test
    fun onMobileDataItWaitsForTheUpdateScreen() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        val g = rig({ r }, bytes, FakePhone().apply { meteredNow = true })
        g.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(UpdateState.Out(r), g.updater.state.value)
        assertEquals(0, g.source.opens)
        g.updater.look(fetchNow = true)
        advanceUntilIdle()
        assertTrue(g.updater.state.value is UpdateState.Ready)
    }

    @Test
    fun asksBySelfAtMostEverySixHours() = runTest {
        var t = 10_000_000L
        val g = rig({ null }, clock = { t })
        g.updater.checkSoon()
        advanceUntilIdle()
        g.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(1, g.asks[0])
        t += Updater.CHECK_EVERY_MS
        g.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(2, g.asks[0])
        // The menu asks again after a minute.
        t += Updater.LOOK_EVERY_MS
        g.updater.look(fetchNow = false)
        advanceUntilIdle()
        assertEquals(3, g.asks[0])
    }

    @Test
    fun aFailureInsideTheUpdaterNeverEndsTheApp() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        // runTest fails on any exception that escapes into its scope, as the app would end.
        val g = rig({ r }, bytes, FakePhone().apply { meteredThrows = true })
        g.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed(r, Updater.STUCK), g.updater.state.value)
    }

    @Test
    fun aFileThatDoesNotMatchIsDeleted() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes).copy(sha256 = sha(byteArrayOf(1, 2, 3)))
        val g = rig({ r }, bytes)
        g.updater.checkSoon()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed(r, Updater.MISMATCH), g.updater.state.value)
        assertTrue(g.dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFileSignedByAnotherKeyIsDeleted() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        val phone = FakePhone().apply { facts = { ApkFacts(pkg, 2, listOf(KEY_B), listOf(KEY_B)) } }
        val g = rig({ r }, bytes, phone)
        g.updater.checkSoon()
        advanceUntilIdle()
        val s = g.updater.state.value
        assertTrue("$s", s is UpdateState.Failed && s.why.startsWith("The file is not signed with Nesty's key."))
        assertTrue(g.dir.listFiles().orEmpty().isEmpty())
        assertTrue(g.ready.isEmpty())
    }

    @Test
    fun installHandsTheCheckedFileToAndroid() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        val g = rig({ r }, bytes)
        g.updater.checkSoon()
        advanceUntilIdle()
        g.updater.installNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Installing(r), g.updater.state.value)
        assertEquals(listOf(File(g.dir, "nesty-2.apk")), g.installs)

        // The user said no to Android's question: offered again as it was.
        g.updater.installEnded(InstallEnd.ABORTED)
        assertTrue(g.updater.state.value is UpdateState.Ready)

        // Android refused it as signed differently: it goes.
        g.updater.installNow()
        advanceUntilIdle()
        g.updater.installEnded(InstallEnd.CONFLICT)
        val s = g.updater.state.value
        assertTrue("$s", s is UpdateState.Failed)
        assertFalse(File(g.dir, "nesty-2.apk").exists())
    }

    @Test
    fun aFileChangedOnDiskIsCheckedBeforeInstall() = runTest {
        val bytes = apkBytes()
        val r = releaseOf(bytes)
        val g = rig({ r }, bytes)
        g.updater.checkSoon()
        advanceUntilIdle()
        File(g.dir, "nesty-2.apk").writeBytes(bytes.copyOf().also { it[5] = 0 })
        g.updater.installNow()
        advanceUntilIdle()
        assertEquals(UpdateState.Failed(r, Updater.MISMATCH), g.updater.state.value)
        assertTrue(g.installs.isEmpty())
    }

    @Test
    fun theNewVersionSaysItIsInstalledUntilSeen() = runTest {
        val disk = MemoryDisk()
        val dir = tmp.newFolder()
        // First run ever: nothing to say.
        assertNull(rig({ null }, disk = disk, dir = dir).updater.justUpdated)
        File(dir, "nesty-2.apk").writeBytes(ByteArray(3))
        // Version 2 starts after version 1.
        val second = rig({ null }, phone = FakePhone(code = 2, version = "0.1.1"), disk = disk, dir = dir)
        assertEquals("0.1.1", second.updater.justUpdated)
        assertTrue("the old files go", dir.listFiles().orEmpty().isEmpty())
        // Android ended the app before it was opened: still said at the next start.
        assertEquals("0.1.1", rig({ null }, phone = FakePhone(code = 2, version = "0.1.1"), disk = disk, dir = dir).updater.justUpdated)
        second.updater.seenUpdated()
        assertNull(second.updater.justUpdated)
        assertNull(rig({ null }, phone = FakePhone(code = 2, version = "0.1.1"), disk = disk, dir = dir).updater.justUpdated)
    }
}
