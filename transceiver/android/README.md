# BeeTransceiver v0.6.0

Screen-off/background-resilient update for the びー・トランシーバー Android app.

- Incoming same-LAN PTT audio runs in a dedicated media-playback foreground service.
- Multicast, Wi-Fi high-performance and partial wake locks are held while receive is enabled.
- Receive socket recreates after transient failures.
- Support recording is sticky and rotates every 5 minutes.
- LAN PC discovery is preferred; otherwise the app reads the public bee-audio endpoint JSON.
- Upload queue works on Wi-Fi or mobile and keeps M4A until matching SHA-256 is returned.
- Internal event log: files/bee_transceiver_events.log.

Screen off / lock is supported. Actual device power-off is not. Android 15 blocks BOOT_COMPLETED from starting mediaPlayback/microphone foreground services, so open the app once after a full reboot.

This repo is public. Supply BEE_AUDIO_TOKEN at build time as a Gradle property or environment variable; do not commit it.
