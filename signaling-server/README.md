# LinkBridge Signaling Server

A tiny WebSocket relay that helps two LinkBridge devices find each other for a
cross-network (different Wi-Fi, no shared hotspot) QR transfer, then gets out
of the way.

**What it does:** relays opaque WebRTC signaling messages (SDP offer/answer,
ICE candidates) between exactly two devices that joined the same session ID.

**What it never does:** see, touch, or relay any file data. Once the two
devices have exchanged enough signaling messages, WebRTC opens a connection
directly between them (or via a TURN relay if a direct connection isn't
possible) - the actual bytes never pass through this server.

## Running locally

```
./gradlew run
```

Starts on port 8080 (override with the `PORT` env var). Health check at
`GET /health`.

## Deploying (pick one - both have generous free tiers)

### Render
1. Push this folder to a GitHub repo (or a subdirectory of one).
2. New → Web Service → connect the repo, set **Root Directory** to
   `signaling-server` if it's part of a larger repo.
3. Render detects the `Dockerfile` automatically. Deploy.
4. Your signaling URL is `wss://<your-service>.onrender.com/pair`.

### Fly.io
```
fly launch          # detects the Dockerfile, asks a few questions
fly deploy
```
Your signaling URL is `wss://<your-app>.fly.dev/pair`.

Either way, take the resulting `wss://...` URL and put it into the Android
app's signaling server setting (see the main LinkBridge README) - that's the
address baked into the QR code so a scanning device knows where to connect.

## Protocol

JSON text frames over `/pair`:

| Direction | Message |
|---|---|
| client → server | `{"type":"join","sessionId":"..."}` |
| server → client | `{"type":"waiting"}` (first device in) |
| server → both | `{"type":"paired"}` (second device joined) |
| client → server | `{"type":"signal","payload":"..."}` (opaque, forwarded as-is) |
| server → peer | `{"type":"signal","payload":"..."}` |
| server → client | `{"type":"peer-left"}` |
| server → client | `{"type":"error","message":"..."}` |

A session only ever holds two devices; a third join attempt gets an error.
