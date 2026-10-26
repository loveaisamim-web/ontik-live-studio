# ONTIK Live Studio

Android live streaming app — Camera, Screen, File → RTMP (YouTube Live).

Built with Kotlin + CameraX + RootEncoder (Apache-2.0).

## Build

Push to `main` — GitHub Actions builds `app-debug.apk` and uploads it as artifact.

## YouTube setup

1. Google Cloud Console → enable **YouTube Live Streaming API**
2. OAuth consent screen → add yourself as test user
3. Create OAuth **Android** client with package `com.ontik.livestudio` + your debug SHA-1
4. Channel must have live streaming enabled (phone verify, ≤24 h)

## Limitations (v1)

- File mode: file picker & setVideoFile wiring pending
- Basic 10-attempt reconnect; no exponential backoff
- No watermark, no overlays, no ads
