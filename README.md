# JevNoiseGate

An Android noise gate for notifications and SMS.

Rule-based blockers are brittle in both directions: a keyword list that
catches ads will eventually swallow a verification code, and it will never
catch an ad phrased in a way nobody anticipated. JevNoiseGate asks a model
whether each message is noise instead, and suppresses only what the model
explicitly flags. **Everything uncertain passes through.**

---

## Design principles

### Fail open

Every uncertain path resolves to `ALLOW` — network failure, missing
credentials, timeout, HTTP error, a category the model returns that is not in
the configured answer space. This is enforced in one place (`DecisionMapper`)
so no call path can bypass it.

The reasoning: leaking one ad costs a moment of annoyance. Swallowing one
verification code can cost someone an account. The asymmetry is large enough
that the default has to favour letting things through.

### OTP never leaves the device

A local gate matches verification-code shapes before anything else runs
(`OtpGate`). On a match the message is allowed and **no request is made at
all** — not "the request is made but the field is stripped". The diagnostics
screen reports an upload count so this claim is observable rather than just
asserted.

### Hard rules stay local

Two mechanisms deliberately do **not** go through the model:

| Mechanism | Behaviour |
|---|---|
| SMS sender blacklist | Exact identifier match. Blocks without a network call, so it cannot be defeated by a timeout or an API outage. |
| OTP gate | Allows without a network call. |

A model is the right tool for "is this an ad". It is the wrong tool for
"this number is on my list".

---

## How it works

```
notification listener ─┐
                       ├─→ decision pipeline ─→ action
SMS receiver ──────────┘
```

Both ingestion paths share one pipeline, so there is no way for notifications
and SMS to drift apart in behaviour.

The pipeline runs in a fixed order:

1. **OTP gate** — local, resolves without network
2. **SMS blacklist** — local, resolves without network
3. **Model** — everything else, with channel history and user rules attached
   as structured `state`

The model receives a structured state rather than a bare string:

```json
{
  "source": "notification",
  "app": "Example",
  "package": "com.example.app",
  "channel": "promo",
  "sender": null,
  "title": "...",
  "body": "...",
  "history": { "seen": 12, "suppressed": 3, "noiseRatio": 0.25 },
  "userRules": { "preferAllow": ["verification codes"] }
}
```

History matters: a bare body gives the model no way to know that this channel
has been 90% ads. `userRules` lets a user state preferences in plain language
("always let verification codes through") which the model weighs rather than
matches literally.

---

## Behaviour worth knowing

### Notifications are cancelled *after* the decision

Android gives `onNotificationPosted` a strict time budget, so the decision
runs in a coroutine and the notification is revoked once it returns. The
practical consequence:

> A blocked notification is visible in the status bar for the duration of the
> request, then disappears.

This is not a bug, but it is not what "block" usually implies either. There is
no way to decide before the notification appears without blocking the system
callback.

### Blocking an SMS only removes its notification

Only the default SMS app may delete SMS. JevNoiseGate is not the default SMS
app and does not intend to become one (that would mean owning the delivery of
every legitimate message, including verification codes).

So blocking an SMS means revoking the notification its app posted. The message
**stays in the inbox and the conversation list**. For the common case — an ad
you glance at and ignore without opening the messaging app — this removes
essentially all of the interruption, but it is a real limitation.

---

## Features

- **Two ingestion paths** — notifications and SMS, one shared pipeline
- **Local OTP gate** — verification codes are allowed and never uploaded
- **SMS sender blacklist** — hard rule, evaluated before the model
- **User rules** — free-text preferences injected into the model state as soft
  constraints
- **Per-app listening filter** — opt apps out entirely
- **Event log** — every message is recorded *before* it is judged, so blocked
  items remain auditable after they are gone from the status bar. Events are
  grouped by app, and content is collapsed by default.
- **Runtime log** — in-app log with level gating, category filters, persisted
  to disk with rotation, clearable by the user
- **Network proxy** — HTTP / HTTPS / SOCKS5, with optional authentication
- **Theming** — Material 3 with materialKolor dynamic color, seven light/dark
  modes, custom seed colors

---

## Modules

```
core:model        domain types
core:decision     backend interface, state builder, decision mapping
core:pipeline     pipeline orchestration (pure Kotlin, JVM-testable)
core:dispatch     glue between ingestion and pipeline
core:data         Room entities, DAO, settings storage
core:fingerprint  message fingerprinting and skeletons
core:common       cross-cutting utilities (logging, clock, ...)

feature:notification   notification listener
feature:sms            SMS receiver

sdk:typesafe      Kotlin client for the TypeSafe API
app               Compose UI
```

`sdk:typesafe` is a Kotlin port of
[`@typesafe-ai/sdk@0.6.0`](https://github.com/typesafe-ai/typesafe-sdk-js)
(MIT). See that module's `LICENSE` and `NOTICE` — they are the attribution
required by the upstream licence, not decoration.

---

## Getting started

### Requirements

- JDK 17
- Android SDK 37 (compileSdk), minSdk 29
- An API key for the backend

### Build

```bash
./gradlew assembleDebug
```

### First run

1. Grant notification access (Settings → Notifications → Notification access).
   The app cannot do this for itself.
2. Grant SMS permission if you want the SMS path.
3. Open **Settings → JevAPI**, enter your key, and press **Test connection**.
   A valid key is required — without one every decision fails open and nothing
   is ever blocked.

> On some ROMs, reinstalling the APK leaves the notification listener
> registered but not actually bound, and no notifications reach the app.
> Toggling notification access off and on again fixes it.

---

## Configuration

| Setting | Notes |
|---|---|
| API key, base URL, model, timeout | Required for real decisions. The model list can be fetched from the backend. |
| Network proxy | HTTP / HTTPS / SOCKS5. HTTP and HTTPS map to the same implementation — HTTPS targets tunnel through CONNECT. Changes take effect on next launch. |
| User rules | Plain-language preferences, injected into the model state. Soft constraints. |
| SMS blacklist | Numbers, prefix or suffix matched, evaluated before the model. Hard rule. |
| Per-app filter | Opt an app out of ingestion entirely. |
| Log level | `OFF` removes all logging overhead. Default `INFO`. |

---

## Security notes

**The API key and proxy credentials are stored in plaintext**, in the app's
private Preferences DataStore. There is no Keystore-backed encryption.

This is a deliberate trade-off — the complexity of Keystore-based
encryption was judged not worth it for this app — but it has consequences you
should be aware of:

- On a rooted device, any process with root can read them.
- They may be included in device backups or extractions.
- **Do not supply a key you cannot rotate.**

The app's private storage is not readable by other apps on a non-rooted
device.

---

## Status

Early, and developed against a single device. Verified behaviour is limited to
what has actually been exercised — some paths have unit tests, others have
only been run by hand. Treat it as a personal tool that happens to be public
rather than a production product.

---

## Licence

The application is released under the MIT Licence — see `LICENSE`.

`sdk/typesafe` is a port of MIT-licensed upstream work and carries its own
`LICENSE` and `NOTICE`. Both must be preserved.
