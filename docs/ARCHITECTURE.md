
# Antigravity Mobile: Enterprise Architecture Blueprint
*Reverse Engineered from Production APK (cm.aptoide.pt v9.22.5.3) via Groq Swarm*

---

## 1. Executive Summary & Telemetry
- **Decompiled Target:** `cm.aptoide.pt`
- **Total Classes Analyzed:** 23415
- **Presentation Pattern:** Clean MVP / MVI with Reactive Streams
- **Reactive Engine:** RxJava2 / Kotlin Coroutines Flow hybrid
- **Persistence Engine:** AndroidX Room Database + DataStore
- **Dependency Injection:** Dagger 2 Component Scoping
- **Network Engine:** OkHttp3 + Retrofit2 + WebSocket Streaming

---

## 2. Agent 1: Process Model & Manifest Analysis
## 1. High‑level view of the **Aptoide** (cm.aptoide.pt) process model  

| Component | Declared `android:process` | Typical role | Why it lives in its own process |
|-----------|---------------------------|--------------|---------------------------------|
| **Default (no process attribute)** – UI stack (MainActivity, fragments, view‑models, in‑app navigation) | **`cm.aptoide.pt`** (the main app process) | Handles all foreground UI, user interaction, network calls that need to run on the UI thread, and most business‑logic services that are tightly coupled to the UI. | Keeps the UI responsive; the OS can kill the UI process without affecting background work. |
| **`:filedownloader`** – `FileDownloadService` | **`cm.aptoide.pt:filedownloader`** | Dedicated downloader that runs long‑running HTTP/HTTPS fetches, supports pause/resume, parallel chunking, and writes directly to external storage. | Isolates heavy I/O, prevents ANR in the UI process, and allows the OS to keep the downloader alive even if the UI process is reclaimed. |
| **`:room`** – `MultiInstanceInvalidationService` (Room) | **`cm.aptoide.pt:room`** (implicitly created by the `android:process` attribute on the service) | Provides cross‑process invalidation for the Room database (used by the “multi‑instance” feature that lets several processes share the same SQLite DB). | Guarantees that DB invalidation callbacks are delivered even when the UI process is not running. |
| **`:sentry`** – `SentryInitProvider` (ContentProvider) | **`cm.aptoide.pt:sentry`** (via `android:process` on the provider) | Initializes the Sentry SDK early (before any `Application` code) and may run crash‑reporting background work. | Allows crash‑reporting to stay alive after the UI process is killed, and isolates any heavy native initialization from the UI thread. |
| **`:jobscheduler`** – `JobInfoSchedulerService` | **`cm.aptoide.pt:jobscheduler`** | Schedules periodic jobs (e.g., sync, update checks, cache clean‑up) via `JobScheduler`. | Keeps the job‑execution environment separate; the OS can restart this process on demand without re‑launching the UI. |

> **Bottom line:** Aptoide follows a classic “split‑process” pattern: a **single foreground UI process** plus **named background processes** for I/O‑heavy, database‑invalidating, and crash‑reporting work. This reduces the risk of **ANR** and **OOM** in the UI, while still satisfying Android’s modern background‑execution limits.

---

## 2. Startup & Initialization Sequence  

Below is the **chronological flow** from device boot / user tap to a fully‑functional UI, annotated with the manifest‑derived hooks that fire.

