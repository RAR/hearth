# Research: Switching to local (on-satellite) wake word detection

Goal: understand exactly what the reference `wyoming-satellite` (Python) client
does when wake word detection happens on-device instead of on the HA server,
and what HA's `wyoming` integration requires/tolerates on its side, so our
Android Wyoming satellite can be changed from "always streaming" to
"local-wake-then-stream" without breaking timers, TTS, or HA's satellite
state machine.

Primary sources (fetched raw, verbatim, on 2026-07-13):
- `wyoming-satellite` @ `master`: `wyoming_satellite/satellite.py`,
  `settings.py`, `__main__.py`, `event_handler.py`
  (pins `wyoming==1.5.4` per its `pyproject.toml`)
- `wyoming` core package @ tag **1.5.4** (the version wyoming-satellite
  actually targets — NOT master/1.10.0, which has a different, incompatible
  `RunPipeline` schema): `wyoming/pipeline.py`, `wyoming/wake.py`
- `wyoming` core package @ `master` (current, 1.10.0): `wyoming/info.py` (the
  `Satellite`/`Info` dataclasses — these are unchanged in shape between 1.5.4
  and 1.10.0 for our purposes, just re-checked against master to be current)
- `home-assistant/core` @ `dev`: `homeassistant/components/wyoming/
  assist_satellite.py` (this is the actual satellite protocol handler; there
  is no `satellite.py` in this component anymore — it was renamed/absorbed),
  plus `data.py`, `devices.py`, `models.py`, `select.py`, `number.py`,
  `binary_sensor.py`, `switch.py`, `entity.py`, `__init__.py`, `wake_word.py`

**Version-mismatch gotcha found**: `wyoming` master's `pipeline.py` (1.10.0)
defines `RunPipeline` with fields `wake_word_name` / `wake_word_names` /
`announce_text` — a newer, different schema. `wyoming-satellite` still pins
`wyoming==1.5.4`, whose `RunPipeline` has `name` / `restart_on_end` /
`snd_format` instead. **The field actually used by the reference satellite's
`_send_run_pipeline()` and by HA's current `assist_satellite.py` is the 1.5.4
shape** (`name`, `restart_on_end`, `snd_format`) — confirmed because HA dev's
`assist_satellite.py` does `RunPipeline.from_event(...)` and only reads
`start_stage`, `end_stage`, `name` (unused for stage skip), `restart_on_end`,
`snd_format` — no `wake_word_name`/`wake_word_names` field exists in the
version HA reads either. All quotes below use the 1.5.4 shape since that's
what's live in both wyoming-satellite and HA's Wyoming integration today.

---

## 1. `WakeStreamingSatellite` — exact event flow

Source: `wyoming_satellite/satellite.py`, class `WakeStreamingSatellite(SatelliteBase)`.

There are 3 satellite classes in the reference implementation, selected in
`__main__.py`:

```python
if settings.wake.enabled:
    # Local wake word detection
    satellite = WakeStreamingSatellite(settings)
elif settings.vad.enabled:
    # Stream after speech
    satellite = VadStreamingSatellite(settings)
else:
    # Stream all the time
    satellite = AlwaysStreamingSatellite(settings)
```

`settings.wake.enabled` is `bool(self.uri or self.command)` (i.e. `--wake-uri`
or `--wake-command` was given). This is exactly the axis we want to flip: our
Android app plays the role of `AlwaysStreamingSatellite` today and needs to
become `WakeStreamingSatellite`.

### RunSatellite / PauseSatellite

`WakeStreamingSatellite.event_from_server`:

