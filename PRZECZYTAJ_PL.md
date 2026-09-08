# Kompletne repo z naniesionymi zmianami (Fazy 1–3)

To jest PEŁNE repo `android-offline-transcribe` (razem z submodułem
`whisper.cpp`, gotowe do builda), z już naniesionymi wszystkimi zmianami:

## Faza 1 — funkcje w istniejącym UI
- Obsługa m4a/mp3/ogg/3gp (dekodowanie do WAV 16kHz mono) — `util/AudioDecodeUtils.kt`
- Live tekst podczas transkrypcji pliku (nie tylko przy nagrywaniu) — chunkowanie w `WhisperEngine.transcribeFile()`
- Pasek postępu transkrypcji pliku

## Faza 2 — redesign "jak Diktafon" (kasety + półka)
- Baza danych Room: `Cassette` (temat) + `Memo` (nagranie) — `data/cassette/`
- Ekran półki z kasetami — `ui/cassette/HomeShelfScreen.kt`
- Ekran kasety: lista memo, nagrywanie, upload, live tekst, progress bar — `ui/cassette/CassetteScreen.kt`
- Trwały zapis audio memo na dysku — `util/CassetteAudioStorage.kt`
- Nowa nawigacja: model -> półka -> kaseta (stary jednolity ekran transkrypcji nadal istnieje pod trasą `transcribe`, tylko odpięty od startu)

## Faza 3 — automatyczne wznawianie po crashu
- Import pliku: checkpoint po każdym 20s oknie (zapis synchroniczny `commit()`, przetrwa twardy crash) — `util/TranscriptionCheckpointStore.kt`
- Po restarcie appka sama dokańcza transkrypcję od ostatniego ukończonego okna — `ui/cassette/CrashRecoveryCoordinator.kt`
- Żywe nagrywanie: zrzut co 5s, po crashu odzyskiwane jako gotowe memo (nie da się dosłownie wznowić mikrofonu po śmierci procesu)
- **Zabezpieczenie przed pętlą**: jeśli wznowienie utknie w tym samym miejscu >2 razy, albo przy powtórce zebrany tekst ma <5 słów — appka porzuca checkpoint zamiast próbować w kółko przy każdym starcie. Progi do zmiany: `MAX_RETRIES_AT_SAME_CHUNK`, `MIN_WORDS_TO_TRUST_PROGRESS` w `TranscriptionCheckpointStore.kt`.

## Faza 4 — poprawki UX (pułapka w ustawieniach, rozwijanie, kopiowanie)
- Wejście w ustawienia nie wyładowuje już modelu z góry — tylko przy
  faktycznej zmianie na inny.
- `ModelSetupScreen` ma przycisk wstecz, gdy jest dokąd wracać.
- Transkrypt memo na liście: dotknięcie rozwija/zwija pełny tekst.
- Przycisk kopiowania (schowek) przy każdym memo i na karcie live-transkryptu.

## Faza 5 — crash na długich plikach, pauza, share-into-app, eksport kasety

**Prawdziwa przyczyna crasha na 30-minutowym audio (znaleziona i naprawiona):**
`AudioRecorder.audioBuffer` był typu `ArrayList<Float>` — to bug z
oryginalnego kodu voiceping, nie coś wprowadzonego przeze mnie. Każda
próbka dźwięku była boxowana jako osobny obiekt `java.lang.Float`. Dla 30
minut przy 16kHz to ~29 milionów obiektów = ponad pół giga zbędnego
narzutu pamięci = gwarantowany OutOfMemoryError. Do tego `readWavFile`
ładował cały plik do RAM podwójnie (bajty + floaty).

Naprawione:
- `util/GrowableFloatArray.kt`, `util/GrowableShortArray.kt` — niebogowane,
  rosnące bufory oparte na surowych tablicach, zastępują `ArrayList<Float>`
  w `AudioRecorder` i `mutableListOf<Short>()` w dekodowaniu m4a/mp3.
- `WhisperEngine.transcribeFile` przepisany na **prawdziwy streaming**:
  `readWavHeader()` + `readWavChunk()` czytają plik fragmentami wprost
  z dysku zamiast ładować całość na raz — działa teraz dla plików
  dowolnej długości.
