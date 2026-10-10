# SignalLogger — Logger zasięgu

Aplikacja na Androida do nagrywania zasięgu sieci komórkowej podczas podróży pociągiem. Co **N sekund** zapisuje pozycję GPS oraz — **osobno dla każdej aktywnej karty SIM** — stan rejestracji w sieci, obsługującą komórkę i siłę sygnału. Opcjonalnie sprawdza też, czy na karcie danych działa prawdziwy internet. Wynik to plik CSV, który można udostępnić i przeanalizować po podróży (np.: gdzie obie sieci tracą zasięg jednocześnie?).

## Co jest nagrywane

Jedna próbka co 2 / 5 / 10 s (do wyboru), a w niej:

- **pozycja GPS** — współrzędne, dokładność, wiek fixa, prędkość (GPS oraz dostawca „fused”, jeśli system go udostępnia),
- **dla każdej aktywnej karty SIM osobny wiersz**:
  - stan usługi (w zasięgu / brak usługi / tylko alarmowe / wyłączona), rejestracja w sieci pakietowej,
  - sieć, w której karta jest zarejestrowana, typ sieci danych (LTE, NR, HSPA+…),
  - obsługująca komórka: identyfikator komórki, TAC, PCI, kanał (ARFCN), pasma, wiek odczytu,
  - sygnał: poziom 0–4, RSRP / RSRQ / SINR / RSSI (LTE), wartości NR, dBm,
- **test internetu** (jeden na próbkę, zapisany przy karcie, której sieć go przeniosła): zapytanie HTTPS `generate_204` wymuszone przez sieć komórkową, nawet gdy telefon jest podłączony do Wi-Fi pociągu — wynik OK/brak oraz czas odpowiedzi,
- **znaczniki** wstawiane ręcznie przyciskiem („nie działa” — brak internetu).

Gdy żadna karta SIM nie jest aktywna, w pliku i tak pojawia się wiersz `NO_SIM` z pozycją — przerwa w logu nigdy nie wygląda jak „brak danych”.

Nagrywanie działa jako usługa pierwszoplanowa (z powiadomieniem, przyciskami „Znacznik” i „Stop”) i trzyma procesor aktywny (wake lock do 12 h), więc ekran może być wyłączony.

## Budowanie i instalacja

**GitHub Actions (zalecane).** APK jest budowany automatycznie przy każdym pushu na `main` (workflow `.github/workflows/build.yml` uruchamia testy jednostkowe i `assembleDebug`) i publikowany w zakładce **Releases** repozytorium: tag `build-N` (N = numer przebiegu), plik `SignalLogger-debug.apk`. Wystarczy pobrać plik na telefon i zainstalować.

> To jest **debugowy** APK, więc instalacja wymaga zezwolenia na instalowanie aplikacji z nieznanych źródeł (Android zapyta o to przy otwarciu pliku — zezwól dla przeglądarki lub menedżera plików, z którego instalujesz).

**Aktualizacja aplikacji.** Od wersji 1.1 kompilacje są podpisywane stałym kluczem zapisanym w repozytorium (`app/debug.keystore`), więc nowe wersje instalują się na starych bez odinstalowywania i bez utraty zapisanych logów. Przejście z wersji 1.0 wymaga jednak odinstalowania jej wcześniej (była podpisana innym, automatycznie wygenerowanym kluczem) — a to **usuwa zapisane logi**, więc najpierw je wyeksportuj (przycisk „Udostępnij”). Klucz jest publiczny, więc to rozwiązanie wystarcza dla prywatnej aplikacji, ale nie do publikacji w sklepie.

**Lokalnie.** Otwórz folder projektu w Android Studio (File → Open), poczekaj na synchronizację Gradle i uruchom aplikację (Run). Jeśli Android Studio zaproponuje aktualizację wtyczki Android Gradle Plugin, możesz ją zaakceptować. Z wiersza poleceń:

```
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # testy jednostkowe (czysty model: wybór komórki, format CSV)
```

Wymagania: JDK 17, Android SDK 35. Aplikacja działa na Androidzie 11 (API 30) i nowszym; wymaga telefonu z modułem telefonii.

## Lista kontrolna przed podróżą

