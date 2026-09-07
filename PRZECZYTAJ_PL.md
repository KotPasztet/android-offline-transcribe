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