```python
async def event_from_server(self, event: Event) -> None:
    # Only check event types once
    is_run_satellite = False
    is_pause_satellite = False
    is_transcript = False
    is_error = False

    if RunSatellite.is_type(event.type):
        is_run_satellite = True
        self._is_paused = False
    elif PauseSatellite.is_type(event.type):
        is_pause_satellite = True
    elif Transcript.is_type(event.type):
        is_transcript = True
    elif Error.is_type(event.type):
        is_error = True

    if is_transcript or is_pause_satellite:
        # Stop streaming before event_from_server is called because it will
        # play the "done" WAV.
        self.is_streaming = False
        if self.stt_audio_writer is not None:
            self.stt_audio_writer.stop()

    await super().event_from_server(event)

    if is_run_satellite or is_transcript or is_error or is_pause_satellite:
        # Stop streaming
        self.is_streaming = False

        if is_pause_satellite:
            self._is_paused = True
        else:
            # Go back to wake word detection
            await self.trigger_streaming_stop()

            # It's possible to be paused in the middle of streaming
            if not self._is_paused:
                await self._send_wake_detect()
                _LOGGER.info("Waiting for wake word")
                # Start debug recording (wake)
                ...
```

**On `run-satellite`**: unlike `AlwaysStreamingSatellite` (which immediately
sends a `RunPipeline` and starts streaming), `WakeStreamingSatellite` does
**not** send a `RunPipeline` at all here. It just clears `_is_paused`, falls
into the generic "stop streaming / go back to wake detection" branch (because
`is_run_satellite` is true), and calls `_send_wake_detect()` to (re)arm local
wake word listening. No pipeline is running yet — the satellite just waits.

**On `pause-satellite`**: sets `is_streaming = False`, `_is_paused = True`,
and does **not** call `_send_wake_detect()` (guarded by `if not
self._is_paused`). Mic audio then goes nowhere (see `event_from_mic` below —
gated on `self._is_paused`).

### RunPipeline sent after a local detection

Triggered from `event_from_wake` when a `Detection` event arrives from the
local wake service (see §1 audio-flow below for exactly when this fires).
Constructed by the shared helper `_send_run_pipeline()` on `SatelliteBase`:

```python
async def _send_run_pipeline(self, pipeline_name: Optional[str] = None) -> None:
    """Sends a RunPipeline event with the correct stages."""
    if self.settings.wake.enabled:
        # Local wake word detection
        start_stage = PipelineStage.ASR
        restart_on_end = False
    else:
        # Remote wake word detection
        start_stage = PipelineStage.WAKE
        restart_on_end = not self.settings.vad.enabled

    if self.settings.snd.enabled:
        # Play TTS response
        end_stage = PipelineStage.TTS
    else:
        # No audio output
        end_stage = PipelineStage.HANDLE

    run_pipeline = RunPipeline(
        start_stage=start_stage,
        end_stage=end_stage,
        name=pipeline_name,
        restart_on_end=restart_on_end,
        snd_format=AudioFormat(
            rate=self.settings.snd.rate,
            width=self.settings.snd.width,
            channels=self.settings.snd.channels,
        ),
    ).event()
    await self.event_to_server(run_pipeline)
    await self.forward_event(run_pipeline)
```

For `WakeStreamingSatellite` (`settings.wake.enabled == True`), this is
**always**:

- `start_stage = PipelineStage.ASR` ("asr")
- `end_stage = PipelineStage.TTS` ("tts") — assuming snd is enabled (it is,
  for a device with a speaker)
- `restart_on_end = False`
- `name = pipeline_name` — **not the wake word name**; it's an optional
  *pipeline* name (HA "assist pipeline" config name), resolved from
  `--wake-word-name NAME[,PIPELINE]` CLI mapping, or `None` if no mapping
  configured for that wake word. There is no field in `RunPipeline` (1.5.4
  schema) for the wake-word name itself — that's carried separately in the
  preceding `Detection` event (see below), not in `RunPipeline`.
- `snd_format` — the desired TTS output audio format the satellite wants
  from HA (rate/width/channels of its snd/speaker service).

So: **no wake word name in `RunPipeline`**, `restart_on_end` is **always
False** for local wake, and it always starts at ASR/ends at TTS (assuming a
sound output is configured, which it will be for a voice assistant device).

### Does it send a Detection event to HA, and when?

Yes — and it's sent **before** the `RunPipeline`. From
`event_from_wake`:

