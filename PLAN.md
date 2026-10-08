# SignalLogger — implementation plan

Goal: an Android app that, during a train ride, records **every N seconds** the phone's GPS position and, **for each active SIM separately**, registration state, serving cell and signal strength, plus an optional real-internet probe on the data SIM. Output is a CSV file that can be shared and analysed afterwards (where do both networks lose coverage at the same time?).

Constraints:
- The environment that writes this code has **no Android SDK and no access to Google Maven / Gradle**. Nothing can be compiled here. Every API used must be checked mentally against the documented signature and API level. Prefer boring, well-known APIs over clever ones.
- The Gradle wrapper (`gradlew`, `gradle/wrapper/*`, Gradle 8.11.1) already exists — do not touch it.
- Single module `:app`, Kotlin, Jetpack Compose, light MVI. No DI framework, no Room, no Play Services.
- UI strings in **Polish**. Code, identifiers and comments in English.

---

## 1. Build setup

`gradle/libs.versions.toml`:

| key | version |
|---|---|
| agp | 8.7.3 |
| kotlin | 2.1.0 |
| coreKtx | 1.15.0 |
| lifecycle | 2.8.7 |
| activityCompose | 1.9.3 |
| composeBom | 2024.12.01 |
| coroutines | 1.9.0 |
| junit | 4.13.2 |

Plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose` (version = kotlin).

Libraries: `androidx.core:core-ktx`, `androidx.lifecycle:lifecycle-runtime-ktx`, `androidx.lifecycle:lifecycle-runtime-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.activity:activity-compose`, Compose BOM + `ui`, `ui-tooling-preview`, `material3`, `ui-tooling` (debugImplementation), `kotlinx-coroutines-android`, `junit` (testImplementation). **Do not use material icons** (avoid the extra artifact) — text buttons only.

`app/build.gradle.kts`:
- `namespace = "pl.com.chodera.signallogger"`, `applicationId` same.
- `compileSdk = 35`, `targetSdk = 35`, **`minSdk = 30`** (gives public `NetworkRegistrationInfo`, `CellIdentityLte.bands`, `CellInfo.timestampMillis`, `CellSignalStrengthWcdma.ecNo` etc.).
- `versionCode = 1`, `versionName = "1.0"`.
- Java 17 (`compileOptions`), Kotlin `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`.
- `buildFeatures { compose = true }`.
- release: `isMinifyEnabled = false` (keep it simple).

Root: `settings.gradle.kts` with `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, `dependencyResolutionManagement { repositoriesMode FAIL_ON_PROJECT_REPOS; repositories { google(); mavenCentral() } }`, `rootProject.name = "SignalLogger"`, `include(":app")`. Root `build.gradle.kts` declares the three plugins with `apply false`. `gradle.properties`: `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`. Add a standard Android `.gitignore`.

## 2. Manifest

Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_PHONE_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`.
`<uses-feature android:name="android.hardware.telephony" android:required="true" />`.

Components:
- `application android:name=".SignalLoggerApp"`, label "Logger zasięgu", icon = adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml` with solid color background + vector foreground drawable — a simple antenna/bars shape; also `ic_launcher_round` pointing to the same).
- `MainActivity` exported, launcher intent filter, `android:launchMode="singleTop"`.
- `recorder.RecorderService`: `android:exported="false"`, `android:foregroundServiceType="location"`.
- `androidx.core.content.FileProvider`: authority `${applicationId}.fileprovider`, `exported=false`, `grantUriPermissions=true`, meta-data `res/xml/file_paths.xml` with `<external-files-path name="logs" path="logs/" />`.

## 3. Package layout (`pl.com.chodera.signallogger`)

