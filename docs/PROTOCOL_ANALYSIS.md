## Antigravity Mobile – Technical Architecture Blueprint  
**Package:** `com.antigravity.mobile`  
**Target SDK:** 34 | Min SDK: 21  
**Core Stack:** Kotlin 1.9, Jetpack Compose 1.6 + Material‑3, **Aptoide Clean‑MVP**, **Reactive Streams** (Kotlin Flow + Coroutines), **Repository pattern**, **Room/SQLite**, **OkHttp 4.x** (SSE/WebSocket), **Epoxy 4.x** (fallback for legacy list screens), **Hilt 2.48** (DI), **Coil** (image loading), **LeakCanary 2.14** (runtime leak detection).

---

## 1. Presentation Contract & Lifecycle  

### 1.1. MVP Contract (Kotlin)

```kotlin
// ── View ─────────────────────────────────────────────────────────────────────
interface TokenView : MvpView {
    /** Render the whole list – called on the UI thread */
    fun renderTokens(tokens: List<TokenUiModel>)

    /** Incremental diff update (Epoxy) */
    fun submitDiff(diff: TokenDiff)

    /** Show a transient UI message */
    fun showSnack(message: String, action: (() -> Unit)? = null)

    /** Show/hide loading indicator */
    fun setLoading(isLoading: Boolean)

    /** Open the server‑config modal */
    fun openServerConfig(current: ServerConfig)
}

// ── Presenter ───────────────────────────────────────────────────────────────
interface TokenPresenter : MvpPresenter<TokenView> {
    /** Called from View.onStart() – attaches UI & starts streams */
    fun attach(view: TokenView)

    /** Called from View.onStop() – detaches UI & disposes streams */
    fun detach()

    /** UI actions ----------------------------------------------------------- */
    fun onRefreshRequested()
    fun onTokenClicked(id: String)
    fun onServerConfigChanged(newConfig: ServerConfig)
}
```

*`MvpView` and `MvpPresenter` are thin base interfaces that only expose `onDestroy()` for the view and `clear()` for the presenter.*

### 1.2. Presenter Implementation (Lifecycle‑Safe)

```kotlin
@Singleton @Inject constructor(
    private val tokenRepository: TokenRepository,
    private val schedulerProvider: SchedulerProvider,
    private val serverConfigProvider: ServerConfigProvider
) : TokenPresenter, CoroutineScope {

    // ---- CoroutineScope ----------------------------------------------------
    private val job = SupervisorJob()
    override val coroutineContext = job + schedulerProvider.main

    // ---- View reference – **WeakReference** to avoid leaks -----------------
    private var viewRef: WeakReference<TokenView>? = null
    private val view get() = viewRef?.get()

    // ---- Stream handling ----------------------------------------------------
    private var streamJob: Job? = null

    // -----------------------------------------------------------------------
    override fun attach(view: TokenView) {
        viewRef = WeakReference(view)
        // UI is now attached – start the reactive pipeline
        streamJob = launch {
            tokenRepository.tokensFlow()
                .distinctUntilChanged()
                .collect { tokens -> view?.renderTokens(tokens.map(::toUiModel)) }
        }
        // Kick‑off a manual refresh to prime the cache
        onRefreshRequested()
    }

    override fun detach() {
        // Cancel the stream *before* dropping the view reference
        streamJob?.cancel()
        streamJob = null
        viewRef?.clear()
        viewRef = null
    }

    override fun onDestroy() {
        // Called from Activity/Fragment onDestroy()
        job.cancel()
    }

    // -----------------------------------------------------------------------
    override fun onRefreshRequested() {
        view?.setLoading(true)
        launch {
            try {
                tokenRepository.refreshFromNetwork()
            } catch (e: Exception) {
                view?.showSnack("Refresh failed: ${e.localizedMessage}")
            } finally {
                view?.setLoading(false)
            }
        }
    }

    override fun onTokenClicked(id: String) {
        // Business logic – e.g. navigate to detail screen
    }

    override fun onServerConfigChanged(newConfig: ServerConfig) {
        serverConfigProvider.update(newConfig)
        // Re‑connect the SSE/WebSocket with the new endpoint
        tokenRepository.reconnectStream()
    }

    // -----------------------------------------------------------------------
    private fun toUiModel(entity: TokenEntity): TokenUiModel = TokenUiModel(
        id = entity.id,
        name = entity.name,
        status = entity.status,
        // … map other fields
    )
}
```

#### Why this prevents leaks  