```python
async def event_from_wake(self, event: Event) -> None:
    if Info.is_type(event.type):
        self._wake_info = Info.from_event(event)
        self._wake_info_ready.set()
        return

    if self.is_streaming or (self.server_id is None):
        # Not detecting or no server connected
        return

    if Detection.is_type(event.type):
        detection = Detection.from_event(event)

        # Check refractory period to avoid multiple back-to-back detections
        refractory_timestamp = self.refractory_timestamp.get(detection.name)
        if (refractory_timestamp is not None) and (
            refractory_timestamp > time.monotonic()
        ):
            _LOGGER.debug("Wake word detection occurred during refractory period")
            return

        # Stop debug recording (wake) / start debug recording (stt) ...

        self.is_streaming = True
        _LOGGER.debug("Streaming audio")

        if self.settings.wake.refractory_seconds is not None:
            self.refractory_timestamp[detection.name] = (
                time.monotonic() + self.settings.wake.refractory_seconds
            )
        else:
            self.refractory_timestamp.pop(detection.name, None)

        # Forward detection to the server
        await self.event_to_server(event)

        # Match detected wake word name with pipeline name
        pipeline_name: Optional[str] = None
        if self.settings.wake.names:
            detection_name = normalize_wake_word(detection.name)
            for wake_name in self.settings.wake.names:
                if normalize_wake_word(wake_name.name) == detection_name:
                    pipeline_name = wake_name.pipeline
                    break

        await self._send_run_pipeline(pipeline_name=pipeline_name)
        await self.forward_event(event)  # forward to event service
        await self.trigger_detection(Detection.from_event(event))
        await self.trigger_streaming_start()
```

Exact wire order on local detection: **`Detection` event to server → then
`RunPipeline` event to server**. (`is_streaming = True` is flipped before
either is sent, so mic audio starts being forwarded to the server as soon as
the next mic chunk arrives — see §4.)

`Detection` (from `wyoming/wake.py`, 1.5.4) has fields `name` (model/wake-word
name, e.g. `"ok_nabu"`), `timestamp` (int, audio-chunk timestamp of the
detection), `speaker` (optional speaker id/name — for multi-speaker wake
models). Our Android app should send whichever of these it has (`name` is
the important one).

### When does streaming start and STOP?

**Start**: `is_streaming = True` is set synchronously the moment a
`Detection` passes the refractory check in `event_from_wake` — before the
`Detection`/`RunPipeline` events are even written to the socket. From that
point, `event_from_mic` forwards subsequent mic `AudioChunk` events to the
server instead of to the local wake service:

```python
async def event_from_mic(self, event, audio_bytes=None) -> None:
    if ((not AudioChunk.is_type(event.type)) or self.microphone_muted
            or self._is_paused):
        return
    # ...debug recording...
    if self.is_streaming:
        await self.event_to_server(event)
    else:
        await self.event_to_wake(event)
```

**Stop**: exactly 4 triggers, all handled in `event_from_server` shown above:
- `Transcript` (STT finished; HA sent the final ASR transcript back)
- `Error` (server-side pipeline error)
- `PauseSatellite` (server explicitly paused the satellite, e.g. muted)
- `RunSatellite` (re-arming after a previous run's HA-side pipeline fully
  ended and HA re-sent `run-satellite`, or first connect)

There is **no explicit "played" / TTS-finish trigger that stops streaming**
— streaming already stopped on `Transcript` (mic audio was never routed to
the server past that point regardless of TTS). `Played` (sent by HA after
TTS audio finishes) only fires `trigger_played()` for LED/event-command
purposes; it does not touch `is_streaming` because `is_streaming` is already
False by then.

There is **no local-VAD-driven stop** in `WakeStreamingSatellite` — see §4.

### What happens on pipeline end / how it returns to wake-word listening

Because `restart_on_end` is always `False` for the wake-streaming class,
neither the satellite nor HA auto-restarts the pipeline. Instead:

1. HA finishes the assist pipeline (ASR→...→TTS), sends `Transcript` (and
   possibly TTS audio events) to the satellite.
2. Satellite sees `Transcript` → `is_streaming = False` immediately (in the
   pre-`super().event_from_server()` block), stops the STT debug recorder,
   plays the "done" WAV (`trigger_transcript` → `_play_wav(done_wav)`, this
   happens inside the generic `SatelliteBase.event_from_server` dispatch
   that runs via `await super().event_from_server(event)`), then in the
   post-dispatch block (`is_transcript` true) calls `trigger_streaming_stop()`
   and — since not paused — `_send_wake_detect()` again, logging "Waiting
   for wake word". This re-arms the wake service (`Detect` + `AudioStart` to
   the wake process) and mic audio (`event_from_mic`) resumes flowing to the
   wake service instead of the (now finished) pipeline.
