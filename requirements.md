# Anime Tracker — Requirements & Architecture

> Solo project · Kotlin · Android · Learning-focused

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Requirements](#2-requirements)
   - 2.1 [Functional Requirements](#21-functional-requirements)
   - 2.2 [Non-Functional Requirements](#22-non-functional-requirements)
   - 2.3 [Out of Scope](#23-out-of-scope)
3. [Data Model](#3-data-model)
4. [Screens](#4-screens)
5. [Architecture](#5-architecture)
   - 5.1 [Overview](#51-overview)
   - 5.2 [MVVM in Detail](#52-mvvm-in-detail)
   - 5.3 [Dependency Injection with Hilt](#53-dependency-injection-with-hilt)
   - 5.4 [Project Structure](#54-project-structure)
6. [Tech Stack](#6-tech-stack)

---

## 1. Project Overview

A personal Android app to track animes by watch status. The primary goal is to learn idiomatic Kotlin and modern Android development (Compose, Room, Hilt, MVVM). Shipping a usable app is the secondary goal.

**Core user story:**
> As a solo anime viewer, I want to maintain a personal list of animes organized by watch status, so I can remember what I've watched, track my episode progress, and plan what to watch next — all without an internet connection.

---

## 2. Requirements

### 2.1 Functional Requirements

#### Core (v1 — must have)

| ID | Feature | Description |
|----|---------|-------------|
| F-01 | Anime list | Display all animes grouped/filtered by status |
| F-02 | Status tabs | Five tabs: Watching, Plan to Watch, Completed, On Hold, Dropped |
| F-03 | Add anime | Form to create a new entry with all fields |
| F-04 | Edit anime | Tap an entry to update any field |
| F-05 | Delete anime | Remove an entry with a confirmation dialog |
| F-06 | Search | Filter the current tab by title |
| F-07 | Local persistence | All data stored locally with Room, survives app restarts |

#### Stretch (attempt if time allows)

| ID | Feature | Description |
|----|---------|-------------|
| S-01 | API search | Look up titles from Jikan (MyAnimeList) to auto-fill fields |
| S-02 | Cover art | Display poster images in the list using Coil |
| S-03 | Statistics screen | Entry count per status, total episodes watched |

### 2.2 Non-Functional Requirements

- **Offline-first.** All core features work with no internet connection. Network is additive for stretch features only.
- **Smooth performance.** List scrolling must be fluid. No work on the main thread — use Kotlin coroutines throughout.
- **Architecture.** Strict MVVM. No business logic in Composables. Single source of truth for UI state via `StateFlow`.
- **Android target.** `minSdk 26` (Android 8.0) · `targetSdk` latest stable. Covers ~95% of active devices.
- **Code quality.** Idiomatic Kotlin (data classes, sealed classes, extension functions, coroutines). No Java interop unless unavoidable.

### 2.3 Out of Scope

- User accounts and cloud sync
- Social features (sharing, friends, export)
- Push notifications
- Widgets or home screen shortcuts

---

## 3. Data Model

### Anime entity

```kotlin
@Entity(tableName = "animes")
data class Anime(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val status: Status,
    val currentEpisode: Int = 0,
    val totalEpisodes: Int? = null,
    val coverUrl: String? = null
)
```

| Field | Type | Notes |
|-------|------|-------|
| `id` | `Int` | Auto-generated primary key. Defaults to 0 (Room treats 0 as "unset" with autoGenerate) |
| `title` | `String` | Required. The anime title |
| `status` | `Status` | Enum — see below. Stored as String via TypeConverter |
| `currentEpisode` | `Int` | Episode progress, defaults to 0 |
| `totalEpisodes` | `Int?` | Nullable — unknown for ongoing series |
| `coverUrl` | `String?` | Nullable — populated from API search (stretch) |

### Status enum

```kotlin
enum class Status {
    WATCHING,
    PLAN_TO_WATCH,
    COMPLETED,
    ON_HOLD,
    DROPPED
}
```

### Room TypeConverter

Room cannot persist enums natively. A converter serializes `Status` to and from `String`:

```kotlin
class Converters {
    @TypeConverter
    fun fromStatus(status: Status): String = status.name

    @TypeConverter
    fun toStatus(value: String): Status = Status.valueOf(value)
}
```

The converter is registered on the database class with `@TypeConverters(Converters::class)`.

---

## 4. Screens

| Screen | Trigger | Description |
|--------|---------|-------------|
| **List** | App launch | Tabbed view by status. Each row shows title, episode progress, and cover art. FAB opens the Add screen |
| **Detail / Edit** | Tap row or FAB | Form to create or update an entry. Save button writes to DB via ViewModel |
| **Search** *(stretch)* | Search icon | API-powered title lookup. Tap a result to pre-fill the Add form |
| **Statistics** *(stretch)* | Nav item | Summary numbers — entries per status, total episodes watched |

---

## 5. Architecture

### 5.1 Overview

The app follows **MVVM (Model–View–ViewModel)** with a **Repository pattern**, enforced by **Hilt** for dependency injection.

```
UI Layer (Compose)
    │  observes StateFlow
    ▼
ViewModel Layer
    │  calls suspend functions
    ▼
Repository Layer
    │  abstracts data source(s)
    ▼
Data Layer (Room DAO)
    │
    ▼
SQLite Database
```

Each layer has a single responsibility and communicates only with the layer directly below it. The UI never touches the DAO. The DAO never knows about the ViewModel.

---

### 5.2 MVVM in Detail

#### Model

The Model layer owns all data and business logic. It has two parts:

**Room Entity & DAO**

The `Anime` data class is the Room entity — it maps directly to a database row. The DAO (Data Access Object) defines the SQL operations as Kotlin `suspend` functions or `Flow`-returning functions:

```kotlin
@Dao
interface AnimeDao {
    @Query("SELECT * FROM animes WHERE status = :status ORDER BY title ASC")
    fun getAnimesByStatus(status: Status): Flow<List<Anime>>

    @Query("SELECT * FROM animes WHERE id = :id")
    suspend fun getAnimeById(id: Int): Anime?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(anime: Anime)

    @Update
    suspend fun update(anime: Anime)

    @Delete
    suspend fun delete(anime: Anime)
}
```

- `Flow<List<Anime>>` on read queries means Room automatically emits a new list whenever the underlying data changes. The ViewModel observes this flow and the UI always reflects the latest state.
- `suspend` on write operations means they must be called from a coroutine. Room enforces that write operations do not run on the main thread.

**Room Database**

```kotlin
@Database(entities = [Anime::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AnimeDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
}
```

**Repository**

The Repository is the single source of truth. It abstracts the data source from the ViewModel — if a network source is added later (stretch), only the Repository changes:

```kotlin
class AnimeRepository @Inject constructor(
    private val dao: AnimeDao
) {
    fun getAnimesByStatus(status: Status): Flow<List<Anime>> =
        dao.getAnimesByStatus(status)

    suspend fun insert(anime: Anime) = dao.insert(anime)

    suspend fun update(anime: Anime) = dao.update(anime)

    suspend fun delete(anime: Anime) = dao.delete(anime)

    suspend fun getById(id: Int): Anime? = dao.getAnimeById(id)
}
```

---

#### ViewModel

The ViewModel holds and manages UI state. It survives configuration changes (e.g., screen rotation). It exposes immutable state to the UI via `StateFlow` and provides methods the UI calls in response to user actions.

```kotlin
@HiltViewModel
class AnimeViewModel @Inject constructor(
    private val repository: AnimeRepository
) : ViewModel() {

    // Currently selected tab
    private val _selectedStatus = MutableStateFlow(Status.WATCHING)
    val selectedStatus: StateFlow<Status> = _selectedStatus.asStateFlow()

    // Anime list for the selected status
    val animes: StateFlow<List<Anime>> = _selectedStatus
        .flatMapLatest { status -> repository.getAnimesByStatus(status) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun selectStatus(status: Status) {
        _selectedStatus.value = status
    }

    fun addAnime(anime: Anime) {
        viewModelScope.launch { repository.insert(anime) }
    }

    fun updateAnime(anime: Anime) {
        viewModelScope.launch { repository.update(anime) }
    }

    fun deleteAnime(anime: Anime) {
        viewModelScope.launch { repository.delete(anime) }
    }
}
```

Key points:
- `viewModelScope` is a coroutine scope tied to the ViewModel's lifecycle. When the ViewModel is cleared, all coroutines in this scope are cancelled automatically.
- `flatMapLatest` switches to a new Flow every time the selected status changes, cancelling the previous collection. This ensures the list always reflects the active tab.
- `stateIn` converts the cold `Flow` from Room into a hot `StateFlow` that the UI can collect. `WhileSubscribed(5000)` keeps the upstream active for 5 seconds after the last subscriber disappears (handles screen rotation gracefully).
- The ViewModel exposes only `StateFlow` (immutable from the UI's perspective). The mutable backing fields are private.

---

#### View (Compose UI)

Composables observe the ViewModel's `StateFlow` using `collectAsStateWithLifecycle()`. They are purely declarative — they describe what the UI looks like for a given state, and delegate all actions back to the ViewModel.

```kotlin
@Composable
fun AnimeListScreen(
    viewModel: AnimeViewModel = hiltViewModel()
) {
    val animes by viewModel.animes.collectAsStateWithLifecycle()
    val selectedStatus by viewModel.selectedStatus.collectAsStateWithLifecycle()

    Column {
        StatusTabRow(
            selectedStatus = selectedStatus,
            onStatusSelected = viewModel::selectStatus
        )
        AnimeList(
            animes = animes,
            onDelete = viewModel::deleteAnime
        )
    }
}
```

Rules for Composables:
- **No business logic.** No filtering, sorting, or data transformation inside a Composable — that belongs in the ViewModel or Repository.
- **No direct DAO or Repository access.** Composables only talk to ViewModels.
- **State flows down, events flow up.** A child Composable receives data as parameters and emits user actions via lambdas. It never holds mutable state that affects business logic.

---

### 5.3 Dependency Injection with Hilt

Hilt wires the dependency graph at compile time. Each component is annotated so Hilt knows how to build and inject it.

#### Application class

```kotlin
@HiltAndroidApp
class AnimeApplication : Application()
```

Required entry point. Registers the application-level Hilt component.

#### Activity

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AnimeTrackerApp() }
    }
}
```

`@AndroidEntryPoint` enables injection into the Activity and all Composables within it (via `hiltViewModel()`).

#### Hilt Module

The module provides instances that cannot be annotated directly (i.e., third-party classes like `Room`):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAnimeDatabase(@ApplicationContext context: Context): AnimeDatabase =
        Room.databaseBuilder(
            context,
            AnimeDatabase::class.java,
            "anime_database"
        ).build()

    @Provides
    @Singleton
    fun provideAnimeDao(database: AnimeDatabase): AnimeDao =
        database.animeDao()
}
```

- `@InstallIn(SingletonComponent::class)` — these bindings live for the entire application lifetime. The database and DAO are singletons.
- `@Singleton` — Hilt creates exactly one instance and reuses it across the app.
- `@ApplicationContext` — Hilt injects the application context automatically.

#### Dependency graph summary

```
SingletonComponent (app lifetime)
│
├── AnimeDatabase        (@Singleton, provided by AppModule)
│       └── AnimeDao     (@Singleton, provided by AppModule)
│               └── AnimeRepository  (@Inject constructor)
│                           └── AnimeViewModel  (@HiltViewModel)
│                                       └── AnimeListScreen  (hiltViewModel())
```

Hilt resolves this entire graph automatically at compile time. Adding a new screen that needs the ViewModel requires only `hiltViewModel()` in the Composable — no manual wiring.

---

### 5.4 Project Structure

```
app/
└── src/main/
    ├── java/com/example/animetracker/
    │   ├── AnimeApplication.kt          # @HiltAndroidApp
    │   ├── MainActivity.kt              # @AndroidEntryPoint, Compose host
    │   │
    │   ├── data/
    │   │   ├── local/
    │   │   │   ├── AnimeDatabase.kt     # RoomDatabase
    │   │   │   ├── AnimeDao.kt          # DAO interface
    │   │   │   └── Converters.kt        # TypeConverter for Status enum
    │   │   ├── model/
    │   │   │   ├── Anime.kt             # Room entity + data class
    │   │   │   └── Status.kt            # Status enum
    │   │   └── repository/
    │   │       └── AnimeRepository.kt   # Single source of truth
    │   │
    │   ├── di/
    │   │   └── AppModule.kt             # Hilt @Module — provides DB and DAO
    │   │
    │   ├── ui/
    │   │   ├── screens/
    │   │   │   ├── list/
    │   │   │   │   └── AnimeListScreen.kt
    │   │   │   └── detail/
    │   │   │       └── AnimeDetailScreen.kt
    │   │   ├── components/
    │   │   │   ├── AnimeListItem.kt     # Reusable row Composable
    │   │   │   └── StatusTabRow.kt      # Tab bar Composable
    │   │   └── theme/
    │   │       ├── Theme.kt
    │   │       ├── Color.kt
    │   │       └── Type.kt
    │   │
    │   └── viewmodel/
    │       └── AnimeViewModel.kt        # @HiltViewModel
    │
    └── res/
        └── ...
```

---

## 6. Tech Stack

| Layer | Library | Version | Notes |
|-------|---------|---------|-------|
| Language | Kotlin | Latest stable | Primary language |
| UI | Jetpack Compose | Latest stable | Declarative UI, replaces XML layouts |
| UI toolkit | Material 3 | Latest stable | Components, theming |
| Navigation | Compose Navigation | Latest stable | Type-safe nav between screens |
| Architecture | ViewModel + StateFlow | AndroidX | State management |
| Database | Room | Latest stable | SQLite abstraction with Flow support |
| DI | Hilt | Latest stable | Compile-time DI on top of Dagger 2 |
| Async | Kotlin Coroutines | Latest stable | Async/await, replaces RxJava |
| Image loading | Coil *(stretch)* | Latest stable | Kotlin-first image loader |
| HTTP client | Retrofit *(stretch)* | Latest stable | REST API calls to Jikan |
| API | Jikan v4 *(stretch)* | — | Free MyAnimeList API, no key required |

### Gradle dependencies (core)

```kotlin
// build.gradle.kts (app)
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.xx.xx"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.navigation:navigation-compose")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")

    // Room
    implementation("androidx.room:room-runtime")
    implementation("androidx.room:room-ktx")
    ksp("androidx.room:room-compiler")

    // Hilt
    implementation("com.google.dagger:hilt-android")
    ksp("com.google.dagger:hilt-compiler")
    implementation("androidx.hilt:hilt-navigation-compose")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")
}
```

> Use KSP (`ksp(...)`) instead of `kapt` for annotation processing — it is significantly faster and is the recommended approach for new projects.