1. **Obie karty SIM aktywne** (Ustawienia → Połączenia / Menedżer kart SIM). Aplikacja pokaże ostrzeżenie, gdy wykryje tylko jedną.
2. **Lokalizacja systemowa włączona** (GPS). Aplikacja ostrzeże, jeśli jest wyłączona.
3. Nadaj uprawnienia: dokładna lokalizacja, stan telefonu, powiadomienia (przycisk „Nadaj uprawnienia”). Przy pytaniu o lokalizację wybierz **dokładną** — przybliżona nie wystarczy do odczytu komórek.
4. **Uruchom nagrywanie przed odjazdem** (najlepiej jeszcze na peronie, żeby GPS zdążył złapać fix).
5. **Ładowarka lub powerbank** — GPS + modem + zapis to spore obciążenie baterii.
6. **Wyłącz optymalizację baterii** dla aplikacji (przycisk „Wyłącz optymalizację baterii” otwiera odpowiednie ustawienia systemu; wybierz „Wszystkie aplikacje” → Logger zasięgu → Nie optymalizuj). Niektórzy producenci mają dodatkowe „oszczędzanie” — warto je też wyłączyć dla tej aplikacji.
7. **Telefon nie w torbie z metalową wyściółką** i nie głęboko w plecaku — najlepiej przy oknie lub na stoliku.
8. Zostaw domyślne ustawienia (5 s, test internetu włączony), chyba że masz powód, by je zmienić. Ustawień nie da się zmienić w trakcie nagrywania.

## Znaczniki

Przycisk **„Znacznik: nie działa”** (w aplikacji lub w powiadomieniu) dopisuje do pliku wiersz `MARK` z bieżącą pozycją GPS i notatką „brak internetu”. Naciśnij go, gdy faktycznie nie działa Ci internet (np. strona się nie ładuje) — to subiektywna „prawda” do porównania z pomiarami telefonu.

## Eksport

Po zatrzymaniu nagrywania plik pojawia się na liście **„Zapisane sesje”**. Przycisk **„Udostępnij”** otwiera systemowe okno udostępniania — wyślij CSV mailem, komunikatorem albo zapisz na dysku. Pliki leżą też w pamięci aplikacji: `Android/data/pl.com.chodera.signallogger/files/logs/` (nazwa `signal_RRRRMMDD_GGMMSS.csv`, czas lokalny). Każda próbka jest od razu zapisywana na dysk, więc zabicie aplikacji traci co najwyżej jedną próbkę.

## Ograniczenia

- **Test internetu dotyczy tylko jednej karty naraz** — tej, przez której sieć komórkową telefon aktualnie przesyła dane (`is_active_data = 1`; przy automatycznym przełączaniu danych może to być inna karta niż domyślna, `is_data_sim`). Gdy system nie podaje, która to karta, wynik trafia do karty aktywnej dla danych, a w ostateczności do domyślnej. Żeby przetestować drugą sieć, na trasie powrotnej zmień kartę danych w ustawieniach telefonu. Dla karty bez testu zostają pomiary radiowe i rejestracja, ale nie wiadomo, czy „internet naprawdę działa”.
- Pomiar sprawdza łączność z jednym serwerem (`connectivitycheck.gstatic.com`); to dobry wskaźnik, ale nie dowód, że działa każda usługa.
- **Pendolino i inne nowoczesne składy tłumią każdą sieć** (szyby z powłoką, metalowy kadłub) — bezwzględne wartości dBm w pociągu będą gorsze niż na zewnątrz; porównuj sieci *względem siebie*, nie z wartościami „książkowymi”.
- **Wartości zależą od modemu i producenta.** Niektóre pola mogą być puste (np. SINR, pasma, RSRQ) albo odświeżane rzadko; `cell_age_s` pokazuje, jak stary jest odczyt komórki.
- Telefony dwukartowe czasem mieszają komórki obu kart w jednej liście — aplikacja filtruje je po sieci rejestracji, ale w rzadkich przypadkach wiersz może mieć puste pola komórki.
- W trybie 5G NSA pole `rat` pokazuje kotwicę LTE, a wartości 5G są w kolumnach `nr_*`.
- Wiek fixa GPS (`gps_age_s`) **nie jest filtrowany** przy nagrywaniu — w tunelu będzie rosnąć; decyzję „czy ta pozycja jest jeszcze wiarygodna” podejmujesz dopiero przy analizie.

## Format CSV — słownik kolumn

Kodowanie UTF-8, separator `,`, kropka dziesiętna, wartości puste = brak danych, pola z przecinkiem / cudzysłowem / znakiem nowej linii w cudzysłowie (RFC 4180). **37 kolumn**, w tej kolejności. Wiersz `SAMPLE` powstaje osobno dla każdej karty SIM — wiersze jednej próbki mają ten sam `epoch_ms` i pozycję.