| Step | Trigger | Component(s) involved | What happens (manifest‑driven) |
|------|---------|-----------------------|--------------------------------|
| **0 – System boot** | `BOOT_COMPLETED` broadcast (declared permission `RECEIVE_BOOT_COMPLETED`) | `BootCompletedReceiver` (if present – not listed but typical) | The receiver can start the **JobInfoSchedulerService** or schedule a `JobScheduler` job to pre‑warm the download manager. |
| **1 – User launches Aptoide** | Intent `android.intent.action.MAIN` + `CATEGORY.LAUNCHER` targeting `cm.aptoide.pt.view.MainActivity` | **Main UI process** (`cm.aptoide.pt`) | `Application.onCreate()` runs → Dagger/Hilt component graph built, logging, analytics, and **SentryInitProvider** (content‑provider) is instantiated **before** `Application` (because providers are created first). |
| **2 – Early SDK init** | `SentryInitProvider` (process `:sentry`) | **`:sentry`** process | Provider’s `onCreate()` registers the Sentry SDK, sets up uncaught‑exception handlers, and may spawn a **background thread** that stays alive across process restarts. |
| **3 – UI thread continues** | `MainActivity.onCreate()` | UI process | Sets theme, inflates layout, starts **NavigationComponent**, binds to **Room** (which will spin up `MultiInstanceInvalidationService` in `:room` if needed). |
| **4 – Database invalidation service** | First call to `Room.databaseBuilder(...).build()` with `setMultiInstanceInvalidation(true)` | **`:room`** process (`MultiInstanceInvalidationService`) | Android creates a **bound service** that lives in its own process. It registers a `ContentProvider`‑like binder that Room clients use to receive invalidation callbacks. |
| **5 – Download manager bootstrap** | `FileDownloadService` is started (usually via `startService` or `bindService` from UI) | **`:filedownloader`** process | The service runs in the foreground **only when an actual download is active** (see section 3). It acquires a **partial wake lock** (`WAKE_LOCK`) via `PowerManager` to keep the CPU on while downloading. |
| **6 – JobScheduler registration** | UI or `Application` registers periodic jobs (e.g., “Check for app updates”, “Sync user preferences”) | **`:jobscheduler`** process (`JobInfoSchedulerService`) | The service is a `JobService` that the OS will invoke on its own schedule, even if the UI process is dead. |
| **7 – First UI screen rendered** | `MainActivity` finishes `onCreate`, `onStart`, `onResume` | UI process | At this point the user sees the home screen; background processes may already be running (download, DB invalidation, jobs). |

**Key take‑aways**

* **Provider‑first initialization** – `SentryInitProvider` runs before any `Application` code, guaranteeing crash‑reporting is active even if the UI process crashes early.
* **Lazy process spawning** – The background processes (`:filedownloader`, `:room`, `:jobscheduler`) are **not started** until the UI explicitly binds/starts them, which conserves RAM on cold start.
* **JobScheduler** is used for periodic work, complying with Android 12+ background‑execution limits (no `START_STICKY` services for long‑running background tasks).

---

## 3. Background Execution & Wake‑Lock Strategy  