3. TTS audio (if any) is delivered and played independently via the snd
   pipeline/`AudioStart`/`AudioChunk`/`AudioStop` → `Played` chain; this is
   unaffected by `is_streaming` already being false.

So "returning to local-wake listening" is driven by receiving `Transcript`
(or `Error`), **not** by waiting for TTS playback (`Played`) to finish. The
satellite is already back to feeding mic audio into the local wake detector
while the TTS response is still being spoken by the snd service. See §5 for
the self-trigger implication.

`RunSatellite` also re-arms wake detection (see the `event_from_server`
excerpt above — `is_run_satellite` takes the same "not paused → send wake
detect" path). This covers the first `run-satellite` and any resend of it
(e.g. HA reconnecting/restarting the connection loop).

### Info/Describe response — wake capability fields

`WakeStreamingSatellite.update_info()`:

```python
async def update_info(self, info: Info) -> None:
    self._wake_info = None
    self._wake_info_ready.clear()
    await self.event_to_wake(Describe().event())
    try:
        await asyncio.wait_for(self._wake_info_ready.wait(), timeout=_WAKE_INFO_TIMEOUT)
        if self._wake_info is not None:
            # Update wake info only
            info.wake = self._wake_info.wake
    except asyncio.TimeoutError:
        _LOGGER.warning("Failed to get info from wake service")
```

This is called by `SatelliteEventHandler.handle_event` whenever the server
sends `Describe`:

```python
if Describe.is_type(event.type):
    await self.satellite.update_info(self.wyoming_info)
    await self.write_event(self.wyoming_info.event())
    return True
```

So on every `Describe`, the satellite re-describes its **local wake service**
(e.g. openWakeWord/microWakeWord running as a subprocess/URI) and copies that
service's own `info.wake` list (a `List[WakeProgram]`, each with
`WakeModel`s — name/phrase/languages) into the outer `Info.wake` that's sent
back to HA. **This is the only wake-related field the reference
implementation ever populates.**

Checked (via grep across the whole `wyoming-satellite` repo and the whole
`wyoming` core `info.py`): **`active_wake_words`, `max_active_wake_words`,
`supports_trigger`, and `has_vad`** all exist as optional fields on the
`Satellite` dataclass in `wyoming/info.py`:

```python
@dataclass
class Satellite(Artifact):
    """Satellite information."""
    area: Optional[str] = None
    has_vad: Optional[bool] = None
    active_wake_words: Optional[List[str]] = None
    max_active_wake_words: Optional[int] = None
    supports_trigger: Optional[bool] = None
```

...but **none of them are ever set** by `wyoming-satellite`'s `__main__.py`
(which constructs the outer `Satellite(...)` with only `name`, `area`,
`description`, `attribution`, `installed`, `version` — no `has_vad`,
`active_wake_words`, etc.) or by any of the three satellite classes. They
remain `None`/default on the wire. **Wake capability is signaled purely by a
non-empty top-level `info.wake` list**, exactly as a standalone wake-word
server would report it — there's no special satellite-specific wake-info
schema in practice.

---

## 2. How HA's `wyoming` integration treats a local-wake satellite

Source: `homeassistant/components/wyoming/assist_satellite.py` (current
`dev` branch — note: **there is no `satellite.py` file in this component
anymore**; the satellite protocol handling class is
`WyomingAssistSatellite(WyomingSatelliteEntity, AssistSatelliteEntity)`
inside `assist_satellite.py`).

### Does HA skip its own wake stage purely because `start_stage="asr"`?

**Yes — confirmed directly in code.** HA maps Wyoming pipeline stages to its
internal `assist_pipeline.PipelineStage` via a plain dict and passes those
straight through to `async_accept_pipeline_from_satellite`:

```python
_STAGES: dict[PipelineStage, assist_pipeline.PipelineStage] = {
    PipelineStage.WAKE: assist_pipeline.PipelineStage.WAKE_WORD,
    PipelineStage.ASR: assist_pipeline.PipelineStage.STT,
    PipelineStage.HANDLE: assist_pipeline.PipelineStage.INTENT,
    PipelineStage.TTS: assist_pipeline.PipelineStage.TTS,
}
```

```python
def _run_pipeline_once(self, run_pipeline: RunPipeline, wake_word_phrase=None) -> None:
    start_stage = _STAGES.get(run_pipeline.start_stage)
    end_stage = _STAGES.get(run_pipeline.end_stage)
    if start_stage is None:
        raise ValueError(f"Invalid start stage: {start_stage}")
    if end_stage is None:
        raise ValueError(f"Invalid end stage: {end_stage}")

    self._audio_queue = asyncio.Queue()
    self._is_pipeline_running = True
    self._pipeline_error = False
    self._pipeline_ended_event.clear()
    self.config_entry.async_create_background_task(
        self.hass,
        self.async_accept_pipeline_from_satellite(
            audio_stream=self._stt_stream(),
            start_stage=start_stage,
            end_stage=end_stage,
            wake_word_phrase=wake_word_phrase,
        ),
        "wyoming satellite pipeline",
    )
```

There's no separate flag anywhere else that HA checks to decide whether to
run its own wake-word stage — it's entirely driven by whatever `start_stage`
the satellite puts in the `RunPipeline` it receives. If the satellite says
`start_stage=asr`, HA's assist pipeline literally begins at
`PipelineStage.STT`, i.e. HA's own WAKE_WORD stage (and any
server-side/openWakeWord invocation) is never entered for that run. This is
the crux of "switching to local wake" from HA's point of view: it requires
zero configuration on the HA side beyond what already happens — the
satellite's own choice of `start_stage` is authoritative.

### Detection event handling — resolving wake word name to a phrase

```python
elif Detection.is_type(client_event.type):
    detection = Detection.from_event(client_event)
    wake_word_phrase = detection.name

    # Resolve wake word name/id to phrase if info is available.
    # This allows us to deconflict multiple satellite wake-ups
    # with the same wake word.
    if (client_info is not None) and (client_info.wake is not None):
        found_phrase = False
        for wake_service in client_info.wake:
            for wake_model in wake_service.models:
                if wake_model.name == detection.name:
                    wake_word_phrase = wake_model.phrase or wake_model.name
                    found_phrase = True
                    break
            if found_phrase:
                break
    _LOGGER.debug("Client detected wake word: %s", wake_word_phrase)
```

`client_info` here is populated from whatever `Info` HA last received from
the satellite (`client_info = Info.from_event(client_event)` on an earlier
`Info` event — recall HA sends `Describe` once per `_run_pipeline_loop()`
call, i.e. once per (re)connect, at the top of the loop: `await
self._client.write_event(Describe().event())`). So **the `info.wake` field
we send back on `Describe` is what lets HA turn a bare `Detection.name` like
`"ok_nabu"` into a nicer phrase like `"Ok Nabu"` for logging/UI** — this is
the one place `info.wake` content actually matters end-to-end, beyond just
"is wake word detection available".

`wake_word_phrase` is then threaded through to `_run_pipeline_once(...,
wake_word_phrase)` and ultimately into
`async_accept_pipeline_from_satellite(..., wake_word_phrase=wake_word_phrase)`
— used by HA's Assist pipeline/conversation logging so the transcript shows
which wake word triggered the run.

Order dependency confirmed by code: satellite sends `Detection` then
`RunPipeline`. HA's event loop processes them in that order:
`client_event_task` resolves to `Detection` first → sets `wake_word_phrase`
→ requests next event → resolves to `RunPipeline` → calls
`self._run_pipeline_once(run_pipeline, wake_word_phrase)` — phrase is
available by the time the pipeline actually starts.

**Robustness fallback** (interesting defensive code, in case our client
skips sending `RunPipeline` after `Detection` and just starts streaming
audio):

