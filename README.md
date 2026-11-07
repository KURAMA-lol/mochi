# Mochi - floating chibi AI companion for Android

Nana (LivLiv character, rendered with LivLiv Player) floats over all your apps, wanders around, and talks to you (text or voice) through **any AI APIs you plug in**, with an advanced routing hub.

## Features
- Floating character (Nana, moods: happy / shy / sad / angry / sleepy / smug / excited), walks, sits, sleeps, waves
- Drag her anywhere, tap to chat, long-press to pat her
- Call her out: notification button, Quick Settings tile, home-screen icon "Call Mochi"
- Voice input (speech recognizer) and spoken replies (cute high-pitch TTS)
- **API hub**: OpenAI-compatible (OpenAI, Groq, OpenRouter, DeepSeek, Together, Mistral, Cerebras, Ollama/local, your own wrappers), Anthropic, Gemini
- **Routing**: priority+failover, round-robin, random, weighted, fastest (learns latency); rules by regex / length / voice -> prefer a tag or provider; retries; circuit breaker with cooldown; route tester; per-provider stats
- Personality prompt, name, size, voice all editable

## Credits
Characters: LivLiv (livliv.ai). Nana is used under the LivLiv Featured Characters License (personal, non-commercial). The CI build downloads her and LivLiv Player (Apache-2.0) from github.com/livliv-dev/player; they are not stored in this repo. Keep this repo private.

## Build
GitHub Actions builds the APK on every push and publishes it at Releases > latest-build > mochi.apk.
The app is signed with a fixed key (app/debug.keystore) so every build installs as an update.