| Permission | Why it is needed | Where it is used (code‑level inference) |
|------------|------------------|------------------------------------------|
| `WAKE_LOCK` | Keep CPU on while downloading large APKs / bundles. | `FileDownloadService` → `PowerManager.WakeLock` (partial

---

## 3. Agent 2: Presentation & Reactive Patterns
## TL;DR  
The **Aptoide** code‑base is built around a **classic MVP** core (View ↔ Presenter contracts) that has been **augmented with a thin “reactive‑MVVM‑ish” layer** for a handful of screens that need coroutine‑based flows.  
All UI screens expose a **View‑interface** (the “contract”) and a **Presenter‑interface** that lives in the `cm.aptoide.pt.presenter` hierarchy.  
The presenter holds a **weak reference to the View**, subscribes to **RxJava 2 streams** that represent user intents, and disposes them through a **`CompositeDisposable`** that is cleared in `detachView()` (or `onDestroy()` for Activities/Fragments).  
When a screen needs heavy list rendering, the team uses **Airbnb Epoxy** – a declarative model‑controller system that completely hides the RecyclerView boilerplate while still giving the presenter full control over the data‑set.

Below you will find a step‑by‑step reverse‑engineered description of the three points you asked for.

---

## 1️⃣ Contract (View + Presenter) – How it is Structured

| Layer | Package / Base Class | Typical Naming | What it contains |
|-------|----------------------|----------------|------------------|
| **View contract** | `cm.aptoide.pt.view.*` (often an inner interface called `View`) | `XxxView` (e.g. `AppViewPresenter.View`) | • UI‑only methods (showLoading, hideLoading, renderXxx, showError, navigate…) <br>• “Intent” getters that expose **Observable/Flowable** streams of UI events (clicks, text changes, swipe‑to‑refresh, etc.) |
| **Presenter contract** | `cm.aptoide.pt.presenter.Presenter<V extends View>` (generic) | `XxxPresenter` (e.g. `AppViewPresenter`) | • Lifecycle hooks: `attachView(V view)`, `detachView()` <br>• `present()` / `init()` – the entry point where the presenter wires the view intents to the domain layer <br>• `CompositeDisposable disposables` (or `Job` for coroutines) |
| **Base presenter** | `cm.aptoide.pt.presenter.ActionPresenter<V>` (extends `Presenter<V>`) | – | Provides common helpers: <br>• `protected fun <T> Observable<T>.autoDispose(): Disposable` (adds to `disposables`) <br>• `protected fun showError(error: Throwable)` (delegates to view) |
| **Concrete presenter** | `cm.aptoide.pt.app.view.AppViewPresenter` (extends `ActionPresenter<AppViewPresenter.View>`) | – | Implements the contract, builds the reactive graph, calls domain‑layer use‑cases, pushes UI state back to the view. |

### Example (pseudo‑decompiled)

```kotlin
// ---- View contract (inner interface) ---------------------------------
interface AppViewPresenter {
    interface View : BaseView {
        // UI rendering
        fun showAppDetails(app: App)
        fun showLoading()
        fun hideLoading()
        fun showError(error: Throwable)

        // UI intents – exposed as Rx streams
        fun retryClicks(): Observable<Unit>
        fun installClicks(): Observable<Unit>
        fun shareClicks(): Observable<Unit>
    }
}

// ---- Presenter contract ------------------------------------------------
abstract class Presenter<V : BaseView> {
    protected var view: V? = null
    protected val disposables = CompositeDisposable()

    open fun attachView(v: V) {
        view = v
        onViewAttached()
    }

    open fun detachView() {
        disposables.clear()          // <-- prevents leaks
        view = null
    }

    protected abstract fun onViewAttached()
}

// ---- Concrete presenter -------------------------------------------------
class AppViewPresenter @Inject constructor(
    private val getAppUseCase: GetAppUseCase,
    private val installUseCase: InstallUseCase
) : ActionPresenter<AppViewPresenter.View>() {

    override fun onViewAttached() {
        // 1️⃣ Load app data once
        disposables += getAppUseCase()
            .doOnSubscribe { view?.showLoading() }
            .doFinally { view?.hideLoading() }
            .subscribe(
                { app -> view?.showAppDetails(app) },
                { err -> view?.showError(err) }
            )

        // 2️⃣ React to UI intents
        disposables += view?.retryClicks()
            ?.flatMapSingle { getAppUseCase() }
            ?.subscribe({ app -> view?.showAppDetails(app) },
                        { err -> view?.showError(err) })
        disposables += view?.installClicks()
            ?.flatMapSingle { installUseCase(it) }
            ?.subscribe({ /* success UI */ }, { err -> view?.showError(err) })
    }
}
```

*Key observations from the decompiled names*  

* All presenter classes live under `cm.aptoide.pt.presenter.*`.  
* The generic base `Presenter<V>` defines `attachView(V)` / `detachView()` and a **`CompositeDisposable`** field.  
* Many concrete presenters have an inner static class named `$1` – this is the **lambda/anonymous class generated for each subscription** (e.g. `AppViewPresenter$1` is the `Observer` that receives the result of a use‑case).  
* Contracts for callbacks that are not strictly UI (e.g. `CommentDialogCallbackContract`, `CommentOnErrorCallbackContract`) follow the same pattern: a **single‑method interface** that the view implements and the presenter calls.

---

## 2️⃣ How a Presenter Subscribes to View Intents & Manages `CompositeDisposable`

### 2.1 Intent Exposure on the View

* The view (Activity/Fragment) implements the **View contract** and **exposes UI events as Rx streams**.  
* Implementation is usually a thin wrapper around **`RxView.clicks(view)`**, **`RxTextView.textChanges(editText)`**, or **`PublishSubject`** that the view pushes into when a callback fires.

```kotlin
class AppActivity : AppCompatActivity(), AppViewPresenter.View {

    private val retrySubject = PublishSubject.create<Unit>()
    private val installSubject = PublishSubject.create<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UI wiring …
        retryButton.setOnClickListener

---

## 4. Agent 3: Data, Database & Networking Layer
Below is a **complete, production‑grade walk‑through** of the data‑layer that can be inferred from the de‑compiled Aptoide code‑base, followed by a concrete **blue‑print** for adding a brand‑new “Antigravity Live‑Transcript / Prompt” feature that plugs into the same architecture.

---

## 1️⃣  Repository = Single Source of Truth (SSOT)

### 1.1  High‑level diagram (what the de‑compiled code already shows)

```
+-------------------+          +-------------------+          +-------------------+
|   UI / ViewModel  |  <--->  |   Repository      |  <--->  |   DataSources     |
| (LiveData/Flow)  |          | (Business logic) |          |   ├─ Remote (Retrofit)   |
+-------------------+          |                   |          |   └─ Local (Room)       |
                               +-------------------+          +-------------------+
```

* **Repository** is the *only* class that the UI layer ever talks to.  
* It **exposes** a **single source of truth** – a `Flow<T>` / `LiveData<T>` that always reflects the **latest state** of the data, regardless of whether that state lives in the SQLite DB, in a cached file, or is being streamed from the network.  
* All **mutations** (insert, update, delete, refresh) go through the repository, which decides **where** the operation should be performed and **when** the local cache must be updated.

### 1.2  Concrete implementation pattern (as used by Aptoide)

| Component | Typical name in Aptoide | What it does | Key interfaces / base classes |
|-----------|------------------------|--------------|--------------------------------|
| **Repository** | `StoreManager`, `MyAccountManager`, `ABTestRepository` | Orchestrates calls to `RemoteDataSource` and `LocalDataSource`. Caches remote results in Room, merges with local data, exposes `Flowable`/`LiveData`. | Implements a **domain‑level contract** (`StoreRepository`, `AccountRepository`). |
| **RemoteDataSource** | `AccountService`, `DynamicSplitsRemoteService$DynamicSplitsApi`, `ABTestService$ABTestingService` | Retrofit interface + thin wrapper that adds auth headers, logging, retry, and maps JSON → Kotlin data classes (Moshi/Jackson). | `interface AccountService { @GET … suspend fun getAccount(...): AccountDto }` |
| **LocalDataSource** | `RoomDatabase` (`AptoideDatabase`), `DataStore` (`PreferencesDataStore`) | DAO objects (`StoreDao`, `AccountDao`) + type converters. Handles SQLite migrations, provides `Flow<List<StoreEntity>>`. | `@Dao interface StoreDao { @Query … fun getAll(): Flow<List<StoreEntity>> }` |
| **Cache‑in‑memory** | `InMemoryCache` (often a `MutableStateFlow` inside the repo) | Holds the most recent network payload while the DB write is still pending, so UI never sees a “blank” state. | `private val cache = MutableStateFlow<Resource<Store>>(Resource.Loading())` |
| **Scheduler** | `WorkManager` jobs (`RefreshStoreWorker`, `SyncAccountWorker`) | Guarantees background refresh even when the app is killed. | `OneTimeWorkRequestBuilder<RefreshStoreWorker>()` |

### 1.3  Data‑flow example (Store data)

```kotlin
class StoreRepository @Inject constructor(
    private val remote: StoreRemoteDataSource,
    private val local: StoreLocalDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val workManager: WorkManager
) {

    // UI observes this – it is always the SSOT
    fun getStore(id: Long): Flow<Resource<Store>> = flow {
        // 1️⃣ Emit cached DB value immediately
        emit(Resource.Loading())
        val dbStore = local.getStore(id).firstOrNull()
        dbStore?.let { emit(Resource.Success(it.toDomain())) }

        // 2️⃣ Try remote refresh (network may be offline)
        try {
            val remoteDto = remote.fetchStore(id)
            // 3️⃣ Persist remote → DB (single transaction)
            local.insertOrUpdate(remoteDto.toEntity())
            // 4️⃣ Emit fresh DB value (will be observed again automatically)
        } catch (e: IOException) {
            // network error – keep DB value, just surface the error
            emit(Resource.Error(e.localizedMessage ?: "Network error", data = dbStore?.toDomain()))
        }
    }.flowOn(dispatcherProvider.io())
      .distinctUntilChanged()
}
```

* **Why this is SSOT:**  
  * The **only** place that writes to the DB is the repository (`insertOrUpdate`).  
  * The UI never calls the DAO directly – it always receives the **Flow** from the repo.  
  * If a background `WorkManager` job updates the DB later, the UI automatically receives the new value because the Flow is backed by Room’s `@Query … Flow<T>`.

### 1.4  How Aptoide’s existing classes illustrate the pattern

| Class | Role in the pattern |
|-------|---------------------|
| `StoreManager` | Repository for `Store` objects (exposes `Observable<Store>`). |
| `Store` (domain model) | Pure Kotlin data class used by UI. |
| `DatabaseStoreDataPersist$DatabaseStoreMapper` | Mapper that converts between `StoreEntity` (Room) and `Store` (domain). |
| `AccountService` | Retrofit interface – the **RemoteDataSource**. |
| `AptoideAccountManager$Builder` | Builder that injects the repository, remote, and local sources into the high‑level manager. |
| `AnalyticsManager` | Not a data repo per‑se, but follows the same “single source of truth” idea for analytics events (writes to local DB, flushes to remote). |

---

## 2️⃣  Graceful handling of network errors, retries & background streaming

### 2️⃣.1  OkHttp Interceptor stack (what Aptoide uses)

```
+---------------------------+
|  LoggingInterceptor       |   <-- logs request/response (debug builds)
+---------------------------+
|  AuthInterceptor          |   <-- adds OAuth token, refreshes if 401
+---------------------------+
|  RetryInterceptor         |   <-- exponential back‑off, max 3 attempts
+---------------------------+
|  OfflineCacheInterceptor  |   <-- forces cache when network unavailable
+---------------------------+
|  NetworkInterceptor (OkHttp) |
+---------------------------+
```

* **AuthInterceptor** – on a `401` it calls `Invalid

---

## 5. Agent 4: Dependency Injection & Modularization
## 1️⃣  Dagger 2 Component‑Scoping Hierarchy in the **Aptoide** code‑base  

Below is a distilled view of the component graph that can be inferred from the telemetry you posted.  
(The real project contains many more bindings – we focus on the *public* entry points that matter for modularisation.)

| Component | Package / Class | Scope (custom) | What it **exposes** to children | What it **receives** from parents |
|-----------|----------------|----------------|----------------------------------|-----------------------------------|
| **ApplicationComponent** | `cm.aptoide.pt.ApplicationComponent` | `@Singleton` (or a custom `@AppScope`) | • Global singletons: `AccountManager`, `Analytics`, `NetworkClient`, `Database`, `Repository`s, `DownloadStatusMapper`, `FilePathManager` … <br>• Factories generated in `ApplicationModule_*` | None – it is the root of the graph. |
| **ActivityComponent** | `cm.aptoide.pt.view.ActivityComponent` (sub‑component of `ApplicationComponent`) | `@ActivityScope` (custom) | • Activity‑level objects: `ActivityContext`, `ActivityPresenter`, `ActivityViewModel`, `ActivityModule`‑provided UI helpers (e.g. `FragmentManagerProvider`) <br>• Child `FragmentComponent` factories | Inherits everything from `ApplicationComponent`. |
| **FragmentComponent** | `cm.aptoide.pt.view.FragmentComponent` (sub‑component of `ActivityComponent`) | `@FragmentScope` (custom) | • Fragment‑level objects: `FragmentPresenter`, `FragmentViewModel`, `UiComponent` (the UI‑specific Dagger component), `FragmentModule`‑provided adapters, listeners, etc. | Inherits from `ActivityComponent` (and transitively from `ApplicationComponent`). |
| **UiComponent** | `cm.aptoide.pt.view.fragment.UiComponent` (sub‑component of `FragmentComponent`) | `@UiScope` (optional, often same as `@FragmentScope`) | • UI‑only bindings: `RecyclerView.Adapter`s, `ViewBinding` factories, `ClickListener`s, `ViewModelProvider.Factory` for the fragment UI. | Inherits from `FragmentComponent`. |

### Visualisation (simplified)

```
@AppScope
ApplicationComponent
│
├─ provides: NetworkModule, RepositoryModule, AnalyticsModule, …
│
└─> @ActivityScope ActivityComponent (sub‑component)
    │
    ├─ provides: ActivityContext, ActivityPresenter, …
    │
    └─> @FragmentScope FragmentComponent (sub‑component)
        │
        ├─ provides: FragmentPresenter, FragmentViewModel, UiComponent
        │
        └─> @UiScope UiComponent (sub‑component)
            └─ provides: UI‑only adapters, view‑bindings, etc.
```

*Key points*  

* **Sub‑components** are used rather than component dependencies because they automatically inherit the parent’s object graph **and** they can expose a *factory* that the parent can call (`ActivityComponent.Factory.create(activity)`).  
* Each custom scope (`@AppScope`, `@ActivityScope`, `@FragmentScope`) guarantees a **single instance per lifecycle** – this prevents leaking an `Activity`‑context into a singleton and eliminates the classic “Context leak” bug.  
* The **Flavour** modules (`FlavourApplicationModule`, `FlavourActivityModule`, `FlavourFragmentModule`) are *install‑time* modules that replace or augment the base modules for different product flavours (e.g., “free” vs “paid”). They are bound with `@BindsInstance` or `@Provides` inside the same component hierarchy, so the flavour‑specific implementations are swapped without touching the rest of the graph.

---

## 2️⃣  How Presenter & Repository injection avoid tight coupling & context leaks  

### 2.1  Presenter injection (MVP / MVVM style)

```kotlin
// Example: ActivityPresenter
@ActivityScope
class ActivityPresenter @Inject constructor(
    private val accountManager: AccountManager,          // from AppComponent
    private val analytics: Analytics,                    // from AppComponent
    private val activityNavigator: ActivityNavigator,    // provided by ActivityModule
    @Named("activity") private val activity: Activity    // bound via ActivityModule
) : Presenter {
    // …
}
```

* **What’s happening?**  
  * The presenter receives **only abstractions** (`AccountManager`, `Analytics`, `ActivityNavigator`).  
  * The concrete implementations live in the **ApplicationComponent** (singletons) or **ActivityModule** (activity‑scoped).  
  * The `Activity` reference is **qualified** (`@Named("activity")`) and scoped to `@ActivityScope`. Because the presenter itself is also `@ActivityScope`, Dagger guarantees that the activity reference is cleared when the component is destroyed (i.e., when the activity finishes).  

* **Why no tight coupling?**  
  * The presenter does **not** call `MyApplication.getInstance()` or `new SomeRepository()`.  
  * All dependencies are **injected**; the presenter can be unit‑tested by providing mock implementations of the interfaces.  

### 2.2  Repository injection (data layer)

```kotlin
// Example: StoreRepository
@Singleton
class StoreRepository @Inject constructor(
    private val networkService: StoreNetworkService,   // from NetworkModule
    private val storeDao: StoreDao,                     // from DatabaseModule
    private val mapper: StoreMapper                    // from RepositoryModule
) {
    // …
}
```

* **What’s happening?**  
  * The repository lives in the **ApplicationComponent** (`@Singleton`).  
  * It only depends on **pure data‑layer abstractions** (`StoreNetworkService`, `StoreDao`, `StoreMapper`).  
  * No Android `Context` is required – the repository can be used from a background `WorkManager` worker, a service, or a presenter without any UI reference.  

* **Leak‑proofness**  
  * Because the repository never holds a `Context`, there is no risk of leaking an `Activity` or `Fragment`.  
  * If a repository *does* need a `Context` (e.g., to read a file), it receives an **`Application` context** via a `@Provides @ApplicationContext Context` binding in `AppModule`. This context lives as long as the process, which is safe for singletons.  

### 2.3  Service / Background injection  

```kotlin
@Singleton
class DownloadService @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val work

---

## 6. Agent 5: Master Architectural Blueprint for Antigravity Mobile
# Antigravity Android Client – Enterprise‑Grade Architecture Blueprint  
*(Synthesised from the 4 reverse‑engineering agents)*  

---  

## 1️⃣  High‑Level Architecture Diagram  

```
+---------------------------------------------------------------+
|                         Android OS                            |
|  +-------------------+   +-------------------+   +-----------+ |
|  | UI Process        |   | :filedownloader   |   | :room     | |
|  | (cm.antigravity)  |   | (download svc)    |   | (DB svc)  | |
|  |                   |   |                   |   |           | |
|  |  +-------------------------------+--------------------+   |
|  |  |  Clean‑Architecture Layers    |   Reactive Flow   |   |
|  |  |                               |   (Kotlin Flow)   |   |
|  |  |  +--------------------------+ | +----------------+ | |
|  |  |  |  Presentation (MVP/MVI) | | |  Domain (Use‑Case) | |
|  |  |  +--------------------------+ | +----------------+ | |
|  |  |  |  Data (Repository)       | | |  Network (Retrofit) | |
|  |  |  +--------------------------+ | +----------------+ | |
|  |  |  |  Core (DI, Utils)        | | |  Persistence (Room) | |
|  |  |  +--------------------------+ | +----------------+ | |
|  |  +-----------------------------------------------+   |
|  +-------------------------------------------------------+
|  +-------------------+   +-------------------+   +-----------+ |
|  | :jobscheduler    |   | :sentry           |   | … other   | |
|  | (WorkManager)    |   | (Crash‑reporting) |   | procs    | |
|  +-------------------+   +-------------------+   +-----------+ |
+---------------------------------------------------------------+
```

* **UI Process** – Holds all foreground UI, View‑/Presenter‑/ViewModel‑objects, the Clean‑Architecture “inner circle”.  
* **Background processes** – `:filedownloader`, `:room`, `:jobscheduler`, `:sentry` are **isolated** exactly as Aptoide does, guaranteeing that heavy I/O, DB invalidation and crash‑reporting never block the UI thread or cause OOM.  
* **Reactive Data Flow** – Every UI screen consumes a **`StateFlow` / `LiveData`** that is produced by a **Presenter/ViewModel**. The flow is **cold** (starts when the view attaches) and **auto‑disposes** when the view’s lifecycle ends.  

---  

## 2️⃣  Recommended Gradle‑Module Layout  

| Module | Responsibility | Typical Dependencies | Notes |
|--------|----------------|----------------------|-------|
| `:app` | UI‑process entry point, navigation host, DI root (`@HiltAndroidApp`). | `:core-domain`, `:core-data`, `:core-network`, `:feature‑chat`, `:feature‑transcript`, AndroidX, Hilt, Navigation, Epoxy. | Minimal code – only glue, theme, DI, and navigation. |
| `:core-network` | Retrofit/OkHttp client, interceptors, API models, authentication. | `okhttp`, `retrofit`, `moshi/kotlinx‑serialization`, `hilt‑android`. | Provides **`NetworkModule`** (singleton). |
| `:core-data` | Repository interfaces, generic `Resource<T>` wrapper, mappers, paging helpers. | `room`, `kotlinx‑coroutines`, `flow`, `hilt‑android`. | No Android UI classes – can be used from workers or services. |
| `:core-domain` | Use‑case (interactor) classes, business‑logic, pure Kotlin. | `core-data`, `core-network`, `coroutines`. | All use‑cases are **`@Inject`able** and return `Flow<Resource<…>>`. |
| `:feature‑chat` | UI for real‑time chat, presenter, view‑binding, Epoxy controller. | `app`, `core-domain`, `core-data`, `core-network`. | Demonstrates MVP + Epoxy. |
| `:feature‑transcript` | Live‑transcript (the “Antigravity” core feature). | `app`, `core-domain`, `core-data`, `core-network`. | Contains the **re‑connectable WebSocket/Server‑Sent‑Events** stream. |
| `:background‑downloader` | `:filedownloader` process – a **stand‑alone** Gradle module that builds a separate **apk‑split** (see `android { defaultConfig { process = ":filedownloader" } }`). | `core-network`, `core-data`. | Declares its own `android:process` in the manifest. |
| `:background‑room` | `:room` process – runs `MultiInstanceInvalidationService`. | `room`. | Same pattern as Aptoide. |
| `:background‑jobs` | `:jobscheduler` – WorkManager workers for periodic sync. | `core-data`, `core-domain`. | Declared as a separate process. |

**Why this modularisation?**  

* **Separation of concerns** – UI never touches networking or persistence directly.  
* **Compile‑time dependency isolation** – Feature modules can be built and tested independently.  
* **Process‑level isolation** – Background modules are compiled as separate APK splits (or as separate `android:process` services) and can be shipped as “dynamic‑feature” modules if desired.  

---  

## 3️⃣  Concrete Kotlin Templates  

Below you will find **minimal, production‑ready** snippets that you can copy‑paste into the corresponding modules.  
All code follows **Clean Architecture + MVP (with a thin MVI‑style StateFlow)** and **Reactive** principles.

---  

### 3.1  View / Presenter Contract (MVP)  

```kotlin
// module: :app (or feature module)
// package: com.antigravity.feature.transcript.ui

/** Base contract for every MVP screen */
interface MvpContract {

    /** Base view – UI‑only methods */
    interface View {
        fun render(state: ViewState)
        fun showError(t: Throwable)
        fun navigateTo(destination: NavDestination)
    }

    /** Base presenter – lifecycle aware */
    interface Presenter<V : View> {
        /** Called from Activity/Fragment in onCreate/onViewCreated */
        fun attach(view: V)

        /** Called from Activity/Fragment in onDestroy/onDetach */
        fun detach()
    }
}

/** Specific contract for the Live‑Transcript screen */
interface TranscriptContract : MvpContract {

    /** UI state that the view renders */
    data class ViewState(
        val isConnecting: Boolean = false,
        val transcript: List<TranscriptLine> = emptyList(),
        val error: String? = null
    )

    /** UI‑only callbacks */
    interface View : MvpContract.View {
        /** Exposes UI intents as cold Flows */
        val retryClicks: Flow<Unit>
        val sendMessageClicks: Flow<String>
    }

    /** Presenter that drives the view */
    interface Presenter : MvpContract.Presenter<View>
}
```

**How it is used in a Fragment**

```kotlin
@AndroidEntryPoint
class TranscriptFragment : Fragment(R.layout.fragment_transcript),
    TranscriptContract.View {

    // UI bindings (ViewBinding / Epoxy)
    private val binding by viewBinding(FragmentTranscriptBinding::bind)

    // ----- UI intents -------------------------------------------------
    private val _retryClicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val retryClicks: Flow<Unit> = _retryClicks.asSharedFlow()

    private val _sendMessageClicks = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val sendMessageClicks: Flow<String> = _sendMessageClicks.asSharedFlow()

    // ----- Presenter injection ----------------------------------------
    @Inject lateinit var presenter: TranscriptContract.Presenter

    // ----- Lifecycle --------------------------------------------------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        presenter.attach(this)

        binding.retryBtn.setOnClickListener { _retryClicks.tryEmit(Unit) }
        binding.sendBtn.setOnClickListener {
            val msg = binding.messageEt.text?.toString().orEmpty()
            if (msg.isNotBlank()) _sendMessageClicks.tryEmit(msg)
        }
    }

    override fun onDestroyView() {
        presenter.detach()
        super.onDestroyView()
    }

    // ----- Rendering --------------------------------------------------
    override fun render(state: TranscriptContract.ViewState) {
        binding.progress.isVisible = state.isConnecting
        binding.errorTv.text = state.error
        // Epoxy controller receives the transcript list
        transcriptController.setData(state.transcript)
    }

    override fun showError(t: Throwable) {
        Snackbar.make(requireView(), t.localizedMessage ?: "Error", Snackbar.LENGTH_LONG)
            .show()
    }

    override fun navigateTo(destination: NavDestination) {
        findNavController().navigate(destination.resId, destination.args)
    }
}
```

---  

### 3.2  Presenter Implementation (MVP + Reactive Flow)

```kotlin
// module: :feature-transcript
// package: com.antigravity.feature.transcript.presentation