```
SignalLoggerApp.kt            // creates notification channel
MainActivity.kt               // enableEdgeToEdge(), setContent { Theme { MainScreen() } }
model/                        // PURE KOTLIN — no android.* imports (JVM unit-testable)
  Models.kt
  ServingCellSelector.kt
  CsvFormat.kt
recorder/
  RecorderService.kt          // foreground service, sampling loop
  RecorderRepository.kt       // in-process StateFlow shared with UI
  RadioCollector.kt           // Android telephony -> model
  LocationTracker.kt          // LocationManager wrapper
  CellularProbe.kt            // HTTP probe bound to the cellular network
  CsvSessionWriter.kt         // file IO
  SessionFiles.kt             // list / delete / share URI
ui/
  MainContract.kt             // UiState, UiIntent, UiEffect
  MainViewModel.kt
  MainScreen.kt
  theme/Theme.kt
```

## 4. Pure model (`model/`)

```kotlin
const val UNAVAILABLE_INT = Int.MAX_VALUE          // == android.telephony.CellInfo.UNAVAILABLE
const val UNAVAILABLE_LONG = Long.MAX_VALUE        // == CellInfo.UNAVAILABLE_LONG
fun Int.validOrNull(): Int? = takeIf { it != UNAVAILABLE_INT }
fun Long.validOrNull(): Long? = takeIf { it != UNAVAILABLE_LONG }

enum class Rat { GSM, WCDMA, LTE, NR, OTHER }

data class CellSnapshot(
    val rat: Rat,
    val isRegistered: Boolean,
    val isPrimaryServing: Boolean,   // cellConnectionStatus == CELL_CONNECTION_PRIMARY_SERVING
    val mcc: String?, val mnc: String?,
    val cellId: Long?,               // LTE ci, NR nci, WCDMA/GSM cid
    val tac: Int?,                   // LTE/NR tac, WCDMA/GSM lac
    val pci: Int?,                   // LTE/NR pci, WCDMA psc, GSM bsic
    val arfcn: Int?,                 // earfcn / nrarfcn / uarfcn / arfcn
    val bands: List<Int>,
    val ageS: Double?,               // from CellInfo.timestampMillis vs SystemClock.elapsedRealtime()
) { val plmn: String? get() = if (mcc != null && mnc != null) mcc + mnc else null }

data class SignalSnapshot(
    val level: Int?,                 // SignalStrength.level 0..4
    val rsrp: Int?, val rsrq: Int?, val sinr: Int?, val rssi: Int?,   // LTE (sinr = rssnr)
    val dbm: Int?,                   // dbm of the primary measured RAT (LTE/WCDMA/GSM/NR)
    val nrRsrp: Int?, val nrRsrq: Int?, val nrSinr: Int?,            // NR ss* values (SA or NSA leg)
)

data class ProbeResult(val ok: Boolean, val ms: Long?)

data class SimSample(
    val slot: Int, val subId: Int,
    val carrier: String, val simPlmn: String?,
    val isDataSim: Boolean,
    val serviceState: String,        // IN_SERVICE / OUT_OF_SERVICE / EMERGENCY_ONLY / POWER_OFF / UNKNOWN
    val psRegistered: Boolean?,      // packet-switched (data) registration on WWAN
    val registeredPlmn: String?,     // TelephonyManager.networkOperator for that sub, "" -> null
    val networkType: String,         // dataNetworkType name: LTE, NR, HSPAP, UMTS, EDGE, GPRS, UNKNOWN…
    val serving: CellSnapshot?,
    val signal: SignalSnapshot,
    val probe: ProbeResult?,         // only for the data SIM, null otherwise
)

data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Float?, val ageS: Double, val speedKmh: Float?)

data class SampleRecord(val epochMs: Long, val location: LocationFix?, val sims: List<SimSample>)
```

### ServingCellSelector