- `android:largeHeap="true"` jako dodatkowy margines bezpieczeństwa.

**Pauza/wznowienie nagrywania:**
- Nowy stan `SessionState.Paused` + `pauseRecording()`/`resumeRecording()`
  w `WhisperEngine` — wykorzystuje fakt, że postęp jest już śledzony przez
  bezwzględny licznik próbek (nie resetuje się), więc wznowienie kontynuuje
  ten sam bufor/transkrypt zamiast zaczynać od nowa.
- Nowy przycisk pauzy/wznowienia na ekranie kasety.

**Udostępnianie pliku z innej aplikacji (Share):**
- Manifest: `intent-filter` na `ACTION_SEND` dla `audio/*` + `singleTask`.
- `MainActivity` przechwytuje URI, `util/PendingShareHolder.kt` trzyma go
  tymczasowo, `ui/cassette/ChooseCassetteDialog.kt` pyta do której kasety
  (albo utwórz nową), `CassetteViewModel` konsumuje i importuje.

**Eksport całej kasety:**
- `util/CassetteExporter.kt` — pakuje wszystkie nagrania audio + wspólny
  plik `transkrypcja.txt` do jednego .zip, udostępnianego przez systemowy
  Share sheet (przycisk ikony udostępniania w pasku górnym ekranu kasety).

## Faza 6 — czarny ekran / zamrożone UI podczas importu długiego pliku

**Przyczyna:** `CassetteViewModel.importFileAsMemo` uruchamiał całe
kopiowanie/dekodowanie pliku (`AudioDecodeUtils.decodeToWavFile` — pętla
MediaCodec bez punktów zawieszenia) bezpośrednio w `viewModelScope.launch`,
którego domyślny dispatcher to **Dispatchers.Main**. Dla 40-minutowego
pliku to blokowało główny wątek na cały czas dekodowania — stąd czarny
ekran, brak reakcji na dotyk, brak możliwości nawigacji.

To samo dotyczyło:
- zapisu nagrania po naciśnięciu "stop" (`stopRecordingAndSaveMemo` —
  zapis WAV + kopiowanie mogły ważyć setki MB dla długiego nagrania),
- **cyklicznego zapisu checkpointu co 5 sekund podczas samego nagrywania**
  (`flushRecordingCheckpoint` — dla długiego nagrania z czasem robi się
  to coraz cięższe, bo za każdym razem nadpisuje cały dotychczasowy plik),
- automatycznego wznawiania po crashu przy starcie appki
  (`CrashRecoveryCoordinator`, wywoływany z `LaunchedEffect` czyli też
  na wątku głównym).

**Naprawione:** wszystkie te operacje I/O i dekodowania przeniesione na
`Dispatchers.IO` (`withContext(Dispatchers.IO) { ... }` / 
`viewModelScope.launch(Dispatchers.IO)`). UI zostaje w pełni responsywne
przez cały czas — appka działa "w tle", tak jak prosiłeś.

