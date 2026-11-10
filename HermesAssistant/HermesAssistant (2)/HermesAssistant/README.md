# Hermes — personal voice assistant for your own phone

A small Android app (Kotlin) that acts like a lightweight Jarvis/Assistant
clone, scoped to your own device, with real hands-free wake-word detection.

## Commands it understands
- `"Hermes call Dad"` — looks up "Dad" in your contacts, places the call.
  After the call ends it checks the call log and tells you out loud
  whether the other person answered or not.
- `"Hermes message Mom I'm on my way"` / `"Hermes text Dad running late"` —
  sends that exact text as an SMS.
- `"Hermes open Spotify"` / `"Hermes launch Instagram"` — opens any
  installed app by its visible name.
- `"Hermes set a timer for 5 minutes"` — hands off to your phone's own
  Clock app.
- `"Hermes search best pizza near me"` / `"Hermes google <x>"` — web search.
- `"Hermes flashlight on"` / `"Hermes flashlight off"` / `"Hermes torch on"`
- `"Hermes what's the time"` / `"Hermes battery"` — spoken answers.

Add more verbs in `CommandParser.kt` + `ActionExecutor.kt` — the only two
files you need to touch for a new command.

## Listening modes
1. **Tap-to-talk** — tap the mic button, or the notification's "Listen"
   action. One phrase, one action.
2. **Background wake word** ("Listen in background (screen off too)"
   switch) — powered by **Picovoice Porcupine**, the same category of
   on-device keyword-spotting model real assistants use. It sits almost
   idle until it hears "Hermes," then switches on full speech recognition
   only for the command that follows. This is dramatically cheaper on
   battery than looping full speech recognition continuously.

## Smarter command understanding (optional)
If you set `OPENROUTER_API_KEY`, Hermes falls back to an LLM (via
openrouter.ai) whenever your exact phrasing doesn't match one of the
rule-based patterns above — so "hey can you ring up dad real quick"
still works, not just the exact "Hermes call Dad". Only the one phrase
you just said is sent to OpenRouter; nothing else on the phone is. Leave
the key blank and Hermes just uses the rule-based parser only — every
command in this README still works without it.

## One-time setup for the wake word (do this before building)

### 1. Get a free AccessKey
Sign up at **console.picovoice.ai** with any email — a normal Gmail
address is fine, no company/work email needed — and copy your AccessKey
from the dashboard (free tier is enough for personal use).

### 2. Put the key somewhere safe — not in chat, not in source
In the project root, copy `local.properties.example` to `local.properties`
(Android Studio usually creates this file for you already — just open it
and add a line) and set:

```
PICOVOICE_ACCESS_KEY=your-real-key-here
```

`local.properties` is already in `.gitignore`, so the key never gets
committed or shared, and the app reads it at build time via `BuildConfig`.

### 3. Train the "Hermes" wake word
Porcupine's free built-in words don't include "Hermes," so you train a
custom one (takes about 2 minutes, no ML knowledge needed):
1. In Picovoice Console, go to **Porcupine → Create Wake Word**.
2. Type `Hermes`, choose **Android** as the platform and **English**
   as the language.
3. Click train, then download the resulting `.ppn` file.
4. Rename it to exactly `hermes_keyword.ppn` and drop it into
   `app/src/main/assets/` (replacing the placeholder `.txt` file there).

That's it — build and run. If either the key or the `.ppn` file is
missing, Hermes says so out loud and turns the background switch back
off instead of silently failing.

## What this is NOT
This only touches **your own phone's own contacts, call, SMS, and system
APIs** — the same permissions Google Assistant or Siri use. It can't:
- Access anyone else's account, inbox, DMs, Discord, Telegram, or YouTube.
- "Hack" anything, or locate a person's live position.
- Call or message someone without you giving the command.

Those were part of the original ask and were left out on purpose — they'd
need unauthorized access to other people's accounts/devices.

## Getting the APK — two ways

### Option A: no Android Studio at all (recommended if you're new to this)
This project includes a GitHub Actions workflow that builds the APK for
you in the cloud every time you push the code. You only need a free
GitHub account.

1. Create a new **private** repo on github.com (e.g. `hermes-assistant`)
   and push this folder's contents to it (GitHub's "upload files" button
   in the browser works fine — drag the whole `HermesAssistant` folder
   contents in, no `git` command-line needed).
2. In that repo, go to **Settings → Secrets and variables → Actions →
   New repository secret** and add:
   - `PICOVOICE_ACCESS_KEY` = your key from console.picovoice.ai
   - `OPENROUTER_API_KEY` = your OpenRouter key (optional — skip if you
     don't want the smarter fallback parsing)
   These secrets are encrypted by GitHub, never shown in logs, and
   only used inside that one build step.
3. Go to the **Actions** tab — a "Build Hermes APK" run starts
   automatically. Wait for the green check (a few minutes).
4. Open that run, scroll to **Artifacts**, download **Hermes-debug-apk**
   (a `.zip` containing `app-debug.apk`).
5. Transfer that `.apk` to your phone (email it to yourself, Google
   Drive, WhatsApp, whatever's easiest) and tap it to install.
   Android will ask you to allow "install from this source" the first
   time — that's expected for any app not from the Play Store.
6. To rebuild after making code changes, just push again — the workflow
   re-runs and a new APK shows up in a new run's Artifacts.

Don't have the `.ppn` wake-word file yet? Still fine — push anyway; the
app builds and tap-to-talk + all commands work immediately, background
wake-word just won't arm itself until you add that file (see below) and
push again.

### Option B: Android Studio, if you'd rather build locally
1. Install **Android Studio** (free, developer.android.com).
2. Open the `HermesAssistant/` folder as a project, let Gradle sync
   (this pulls the Porcupine SDK — needs internet once).
3. Do the wake-word setup above.
4. Plug in your phone (Developer Options → USB debugging) and hit **Run**,
   or **Build > Build Bundle(s)/APK(s) > Build APK(s)** to sideload.

## First run
Hermes asks for: microphone, phone, contacts, SMS, call log, and
"display over other apps." All are required for the commands above.
Turning on background listening also prompts you to exempt Hermes from
battery optimization — accept that, or aggressive OEM battery managers
(MIUI, OnePlus, etc.) may still kill the service after a while even with
Porcupine's lighter footprint.

## Files worth knowing
- `CommandParser.kt` / `ActionExecutor.kt` — add new voice commands here.
- `PorcupineWakeService.kt` — the wake-word + background listening engine.
- `HermesListenerService.kt` — tap-to-talk, one-shot listening.
- `HermesWakeService.kt` — legacy fallback (plain SpeechRecognizer loop,
  no Picovoice account needed, but much heavier on battery); unused
  unless you wire it back in yourself.