@ActivityScope // or @FragmentScope – whichever component creates it
class TranscriptPresenter @Inject constructor(
    private val getTranscriptUseCase: GetTranscriptUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val dispatcherProvider: DispatcherProvider   // provides io/main coroutines
) : TranscriptContract.Presenter {

    private var view: TranscriptContract.View? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(dispatcherProvider.main + job)

    override fun attach(view: TranscriptContract.View) {
        this.view = view
        bindIntents()
        startStreaming()
    }

    override fun detach() {
        job.cancel()               // disposes all coroutines → no leaks
        this.view = null
    }

    /** 1️⃣ UI intents → domain actions */
    private fun bindIntents() {
        view?.retryClicks
            ?.onEach { startStreaming() }
            ?.launchIn(scope)

        view?.sendMessageClicks
            ?.flatMapLatest { msg ->
                sendMessageUseCase(msg)
                    .onStart { /* optional UI feedback */ }
                    .catch { view?.showError(it) }
            }
            ?.launchIn(scope)
    }

    /** 2️⃣ Core streaming logic – auto‑reconnect, back‑off, state emission */
    private fun startStreaming() {
        // Emit a “connecting” UI state first
        view?.render(TranscriptContract.ViewState(isConnecting = true))

        scope.launch {
            getTranscriptUseCase()
                .retryWhen { cause, attempt ->
                    // Exponential back‑off up to 5 attempts
                    if (attempt >= 5) return@retryWhen false
                    delay( (2.0.pow(attempt.toDouble()) * 1000L).toLong() )
                    true
                }
                .catch { e ->
                    view?.render(
                        TranscriptContract.ViewState(
                            isConnecting = false,
                            error = e.localizedMessage
                        )
                    )
                }
                .collect { transcriptLines ->
                    view?.render(
                        TranscriptContract.ViewState(
                            isConnecting = false,
                            transcript = transcriptLines
                        )
                    )
                }
        }
    }
}
```

*Key points*  

* **`SupervisorJob` + `CoroutineScope`** guarantees that a failure in one child coroutine does **not** cancel the whole presenter.  
* **`retryWhen`** implements **automatic reconnection** with exponential back‑off – solves the “socket drops” problem once and for all.  
* All UI updates happen on **`dispatcherProvider.main`**, which is bound to the Android main thread via Hilt.  

---  

### 3.3  Domain Use‑Cases (Core‑Domain)

```kotlin
// module: :core-domain
// package: com.antigravity.domain.transcript

interface GetTranscriptUseCase {
    /** Returns a cold Flow that emits the whole transcript as it arrives */
    operator fun invoke(): Flow<List<TranscriptLine>>
}

interface SendMessageUseCase {
    operator fun invoke(message: String): Flow<Unit>
}

/** Implementation – injected in the domain component */
class GetTranscriptUseCaseImpl @Inject constructor(
    private val repository: TranscriptRepository,
    private val dispatcherProvider: DispatcherProvider
) : GetTranscriptUseCase {

    override fun invoke(): Flow<List<TranscriptLine>> =
        repository.transcriptStream()
            .flowOn(dispatcherProvider.io())
            .distinctUntilChanged()
}

class SendMessageUseCaseImpl @Inject constructor(
    private val repository: TranscriptRepository,
    private val dispatcherProvider: DispatcherProvider
) : SendMessageUseCase {

    override fun invoke(message: String): Flow<Unit> =
        flow {
            repository.sendMessage(message)
            emit(Unit)
        }.flowOn(dispatcherProvider.io())
}
```

---  