```python
elif AudioChunk.is_type(client_event.type) and (
    self._is_pipeline_running or (wake_word_phrase is not None)
):
    if not self._is_pipeline_running:
        # Some satellites report a local wake word detection and
        # then start streaming audio without sending a
        # RunPipeline event. Start a pipeline so the audio isn't
        # silently dropped. Begin at ASR since the wake word was
        # already detected on the satellite.
        self._run_pipeline_once(
            RunPipeline(start_stage=PipelineStage.ASR, end_stage=PipelineStage.TTS),
            wake_word_phrase,
        )
    chunk = AudioChunk.from_event(client_event)
    chunk = self._chunk_converter.convert(chunk)
    self._audio_queue.put_nowait(chunk.audio)
```

This means: **even if our Android satellite only sends `Detection` +
`AudioChunk`s without an explicit `RunPipeline`, HA will auto-start an
ASR→TTS pipeline** as long as it saw a `Detection` first. Good safety net,
but we should still send `RunPipeline` explicitly to match the reference
protocol precisely (e.g. so `restart_on_end`/`snd_format` are communicated).

### Is anything special needed in the Info block for local-wake?

**No.** Confirmed by grepping the entire `homeassistant/components/wyoming/`
package (`assist_satellite.py`, `data.py`, `devices.py`, `models.py`,
`select.py`, `number.py`, `binary_sensor.py`, `switch.py`, `entity.py`,
`__init__.py`, `wake_word.py`) for `active_wake_words`, `has_vad`,
`supports_trigger`: **zero matches anywhere.** HA's Wyoming integration does
not read or act on any of those three `Satellite` Info fields at all, today.

The only Info-block-driven behavior found (`data.py`, `WyomingService.__init__`):

```python
if (self.info.satellite is not None) and self.info.satellite.installed:
    # Don't load platforms for satellite services, such as local wake
    # word detection.
    return

if any(asr.installed for asr in info.asr):
    self.platforms.append(Platform.STT)
if any(tts.installed for tts in info.tts):
    self.platforms.append(Platform.TTS)
if any(wake.installed for wake in info.wake):
    self.platforms.append(Platform.WAKE_WORD)
...
```

i.e., because our device's top-level `Info.satellite.installed` is (and
will remain) `True`, HA's config flow **never creates a standalone
`wake_word` platform entity for us regardless of what we put in `info.wake`**
— that early `return` short-circuits it. `info.wake` is only consulted
later, at runtime, purely to resolve the detected wake word's display
`phrase` (§ above). There is no "wake word selector" entity or `has_wake`
flag HA looks for on the satellite-info level. Bottom line: **no new HA-side
config surface is required to move to local wake** — it's purely a change
in what our satellite sends over the wire.

---

## 3. Timers and TTS — unaffected by wake mode

**Timers**: HA's `WyomingAssistSatellite._handle_timer` is registered once
via `intent.async_register_timer_handler(self.hass, self.device.device_id,
self._handle_timer)` in `run()`, completely independent of pipeline
start/end stage or `is_streaming` state:

```python
@callback
def _handle_timer(self, event_type, timer) -> None:
    if self._client is None:
        return
    event: Event | None = None
    if event_type == intent.TimerEventType.STARTED:
        event = TimerStarted(...).event()
    elif event_type == intent.TimerEventType.UPDATED:
        event = TimerUpdated(...).event()
    elif event_type == intent.TimerEventType.CANCELLED:
        event = TimerCancelled(id=timer.id).event()
    elif event_type == intent.TimerEventType.FINISHED:
        event = TimerFinished(id=timer.id).event()
    if event is not None:
        self.config_entry.async_create_background_task(
            self.hass, self._client.write_event(event), "wyoming timer event"
        )
```

This fires on the raw client-writer connection any time the intent-engine's
timer fires, regardless of whether a pipeline is currently running or which
`start_stage` was used to launch it. Nothing changes for timers.

**TTS audio delivery**: driven purely by pipeline events
(`PipelineEventType.TTS_START`/`TTS_END`/`INTENT_PROGRESS` with
`tts_start_streaming`) inside `on_pipeline_event`, which fire the same way
regardless of `start_stage` as long as `end_stage=TTS` (which it always is
for a device with a speaker, in both always-streaming and local-wake modes).
`_stream_tts()` streams `AudioStart`/`AudioChunk`/`AudioStop` to the
satellite exactly the same way in both modes. Nothing changes for TTS.