| # | Kolumna | Opis |
|---|---|---|
| 1 | `timestamp_iso` | Czas próbki, ISO-8601 z przesunięciem strefy (czas lokalny telefonu), dokładność do ms. Ułamek sekundy bywa pomijany, gdy wynosi 0 — kanonicznym czasem jest `epoch_ms`. |
| 2 | `epoch_ms` | Czas Unix w milisekundach (UTC). |
| 3 | `event` | `SAMPLE` (pomiar karty), `NO_SIM` (brak aktywnej karty), `MARK` (znacznik użytkownika). |
| 4 | `lat` | Szerokość geograficzna (stopnie, 6 miejsc). |
| 5 | `lon` | Długość geograficzna (stopnie, 6 miejsc). |
| 6 | `gps_acc_m` | Szacowana dokładność pozycji w metrach. |
| 7 | `gps_age_s` | Wiek fixa w chwili zapisu próbki (s). Duża wartość = brak świeżego fixa. |
| 8 | `speed_kmh` | Prędkość z GPS (km/h). |
| 9 | `sim_slot` | Indeks slotu karty liczony **od 0** (0 = „Slot 1”). |
| 10 | `sub_id` | Identyfikator subskrypcji Androida. |
| 11 | `carrier` | Nazwa operatora karty. |
| 12 | `sim_plmn` | MCC+MNC karty SIM (np. `26003`). |
| 13 | `is_data_sim` | `1` — karta jest domyślną kartą do transmisji danych, `0` — nie. |
| 14 | `is_active_data` | `1` — przez tę kartę telefon w tej chwili przesyła dane (może różnić się od domyślnej przy automatycznym przełączaniu danych), `0` — nie. |
| 15 | `service_state` | `IN_SERVICE`, `OUT_OF_SERVICE`, `EMERGENCY_ONLY`, `POWER_OFF`, `UNKNOWN`. |
| 16 | `ps_registered` | `1` — karta jest zarejestrowana w domenie pakietowej (dane) w sieci komórkowej, `0` — nie. |
| 17 | `reg_plmn` | PLMN sieci, w której karta jest aktualnie zarejestrowana (`TelephonyManager.networkOperator`). Różni się od `sim_plmn` w roamingu. |
| 18 | `network_type` | Typ sieci danych: `LTE`, `NR`, `HSPAP`, `HSPA`, `HSDPA`, `HSUPA`, `UMTS`, `EDGE`, `GPRS`, `IWLAN`, `UNKNOWN` (inne wartości jako liczba). |
| 19 | `rat` | Technologia obsługującej komórki: `GSM`, `WCDMA`, `LTE`, `NR`, `OTHER`. |
| 20 | `cell_id` | Identyfikator komórki: LTE — `ci`, NR — `nci`, WCDMA/GSM — `cid`. |
| 21 | `tac` | LTE/NR — TAC; WCDMA/GSM — LAC. |
| 22 | `pci` | LTE/NR — PCI; WCDMA — PSC; GSM — BSIC. |
| 23 | `arfcn` | LTE — EARFCN; NR — NR-ARFCN; WCDMA — UARFCN; GSM — ARFCN. |
| 24 | `bands` | Numery pasm rozdzielone `;` (np. `3;20`); tylko LTE/NR. |
| 25 | `cell_age_s` | Wiek odczytu komórki w sekundach. |
| 26 | `level` | Poziom sygnału systemu, 0–4. |
| 27 | `rsrp` | LTE RSRP (dBm). |
| 28 | `rsrq` | LTE RSRQ (dB). |
| 29 | `sinr` | LTE SINR / RSSNR (dB). |
| 30 | `rssi` | LTE RSSI (dBm). |
| 31 | `dbm` | Moc sygnału (dBm) głównej technologii: LTE, a przy jej braku NR, WCDMA lub GSM. |
| 32 | `nr_rsrp` | NR SS-RSRP (dBm) — samodzielne 5G albo część 5G w NSA. |
| 33 | `nr_rsrq` | NR SS-RSRQ (dB). |
| 34 | `nr_sinr` | NR SS-SINR (dB). |
| 35 | `probe_ok` | Test internetu: `1` — odpowiedź HTTP 204, `0` — niepowodzenie. Wynik jest zapisany w wierszu tej karty, której sieć przeniosła test (zwykle karta z `is_active_data = 1`); na pozostałych kartach i przy wyłączonym teście pole jest puste. |
| 36 | `probe_ms` | Czas odpowiedzi testu w ms (puste, gdy brak odpowiedzi). Tak samo jak `probe_ok`: wiersz karty, której sieć przeniosła test. |
| 37 | `note` | Notatka znacznika (tylko `MARK`). |

Wskazówka do analizy: łącz wiersze po `epoch_ms`; „obie sieci bez zasięgu” to próbki, w których dla obu kart `service_state != IN_SERVICE` albo sygnał (`rsrp` / `dbm`) jest poniżej Twojego progu, a dla karty, która przenosiła dane (`is_active_data = 1`), dodatkowo `probe_ok = 0`.

## Struktura projektu

```
app/src/main/java/pl/com/chodera/signallogger/
  model/      czysty Kotlin (bez android.*): modele, wybór komórki obsługującej, format CSV
  recorder/   usługa pierwszoplanowa, odczyt telefonii, GPS, test internetu, zapis plików
  ui/         Compose + Material 3, lekkie MVI (UiState / UiIntent / UiEffect)
app/src/test/ testy jednostkowe modelu (JUnit 4)
```
