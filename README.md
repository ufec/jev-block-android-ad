# JevNoiseGate

An Android noise gate for notifications and SMS.

Project review and fixes: [review](docs/PROJECT_REVIEW.md), [fixes and validation](docs/FIXES.md).

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

### Locally recognized OTP messages stay out of model requests

A local gate checks both title and body before blacklist or model decisions
(`OtpGate`). On a match the message is allowed and **no backend call is made**.
It recognizes common numeric and alphanumeric code formats, including split
title/body messages. Recognition is heuristic: unfamiliar wording can miss
the gate. The diagnostics screen reports backend attempts, not transport-level
upload counts; the no-call invariant is covered by pipeline tests.

### Hard rules stay local

Two mechanisms deliberately do **not** go through the model:

| Mechanism | Behaviour |
|---|---|
| SMS sender blacklist | Normalized number, prefix or suffix match. Blocks locally after the OTP gate. |
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
2. **User corrections** — restored identical content is allowed locally
3. **SMS blacklist** — local, resolves without network
4. **Model** — everything else, with channel history and user rules attached
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

Each notification key has a generation. A result for an older message cannot
cancel an updated or reposted message. Model work is limited to four concurrent
requests and a 15-second total deadline, including queueing and retries. At
most 64 notification decisions remain in flight; overload passes through.

### Blocking an SMS only removes its notification

Only the default SMS app may delete SMS. JevNoiseGate is not the default SMS
app and does not intend to become one (that would mean owning the delivery of
every legitimate message, including verification codes).

So blocking an SMS means revoking the notification its app posted. The message
**stays in the inbox and the conversation list**. For the common case — an ad
you glance at and ignore without opening the messaging app — this removes
essentially all of the interruption, but it is a real limitation.

SMS broadcasts perform only capture and local decisions, with an eight-second
budget. Model decisions run as unique persistent WorkManager jobs. Suppression
requests wait up to 60 seconds for a matching notification. Ambiguous matches
and grouped notifications stay visible to protect unrelated messages.

### Quarantine and recovery

The event screen has a paginated quarantine view. Recovery posts a new
JevNoiseGate notification containing the saved title/body and stores a local
allow correction for the complete identical content and source bucket. It
does not recreate the original app's notification buttons or pending intents.
Notification permission is required to post the recovery notification.
Silent suppression retains an audit event but offers no recovery action.

Events are retained for 30 days, with a maximum of 10,000 rows. User corrections
survive event cleanup. Latency statistics use the most recent 1,000 decisions.
Database upgrades from version 2 preserve existing history.

---

## Features

- **Two ingestion paths** — notifications and SMS, one shared pipeline
- **Local OTP gate** — recognized code messages are allowed without backend calls
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

app               Compose UI
```

The TypeSafe client is **not** a module in this repository. It is a Kotlin port
of [`@typesafe-ai/sdk@0.6.0`](https://github.com/typesafe-ai/typesafe-sdk-js)
(MIT) that lives in its own repository, `ufec/typesafe-sdk-kotlin`, and is
consumed as an ordinary dependency via JitPack. Its `LICENSE` and `NOTICE` are
the attribution required by the upstream licence, and they are preserved there
rather than here.

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

Run the same validation used by pull requests and releases:

```bash
./gradlew verify
```

This runs all modules' host/unit tests and Android Lint, including pipeline,
settings persistence, notification extraction and database migration tests.

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
| Network proxy | HTTP / HTTPS / SOCKS5. HTTP and HTTPS map to the same implementation — HTTPS targets tunnel through CONNECT. Saved changes take effect on the next decision. |
| User rules | Plain-language preferences, injected into the model state. Soft constraints. |
| SMS blacklist | Numbers, prefix or suffix matched, evaluated before the model. Hard rule. |
| Per-app filter | Opt an app out of ingestion entirely. |
| Log level | `OFF` removes all logging overhead. Default `INFO`. |

---

## Security notes

API keys and proxy credentials are encrypted in Preferences DataStore using
AES-GCM and a device-bound Android Keystore key. Legacy plaintext credentials
are migrated on first load. Missing or unreadable settings fail open and are
reported in the settings screen. Writes are persisted in the same order as
in-memory changes; accepted writes survive cancellation of their UI caller.

Application data is excluded from cloud backup and device transfer. Full
message text remains in the private event database for the retention period;
users can clear it from the event screen. Credentials exist in memory while
used, so Keystore storage does not protect a compromised running process.

`local.properties` API keys can be bundled into debug builds for development;
release builds always contain an empty bundled key. Release users enter their
own key. Saved API settings are validated and applied to the next decision.

---

## Status

Early, and developed against a single device. Verified behaviour is limited to
what has actually been exercised — some paths have unit tests, others have
only been run by hand. Treat it as a personal tool that happens to be public
rather than a production product.

---

## Licence

The application is released under the MIT Licence — see `LICENSE`.

The TypeSafe client is a port of MIT-licensed upstream work. It is not part of
this repository: it lives in `ufec/typesafe-sdk-kotlin` and carries its own
`LICENSE` and `NOTICE` there. Both must be preserved if you redistribute it.
