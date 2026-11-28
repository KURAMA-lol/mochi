# Nana - floating chibi AI companion for Android

Nana (LivLiv character, rendered with LivLiv Player) floats over all your apps, wanders around, and talks to you (text or voice) through **any AI APIs you plug in**, with an advanced routing hub.

## Features
- Floating character (Nana, moods: happy / shy / sad / angry / sleepy / smug / excited), walks, sits, sleeps, waves
- Drag her anywhere, tap to chat, long-press to pat her
- Call her out: notification button, Quick Settings tile, home-screen icon "Call Nana"
- Voice input (speech recognizer) and spoken replies (cute high-pitch TTS)
- **API hub**: OpenAI-compatible (OpenAI, Groq, OpenRouter, DeepSeek, Together, Mistral, Cerebras, Ollama/local, your own wrappers), Anthropic, Gemini
- **Routing**: priority+failover, round-robin, random, weighted, fastest (learns latency); rules by regex / length / voice -> prefer a tag or provider; retries; circuit breaker with cooldown; route tester; per-provider stats
- Personality prompt, name, size, voice all editable

## Connected Apps (plugins)
Settings > Setup > Connected Apps has one card each for Telegram, Gmail and Google Calendar. Nana uses them when you ask ("check my mail", "send a Telegram to ...", "book a meeting tomorrow 4pm"). If an app is not connected she says "Connect Gmail first from Settings > Connected Apps" with a link that opens that page. Sends and event creation always show an Allow card.

Code: `app/src/main/java/com/sahil/chibi/plugins/`. Every app is one file that implements `Plugin`. To add one, write the file and add it to the list in `PluginManager.all`.

Secrets are typed into the app on your phone and stored encrypted (AES-GCM, key in the Android Keystore). They are never put in the build, because the APK is public. `.env.example` lists the names of the values.

### Telegram
1. In Telegram open @BotFather, send `/newbot`, copy the token.
2. Open your new bot and press Start (a bot cannot message you before that).
3. Paste the token in the Telegram card and tap Connect. The app checks it with `getMe`.
Your chat id: send the bot a message, then ask Nana to read the bot's messages; the chat id is shown.

### Google (Gmail and Calendar)
Nana never sees your password. Google signs you in in your browser.
1. Go to console.cloud.google.com, make a project.
2. APIs & Services > Library: enable **Gmail API** and **Google Calendar API**.
3. APIs & Services > OAuth consent screen: User type External, fill the app name and your email, add your Google account under **Test users**.
4. Credentials > Create credentials > OAuth client ID > Application type **Desktop app**. Copy the Client ID and Client secret.
5. In Nana paste them (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) in the Gmail card, tap Connect, allow access in the browser. The Calendar card reuses the same ID and secret.
The redirect (`GOOGLE_REDIRECT_URI`) is automatic: `http://127.0.0.1:<random port>` on your phone, which Desktop clients allow.
While the consent screen is in Testing mode Google expires the login after 7 days; connect again then, or publish the app to production.

## Credits
Characters: LivLiv (livliv.ai). Nana is used under the LivLiv Featured Characters License (personal, non-commercial). The CI build downloads her and LivLiv Player (Apache-2.0) from github.com/livliv-dev/player; they are not stored in this repo.

## Build
GitHub Actions builds the APK on every push and publishes it at Releases > latest-build > mochi.apk.
The app is signed with a fixed key (app/debug.keystore) so every build installs as an update.
