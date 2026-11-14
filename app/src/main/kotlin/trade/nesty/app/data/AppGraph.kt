package trade.nesty.app.data

import android.content.Context
import okhttp3.OkHttpClient
import trade.nesty.app.move.HttpRelay
import trade.nesty.app.move.MoveStore
import trade.nesty.app.move.Mover
import trade.nesty.app.move.PrefsMoveDisk
import trade.nesty.app.BuildConfigLite
import trade.nesty.app.vault.AndroidKeyWrap
import trade.nesty.app.vault.FileVaultStore
import trade.nesty.app.vault.FingerprintKey
import trade.nesty.app.vault.Vault
import java.util.concurrent.TimeUnit

/** The app's few long-lived pieces. */
object AppGraph {
    lateinit var api: NestyApi
        private set
    lateinit var settings: Settings
        private set
    lateinit var imageHttp: OkHttpClient
        private set
    lateinit var session: WalletSession
        private set
    lateinit var fingerprint: FingerprintKey
        private set
    lateinit var balances: BalanceModel
        private set
    lateinit var positions: PositionStore
        private set
    lateinit var trading: Trading
        private set
    lateinit var mover: Mover
        private set

    fun init(context: Context) {
        val agent = "Nesty-Android/${BuildConfigLite.VERSION}"
        val base = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build())
            }
            .build()
        // A stream sends a comment every 15 seconds; 45 seconds of silence means it is gone.
        val stream = base.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
        imageHttp = base.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
        api = NestyApi(base, stream)
        settings = Settings(context.applicationContext)
        // The keys: a private file, sealed by the password and by a key the phone never gives out.
        // Fingerprint unlock keeps the vault's key under a second phone key that only a finger opens.
        fingerprint = FingerprintKey(context.applicationContext)
        session = WalletSession(Vault(FileVaultStore(context.applicationContext), AndroidKeyWrap()), settings, fingerprint)
        positions = PositionStore(context.applicationContext)
        // A wallet's balance names the coins it has traded: EVM chains cannot list what it holds.
        balances = BalanceModel(api, session) { wallet -> positions.coinsOf(wallet.evm) + positions.coinsOf(wallet.sol) }
        trading = Trading(api, session, positions, base).also { it.balances = balances }
        // Moves of funds between chains and out of One Balance, kept on the phone; bridge quotes are
        // asked of the bridge from the phone. A move the app was closed in is followed again.
        mover = Mover(session, trading, balances, settings, MoveStore(PrefsMoveDisk(context.applicationContext)), HttpRelay(base), base)
        mover.resume()
    }
}