Satellite-side (`wyoming_satellite/satellite.py`), TTS/timer handling lives
in `SatelliteBase` (shared by all three satellite classes — Always/Vad/Wake):
`event_from_server`'s `AudioChunk`/`AudioStart`/`AudioStop` (TTS)
`Synthesize`, and the four `Timer*` branches are identical regardless of
which subclass is running. Only wake/streaming-state logic differs between
subclasses.

---

## 4. VAD — does the wake-streaming satellite need its own VAD?

**No.** `WakeStreamingSatellite.__init__`:

```python
if settings.vad.enabled:
    _LOGGER.warning("VAD is enabled but will not be used")
```

And `__main__.py` explicitly warns if you try to combine them:

```python
if args.vad and (args.wake_uri or args.wake_command):
    _LOGGER.warning("VAD is not used with local wake word detection")
```

There is no `SileroVad`/`vad(...)` call anywhere in `WakeStreamingSatellite`
(contrast with `VadStreamingSatellite`, which does run `SileroVad` locally
to decide when to start streaming in the *no-wake-word* case). For the
wake-streaming class, the end of an utterance is decided **entirely
server-side**, inside HA's ASR/STT pipeline stage — confirmed by HA's own
VAD-boundary events (`assist_pipeline.PipelineEventType.STT_VAD_START` /
`STT_VAD_END`), which the satellite handler just forwards as informational
`VoiceStarted`/`VoiceStopped` wire events back to the client:

```python
elif event.type == assist_pipeline.PipelineEventType.STT_VAD_START:
    # User started speaking
    ... self._client.write_event(VoiceStarted(timestamp=...).event())
elif event.type == assist_pipeline.PipelineEventType.STT_VAD_END:
    # User stopped speaking
    ... self._client.write_event(VoiceStopped(timestamp=...).event())
```

The satellite itself never uses these to gate anything — `event_from_server`
in `SatelliteBase` just triggers `trigger_stt_start()`/`trigger_stt_stop()`
(LED/event-command hooks), not streaming control. **What actually ends
streaming client-side is `Transcript` (or `Error`), sent by HA once its
STT/ASR stage (with its own server-side VAD, e.g. energy-based or Silero on
the STT provider side) has decided the utterance is complete and produced
text.** So: our Android satellite does **not** need to implement any local
VAD to segment the utterance for the wake-streaming path — it just streams
continuously from the moment `is_streaming` flips true (on detection) until
it receives `Transcript`/`Error`/`PauseSatellite`/`RunSatellite` back from
HA, exactly as quoted in §1.

(The only place local VAD genuinely matters is the *separate*
`VadStreamingSatellite` class, used when there's no wake word service at
all — not our case, and mutually exclusive with `settings.wake.enabled`.)

---

## 5. Mic mute/ducking during TTS — anti-self-trigger measures

**Verified: the reference implementation has essentially none, beyond a
per-wake-word refractory timer.** Two separate mechanisms exist in
`SatelliteBase`/`WakeStreamingSatellite`, and neither covers "don't listen
for wake words while our own TTS speaker is playing":

1. **`microphone_muted` flag** — only set `True` in `_play_wav()`, and only
   when `mute_microphone=True` is passed, which happens for:
   - the **awake_wav** chime (`trigger_detection` →
     `_play_wav(self.settings.snd.awake_wav, mute_microphone=
     self.settings.mic.mute_during_awake_wav)`, default `True`), muted for
     the WAV's own duration + `--mic-seconds-to-mute-after-awake-wav`
     (default 0.5s), and
   - the **timer-finished** chime (same pattern in
     `trigger_timer_finished`).

   It is **not** set during the actual TTS response audio
   (`AudioStart`/`AudioChunk`/`AudioStop`/`Synthesize` handling in
   `event_from_server` — `trigger_tts_start`/`trigger_tts_stop` never touch
   `microphone_muted`). So while HA's synthesized answer is playing out of
   the speaker, the mic is fully live.