| Problem | Mitigation |
|---------|------------|
| **View retained after `onStop()`** | `WeakReference` + explicit `detach()` cancels the Flow subscription before the reference is cleared. |
| **Long‑running coroutines outliving the view** | All coroutines are children of `job` (a `SupervisorJob`). `onDestroy()` cancels the whole scope. |
| **Configuration changes** | The presenter is **Hilt‑scoped to `@ActivityRetainedScope`** (or `@ViewModelScope` if you prefer a `ViewModel`‑backed presenter). The same instance survives rotation, while the view reference is re‑attached in `onStart()`. |
| **Memory‑intensive Epoxy models** | UI models are plain data classes (`TokenUiModel`). Epoxy controllers receive immutable diff lists – no view references are stored in the presenter. |

### 1.3. Activity / Fragment Boilerplate

```kotlin
@AndroidEntryPoint
class TokenActivity : AppCompatActivity(), TokenView {

    @Inject lateinit var presenter: TokenPresenter

    private val epoxyController = TokenEpoxyController(
        onItemClicked = presenter::onTokenClicked
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_token)
        // Edge‑to‑edge insets handled by WindowInsetsController (see UI section)
        epoxyRecyclerView.setController(epoxyController)
    }

    override fun onStart() {
        super.onStart()
        presenter.attach(this)
    }

    override fun onStop() {
        presenter.detach()
        super.onStop()
    }

    override fun onDestroy() {
        presenter.onDestroy()
        super.onDestroy()
    }

    // ---- TokenView ---------------------------------------------------------
    override fun renderTokens(tokens: List<TokenUiModel>) {
        epoxyController.setData(tokens)
    }

    override fun submitDiff(diff: TokenDiff) = epoxyController.submitDiff(diff)

    override fun showSnack(message: String, action: (() -> Unit)?) {
        Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG)
            .setAction(if (action != null) "Retry" else null) { action?.invoke() }
            .show()
    }

    override fun setLoading(isLoading: Boolean) {
        progressBar.isVisible = isLoading
    }

    override fun openServerConfig(current: ServerConfig) {
        ServerConfigDialogFragment.newInstance(current)
            .show(supportFragmentManager, "ServerConfig")
    }
}
```

---

## 2. Resilient Streaming & Reconnection  

### 2.1. Choice of Transport  

| Use‑case | Preferred transport | Reason |
|----------|---------------------|--------|
| **Low‑latency token updates** (≤ 100 ms) | **WebSocket** (binary or JSON) | Full‑duplex, back‑pressure via `Flow` |
| **Server‑sent events (SSE)** when only server → client | **OkHttp EventSource** (SSE) | Simpler, automatic reconnection support (but we’ll add our own back‑off) |

Both are wrapped behind a **`TokenStream`** interface so the rest of the app is agnostic.

```kotlin
interface TokenStream {
    /** Emits raw JSON payloads from the server */
    fun events(): Flow<String>

    /** Force a reconnection (e.g. after config change) */
    suspend fun reconnect()
}
```

### 2.2. Implementation – WebSocket (OkHttp)

```kotlin
@Singleton @Inject constructor(
    @Named("streamOkHttp") private val client: OkHttpClient,
    private val serverConfigProvider: ServerConfigProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TokenStream {

    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    private val reconnectionLock = Mutex()

    override fun events(): Flow<String> = _events.asSharedFlow()

    init {
        // Auto‑connect on creation
        CoroutineScope(dispatcher).launch { connect() }
    }

    private suspend fun connect() {
        val request = Request.Builder()
            .url(serverConfigProvider.current().websocketUrl)
            .addHeader("Authorization", "Bearer ${serverConfigProvider.current().authToken}")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                _events.tryEmit(text) // non‑blocking; overflow handled by buffer
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure", t)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                scheduleReconnect()
            }
        })
    }

    /** Public API – used when the user changes the server config */
    override suspend fun reconnect() {
        reconnectionLock.withLock {
            webSocket?.cancel()
            _events.resetReplayCache()
            connect()
        }
    }

    /** Exponential back‑off with jitter (max 30 s) */
    private fun scheduleReconnect() {
        CoroutineScope(dispatcher).launch {
            var attempt = 0
            while (isActive) {
                val delayMs = backoffDelay(attempt++)
                Log.i(TAG, "Reconnecting in $delayMs ms (attempt $attempt)")
                delay(delayMs)
                try {
                    reconnectionLock.withLock { connect() }
                    break // success → exit loop
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $attempt failed", e)
                }
            }
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        // 500 ms × 2^attempt, capped at 30 s, plus ±10 % jitter
        val base = 500L shl attempt.coerceAtMost(5) // 500, 1k, 2k, 4k, 8k, 16k, 30k cap
        val jitter