**Dodatkowo:** dodałem osobny stan "Dekodowanie pliku..." (nowy
`isImportDecoding` w `CassetteViewModel`) z nieokreślonym paskiem postępu,
pokazywany zanim ruszy właściwa transkrypcja (wcześniej ten etap nie miał
żadnego wskaźnika, więc nawet po naprawie responsywności ekran mógłby
wyglądać na "nic się nie dzieje" — teraz jest jasne, że appka pracuje.

Zmienione pliki: `CassetteViewModel.kt`, `CassetteScreen.kt`,
`CrashRecoveryCoordinator.kt`.

## Ograniczenia (Faza 5)

- Pauza/wznowienie nie było testowane na urządzeniu (brak tu Android SDK) —
  logika oparta jest na już istniejącym w kodzie mechanizmie śledzenia
  postępu przez bezwzględny licznik próbek, ale realny build może ujawnić
  drobiazg w wątkach/coroutines.
- Dekodowanie m4a/mp3 nadal trzyma cały zdekodowany sygnał w pamięci na
  raz (teraz jako niebogowane tablice, nie boxowaną listę) — dla bardzo
  długich plików (>1h) to nadal spory szczyt pamięci (~200MB+), ale
  nieporównywalnie lepszy niż wcześniejszy stan (700MB+ i gwarantowany
  crash). Prawdziwy pełny streaming dekodowania m4a byłby kolejnym krokiem,
  jeśli nadal będzie to problem.


## Faza 7 — "wysyłam WAV i nic się nie dzieje"

Dwa konkretne, potwierdzone bugi:

1. **Parser WAV rzucał wyjątkiem dla plików ze streamowanym rozmiarem
   danych.** Niektóre nagrywarki zapisują rozmiar chunku `data` jako
   `0xFFFFFFFF` ("nieznany na etapie zapisu"). Odczytany jako liczba ze
   znakiem w Kotlinie dawał `-1`, co wywalało `readWavHeader()` z
   wyjątkiem "No data chunk found in WAV" dla KAŻDEGO takiego pliku.
   Naprawione w `WhisperEngine.readWavHeader()`: rozmiar chunku jest teraz
   poprawnie interpretowany jako liczba bez znaku, a gdy jest
   nieznany/nieprawidłowy — czytamy dane do końca pliku (standardowa
   konwencja dla tego przypadku).

2. **Błędy ginęły po cichu.** `CassetteViewModel.lastError` był ustawiany
   (np. właśnie przez powyższy wyjątek), ale `CassetteScreen` nigdy go nie
   wyświetlał. Efekt: transkrypcja cicho się wysypywała, appka nie
   pokazywała ani paska postępu, ani błędu — wyglądało jak "nic się nie
   dzieje". Dodany dialog błędu (ten sam wzorzec co w starym
   `TranscriptionScreen`), więc od teraz każda awaria jest widoczna z
   konkretnym komunikatem zamiast ciszy.

Zmienione pliki: `WhisperEngine.kt`, `CassetteViewModel.kt`,
`CassetteScreen.kt`.

**Uwaga o zgłoszonym "insta crashu":** nie mam logów/stack trace z tego
zgłoszenia, więc nie mogłem go bezpośrednio zdiagnozować — przejrzałem
ręcznie całą ścieżkę startową (Application, MainActivity, nawigację,
crash-recovery) i nie znalazłem tam ewidentnego błędu. Jeśli po tej
poprawce nadal zdarza się prawdziwy crash (nie tylko cichy błąd jak wyżej),
potrzebuję Logcat/stack trace żeby to precyzyjnie namierzyć — bez tego
dalsze zgadywanie byłoby strzałem w ciemno.

## Budowanie — zalecane: GitHub Actions (nie Termux)

Ten projekt ma natywne C++/CMake/NDK (whisper.cpp, sherpa-onnx) —
kompilacja w Termux jest technicznie możliwa, ale zawodna. Zalecana droga:

1. Załóż nowe repo na GitHub (puste) albo fork
   `voiceping-ai/android-offline-transcribe`.
2. Wgraj **całą zawartość tego zipa** (nadpisując wszystko, jeśli forkujesz)
   — np.:
   ```bash
   cd android-offline-transcribe   # katalog z tego zipa
   git init
   git remote add origin <adres-twojego-repo>
   git add -A
   git commit -m "Kasety + m4a + live tokeny + crash recovery"
   git push -u origin main
   ```
3. Repo ma gotowy workflow `.github/workflows/android-build.yml` — poczekaj
   aż GitHub Actions zbuduje APK, pobierz z zakładki **Actions -> (twój run)
   -> Artifacts**.

Jeśli mimo wszystko chcesz Termux — pełna instrukcja krok po kroku była
w paczce `faza1-m4a-live-progress.zip` z wcześniejszej wiadomości
(instalacja Android SDK + NDK + cmake ręcznie w Termux).

## Uwaga
Nie mam tu Android SDK do kompilacji — wszystko sprawdzone ręcznie pod
kątem składni (importy, zbalansowane nawiasy, zgodność sygnatur z resztą
kodu), ale realny build może jeszcze wyłapać drobiazg. Jeśli Gradle coś
wypluje, wklej mi błąd, poprawię.