2. **Refractory period** (`--wake-refractory-seconds`, default `5.0`) — a
   per-wake-word-name cooldown timestamped from the **moment of detection**,
   not from TTS start or end:

   ```python
   if self.settings.wake.refractory_seconds is not None:
       self.refractory_timestamp[detection.name] = (
           time.monotonic() + self.settings.wake.refractory_seconds
       )
   ```

   checked on the next detection:

   ```python
   refractory_timestamp = self.refractory_timestamp.get(detection.name)
   if (refractory_timestamp is not None) and (refractory_timestamp > time.monotonic()):
       _LOGGER.debug("Wake word detection occurred during refractory period")
       return
   ```

Combined with the fact that `is_streaming` already flips back to `False` on
`Transcript` — i.e. **before** TTS playback even starts (STT finishes, then
intent handling, then TTS starts) — the satellite is back to actively
feeding mic audio into the local wake-word detector for the *entire
duration* of the TTS response. If the TTS response is longer than the
refractory window (default 5s) and contains something acoustically close to
the wake word, self-triggering is possible; the reference implementation
relies on wake-word models being fairly specific/robust rather than any
explicit AEC or TTS-aware muting. This is worth being deliberate about in
our own implementation (e.g. consider muting/ducking the wake detector input
for the duration of TTS playback, which the upstream project does **not**
do) — flagging as a design decision for us, not a spec requirement.

---

## Practical implications for our Android satellite

1. Keep sending `run-pipeline` only **after** a local wake detection, not on
   `run-satellite`. On `run-satellite`, just arm local wake listening (no
   pipeline).
2. `run-pipeline` fields to send: `start_stage: "asr"`, `end_stage: "tts"`,
   `restart_on_end: false`, plus `name` (only if we support named
   pipelines — optional/omit otherwise) and `snd_format` (our TTS playback
   audio format).
3. Send `detection` (`{"name": ..., "timestamp": ..., "speaker": null}`)
   immediately before `run-pipeline`, as soon as we detect the wake word
   locally — don't wait for anything else.
4. Start streaming mic `audio-chunk`s to HA the instant we decide a
   detection is real (no local VAD needed) and keep streaming until we
   receive `transcript`, `error`, `pause-satellite`, or `run-satellite`
   (re-arm) from HA — whichever comes first. On any of those, stop
   streaming and go back to local-only wake detection.
5. Nothing changes for timer events or TTS audio-start/chunk/stop handling
   — those are dispatched from the same `event_from_server`-equivalent path
   regardless of streaming mode.
6. No special `active_wake_words`/`has_vad`/`supports_trigger` fields are
   required in our `Info`/`Describe` response — HA doesn't read them. Only
   populate `info.wake` (a `WakeProgram`/`WakeModel` list naming our local
   wake model(s)) if we want HA to show a friendly wake-word phrase in logs;
   otherwise it's fine to omit and HA will fall back to showing the raw
   detection `name`.
7. Consider (own design choice, not spec-mandated) whether to mute/duck the
   local wake detector's mic input while our TTS is playing, since the
   reference client does not do this and relies solely on a short
   (default 5s) per-wake-word refractory timer that starts at detection
   time, not at TTS-start/end.

---

## Could not verify

- Whether **microWakeWord**/**openWakeWord** wire behavior differs in any
  way relevant to us — out of scope, we only inspected the satellite-side
  wyoming protocol handling, not any specific wake-engine implementation.
- Real-world behavior/latency of HA's `_INFO_TIMEOUT`/retry logic
  (`load_wyoming_info`) under our Android TCP stack — not testable from
  static source reading alone.
- Whether any HA **frontend** surface (Settings > Voice Assistants device
  page) displays something different for a satellite whose `info.wake` is
  populated vs. empty — I only checked the `wyoming` integration's Python
  backend, not the frontend (`frontend` repo / Lovelace) rendering of
  device pages.
- Behavior across older HA core versions (this was checked against current
  `dev`, i.e. very recent HA) — if the user's HA installation is materially
  older, some of the exact code (e.g. the `AudioChunk`-without-RunPipeline
  fallback, or the `wake_word_phrase` resolution) may not be present yet.