`fun select(cells: List<CellSnapshot>, registeredPlmn: String?): CellSnapshot?`
1. `candidates = cells.filter { it.isRegistered }`. Empty → `null`.
2. If `registeredPlmn != null`: drop candidates whose `plmn != null && plmn != registeredPlmn` (dual-SIM devices sometimes leak the other SIM's serving cell into the list). Candidates with unknown PLMN are kept but ranked lower.
3. Rank: (a) PLMN equals `registeredPlmn`, (b) `isPrimaryServing`, (c) RAT order LTE > NR > WCDMA > GSM > OTHER (in NSA the LTE anchor is the primary serving cell). Return the best, else `null`.

### CsvFormat

Header (exact order — the analysis script depends on it):

```
timestamp_iso,epoch_ms,event,lat,lon,gps_acc_m,gps_age_s,speed_kmh,sim_slot,sub_id,carrier,sim_plmn,is_data_sim,service_state,ps_registered,reg_plmn,network_type,rat,cell_id,tac,pci,arfcn,bands,cell_age_s,level,rsrp,rsrq,sinr,rssi,dbm,nr_rsrp,nr_rsrq,nr_sinr,probe_ok,probe_ms,note
```

- `fun rows(record: SampleRecord, zone: ZoneId): List<String>` → one line per SIM, `event = SAMPLE`. If `sims` is empty, still emit **one** line with `event = NO_SIM` and the location filled — a gap in the log must never look like "no data".
- `fun markerRow(epochMs: Long, location: LocationFix?, note: String, zone: ZoneId): String` → `event = MARK`, SIM columns empty, `note` filled.
- `timestamp_iso`: `Instant.ofEpochMilli(epochMs).atZone(zone).toOffsetDateTime()` formatted with `DateTimeFormatter.ISO_OFFSET_DATE_TIME` after `truncatedTo(ChronoUnit.MILLIS)`.
- Numbers: `Locale.ROOT`, doubles lat/lon with 6 decimals, accuracy/age/speed 1 decimal. Booleans `1`/`0`. `null` → empty field. `bands` joined with `;`.
- RFC 4180 escaping: quote a field if it contains `,`, `"`, `\r` or `\n`; double inner quotes. Carrier names and notes go through the escaper.
- No trailing newline inside the returned strings; the writer adds `\n`.

## 5. Recorder

### RecorderRepository (object)
`val state: StateFlow<RecorderState>`; internal `update { }`.
```kotlin
data class RecorderState(
  val isRecording: Boolean = false,
  val fileName: String? = null,
  val startedAtMs: Long? = null,
  val sampleCount: Int = 0,
  val markerCount: Int = 0,
  val last: SampleRecord? = null,
  val gpsProviderEnabled: Boolean = true,
  val lastError: String? = null,
)
```

### RecorderService
Intent actions (constants in a companion): `ACTION_START` (extras `EXTRA_INTERVAL_MS: Long`, `EXTRA_PROBE: Boolean`), `ACTION_STOP`, `ACTION_MARK` (extra `EXTRA_NOTE: String`, default "brak internetu"). Static helpers `start(context, intervalMs, probe)`, `stop(context)`, `mark(context, note)`; `start` uses `ContextCompat.startForegroundService`, `stop`/`mark` use plain `startService` (service is already foreground).

`onStartCommand`:
- `ACTION_START`: if already recording → ignore. Verify `ACCESS_FINE_LOCATION` and `READ_PHONE_STATE` are granted; if not → set `lastError`, `stopSelf()`, return `START_NOT_STICKY`. Call `ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)` **before** any other work. Acquire `PARTIAL_WAKE_LOCK` (tag `"SignalLogger:rec"`, timeout 12 h). Open `CsvSessionWriter`, start `LocationTracker`, start `CellularProbe` if enabled, launch the sampling loop.
- `ACTION_STOP`: cancel loop, stop tracker/probe, close writer, release wakelock, update state `isRecording=false`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`.
- `ACTION_MARK`: if recording, write `markerRow` (under the writer mutex), increment `markerCount`.
- Return `START_NOT_STICKY`. `onDestroy` performs the same cleanup as STOP (idempotent). `onBind` returns null.

Scope: `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, cancelled in cleanup.

Sampling loop:
```kotlin
while (isActive) {
  val t0 = SystemClock.elapsedRealtime()
  val record = collector.collect(locationTracker.current(), probeOrNull)   // never throws; errors -> lastError
  writer.write(CsvFormat.rows(record, zone))
  RecorderRepository.update { copy(sampleCount = sampleCount + 1, last = record, gpsProviderEnabled = locationTracker.isGpsEnabled()) }
  updateNotification(record)
  delay((intervalMs - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(0))
}
```
Wrap one iteration in `try/catch (e: Exception)` (rethrow `CancellationException`) so one bad read never kills the session.

Notification (channel `recording`, `IMPORTANCE_LOW`, created in `SignalLoggerApp.onCreate`): small icon `ic_notification` (vector), title "Nagrywanie zasięgu", text = per-SIM one-liner e.g. `Orange LTE −97 dBm · Play brak usługi` + sample count, `setOngoing(true)`, `setOnlyAlertOnce(true)`, content intent → `MainActivity` (`FLAG_IMMUTABLE`), actions "Znacznik" (ACTION_MARK) and "Stop" (ACTION_STOP) via `PendingIntent.getService(..., FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)` with distinct request codes. Update via `NotificationManagerCompat.notify` only if `POST_NOTIFICATIONS` granted (API 33+) — guard with permission check.

### RadioCollector
Constructor takes `Context`. `suspend fun collect(location: LocationFix?, probe: CellularProbe?): SampleRecord`.
- `subscriptionManager.activeSubscriptionInfoList ?: emptyList()` (catch `SecurityException` → empty). Re-read every sample (SIM may be toggled).
- `dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()`.
- For every sub, in parallel (`coroutineScope { subs.map { async { … } }.awaitAll() }`), with `tm = telephonyManager.createForSubscriptionId(sub.subscriptionId)`:
  - `serviceState = tm.serviceState` (nullable). `serviceState.state` → text. `psRegistered = serviceState.networkRegistrationInfoList.any { it.domain and NetworkRegistrationInfo.DOMAIN_PS != 0 && it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.isRegistered }` (domain is a bitmask — use `and`). If `serviceState == null` → `"UNKNOWN"` / `null`.
  - `registeredPlmn = tm.networkOperator.takeIf { it.isNotBlank() }`.
  - `networkType = networkTypeName(tm.dataNetworkType)` — map the `TelephonyManager.NETWORK_TYPE_*` constants to short names (GPRS, EDGE, UMTS, HSDPA, HSUPA, HSPA, HSPAP, LTE, NR, IWLAN, UNKNOWN; anything else → the int as string).
  - Cells: `requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() { onCellInfo(list); onError(code, detail) })` wrapped in `suspendCancellableCoroutine`, guarded by `withTimeoutOrNull(2500)`; on timeout/error fall back to `tm.allCellInfo ?: emptyList()`. Executor: one `Executors.newSingleThreadExecutor()` owned by the collector (shut down in `close()`). Map each `CellInfo` to `CellSnapshot`:
    - `CellInfoLte` → `CellIdentityLte`: `ci`, `tac`, `pci`, `earfcn`, `bands.toList()`, `mccString`, `mncString`.
    - `CellInfoNr` → `cellIdentity as CellIdentityNr`: `nci` (Long), `tac`, `pci`, `nrarfcn`, `bands.toList()`, `mccString`, `mncString`.
    - `CellInfoWcdma` → `CellIdentityWcdma`: `cid`, `lac`, `psc`, `uarfcn`, no bands.
    - `CellInfoGsm` → `CellIdentityGsm`: `cid`, `lac`, `bsic`, `arfcn`, no bands.
    - other → `Rat.OTHER` with nulls.
    - `isPrimaryServing = cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING`; `ageS = (SystemClock.elapsedRealtime() - timestampMillis) / 1000.0` (`timestampMillis` is in the elapsed-realtime base).
    - Apply `validOrNull()` to every int/long field.
  - `serving = ServingCellSelector.select(snapshots, registeredPlmn)`.
  - Signal: `tm.signalStrength` (nullable) → `level`; iterate `cellSignalStrengths`: `CellSignalStrengthLte` → `rsrp`, `rsrq`, `rssnr`→sinr, `rssi`, `dbm`; `CellSignalStrengthNr` → `ssRsrp`, `ssRsrq`, `ssSinr` (and `dbm` if no LTE present); `CellSignalStrengthWcdma` / `CellSignalStrengthGsm` → `dbm`. All through `validOrNull()`.
  - `probe`: only if `sub.subscriptionId == dataSubId && probe != null` → `probe.run(timeoutMs)`; run it concurrently with the radio reads (it is the slowest part).
  - Wrap every Android call in `runCatching`; a `SecurityException` must degrade to null fields, never crash.
- `carrier = sub.carrierName?.toString() ?: sub.displayName?.toString() ?: "SIM${slot+1}"`, `simPlmn = (sub.mccString ?: "") + (sub.mncString ?: "")` or null if blank.
- Sort SIMs by slot.
- Permission annotations: these calls need runtime permissions; check `ContextCompat.checkSelfPermission` once at the top of `collect()` and annotate the private helpers with `@SuppressLint("MissingPermission")`.

### LocationTracker
- `LocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())`. Additionally, if `LocationManager.FUSED_PROVIDER` (API 31+) is among `allProviders`, request it too (helps inside the train when GPS is weak). Keep the most recent `Location` by `elapsedRealtimeNanos` in a `@Volatile` field. Seed with `getLastKnownLocation(GPS_PROVIDER)`.
- `fun current(): LocationFix?` → age from `SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos`, `accuracy` if `hasAccuracy()`, speed ×3.6 if `hasSpeed()`.
- `fun isGpsEnabled() = locationManager.isProviderEnabled(GPS_PROVIDER)`.
- `stop()` removes updates. Catch `SecurityException`.
- Location fix staleness is **not** filtered here — age goes into the CSV and is decided at analysis time.

### CellularProbe
- `start()`: `ConnectivityManager.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)`; `onAvailable` stores the `Network` in a `@Volatile` field, `onLost` clears it. This forces the probe over mobile data even when the phone is on the train's Wi-Fi.
- `suspend fun run(timeoutMs: Int): ProbeResult` on `Dispatchers.IO`: if no network → `ProbeResult(false, null)`. Else `network.openConnection(URL("https://connectivitycheck.gstatic.com/generate_204")) as HttpURLConnection`, `connectTimeout = readTimeout = timeoutMs`, `useCaches = false`, `instanceFollowRedirects = false`, measure `SystemClock.elapsedRealtime()` around `responseCode`; ok = code == 204; always `disconnect()`. Any exception → `ProbeResult(false, null)`.
- Timeout per sample: `min(3000, (intervalMs * 0.8).toInt())`.
- `stop()`: `unregisterNetworkCallback` in `runCatching`.

### CsvSessionWriter
- Directory `context.getExternalFilesDir("logs")` (create if missing). File `signal_yyyyMMdd_HHmmss.csv` (local time).
- `BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))`; write header on open; `suspend fun write(lines: List<String>)` and `writeLine` guarded by a `Mutex`, each followed by `flush()` so a killed process loses at most one sample. `close()` idempotent.

### SessionFiles
- `list(): List<SessionFile>` (name, sizeBytes, lastModified) sorted newest first.
- `delete(name)`.
- `shareIntent(name): Intent` → `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)`, `ACTION_SEND`, type `text/csv`, `EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`, wrapped in `Intent.createChooser(…, "Udostępnij log")`.

## 6. UI (Compose, Material 3, light MVI)

`MainContract.kt`:
```kotlin
data class UiState(
  val permissionsGranted: Boolean, val notificationsGranted: Boolean,
  val gpsEnabled: Boolean, val detectedSims: List<String>,   // "Slot 1: Orange", "Slot 2: Play"
  val intervalMs: Long = 5000, val probeEnabled: Boolean = true,
  val recorder: RecorderState = RecorderState(),
  val sessions: List<SessionFile> = emptyList(),
  val pendingDelete: String? = null,
)
sealed interface UiIntent { Start; Stop; Mark; SetInterval(ms); SetProbe(on); Share(name); AskDelete(name); ConfirmDelete; CancelDelete; Refresh; PermissionsChanged }
sealed interface UiEffect { LaunchIntent(intent) }
```
`MainViewModel(app: Application) : AndroidViewModel` — combines its own `MutableStateFlow` with `RecorderRepository.state`; effects through `Channel<UiEffect>(BUFFERED).receiveAsFlow()`; `onIntent(intent)` single entry point. `Refresh` re-reads permissions, GPS state, SIM list (guarded by permission) and session files; called from `ON_RESUME` (use `LifecycleResumeEffect` or `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` from lifecycle-runtime-compose) and after `Stop`. When recording stops (observe transition true→false), refresh sessions.

`MainScreen` (scrollable `Column` inside `Scaffold`, respect insets):
1. Title "Logger zasięgu".
2. If permissions missing: card explaining why (lokalizacja — wymagana do odczytu komórek i GPS; stan telefonu — lista kart SIM) + button "Nadaj uprawnienia" using `rememberLauncherForActivityResult(RequestMultiplePermissions)` for FINE+COARSE location, READ_PHONE_STATE and POST_NOTIFICATIONS (API 33+). Second button "Otwórz ustawienia aplikacji" (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).
3. Warnings: GPS off → "Włącz lokalizację" button (`Settings.ACTION_LOCATION_SOURCE_SETTINGS`). Fewer than 2 SIMs detected → "Aktywna jest tylko jedna karta SIM — włącz drugą, żeby porównać sieci." Button "Wyłącz optymalizację baterii" → `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (no extra permission needed).
4. Settings (disabled while recording): interval `FilterChip`s 2 s / 5 s / 10 s; `Switch` "Test internetu na karcie danych".
5. Big button "Start" / "Stop". While recording: "Znacznik: nie działa" button (sends Mark).
6. Live card while recording: elapsed time (ticking each second via `LaunchedEffect` loop), sample and marker counts, file name; GPS line (accuracy, age, speed or "brak fixa"); one row per SIM: carrier, state (polish: W zasięgu / Brak usługi / Tylko alarmowe / Wyłączona), RAT, RSRP / SINR (or NR values / dBm), cell id + PCI, data-SIM badge with probe result ("internet OK 123 ms" / "internet: brak").
7. "Zapisane sesje" list: name, size (KB), date; buttons "Udostępnij", "Usuń" (confirm `AlertDialog`).

Theme: simple Material 3 with dynamic color on API 31+, fallback light/dark schemes.

`MainActivity`: `enableEdgeToEdge()`; collect `UiEffect` and `startActivity` (catch `ActivityNotFoundException`).

## 7. Unit tests (`app/src/test`, JUnit 4, pure model only)
- `ServingCellSelectorTest`: picks PLMN match over a foreign registered cell; prefers primary serving; LTE over NR when both primary-less; unknown-PLMN single cell is returned; empty → null; no registered → null.
- `CsvFormatTest`: header column count == every row column count (count unquoted commas via a tiny CSV split helper in the test); null → empty field; escaping of `Play, "test"`; decimal dot regardless of default locale (set `Locale.setDefault(Locale.GERMANY)` in the test); `NO_SIM` row when sims empty; `MARK` row has note and empty SIM columns; `validOrNull` on `Int.MAX_VALUE`.

## 8. README.md (Polish)
What the app records; how to open/build in Android Studio (open folder → sync → Run; accept AGP upgrade if offered); pre-trip checklist (both SIMs active, system location ON, start before departure, charger/power bank, disable battery optimisation, phone not in a metal-lined bag); markers; how to export (Udostępnij → send the CSV); limitations (probe tests only the default data SIM — swap data SIM on the return trip to test the other one; Pendolino attenuates every network; values depend on the modem, some fields can be empty); full column dictionary.

## 9. Self-review checklist (do this before finishing)
- Every Android API used exists at minSdk 30 or is guarded by `Build.VERSION.SDK_INT` (FUSED_PROVIDER = 31, POST_NOTIFICATIONS = 33).
- `startForeground` is called with the `location` type, within the first `onStartCommand`, and the manifest declares `FOREGROUND_SERVICE_LOCATION`.
- All `PendingIntent`s have `FLAG_IMMUTABLE`.
- No `android.*` import in `model/`.
- Header column count matches row builders exactly (36 columns).
- No blocking IO on the main thread; Location listener on main looper only updates a volatile field.
- Every resource referenced (`@drawable/ic_notification`, `@mipmap/ic_launcher`, `@xml/file_paths`, theme) exists.
- `CancellationException` is never swallowed.
