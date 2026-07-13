# openWakeWord TFLite Pipeline — Research for Android/Kotlin Port

Sources read directly (raw file fetch + local `Read`, not paraphrased blog posts):
- `https://raw.githubusercontent.com/dscripka/openWakeWord/main/openwakeword/utils.py` (main branch, fetched 2026-07-13)
- `https://raw.githubusercontent.com/dscripka/openWakeWord/main/openwakeword/model.py`
- `https://raw.githubusercontent.com/dscripka/openWakeWord/main/openwakeword/__init__.py`
- `https://raw.githubusercontent.com/dscripka/openWakeWord/main/README.md`
- GitHub Releases API: `api.github.com/repos/dscripka/openWakeWord/releases/tags/v0.5.1` (asset names/sizes)
- `https://raw.githubusercontent.com/rhasspy/wyoming-openwakeword/master/{wyoming_openwakeword/__main__.py,handler.py,state.py,Dockerfile}` — this is what the HA add-on actually runs
- `https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/{openwakeword.py,wakeword.py,const.py,__init__.py}` — the newer C-API-based engine that current wyoming-openwakeword depends on (bundles the actual `ok_nabu`/`okay_nabu.tflite` file)
- GitHub issues #340, #239, #223 on `dscripka/openWakeWord` (Android-specific reports)
- Downloaded `melspectrogram.tflite` (v0.5.1) directly and ran `strings` on the flatbuffer to check for Flex/RFFT op names

Note on repo state: openWakeWord's main branch has migrated from `tflite-runtime`/`tensorflow` to Google's `ai-edge-litert` package as of a recent commit, but the tensor shapes, buffer logic, and transforms are unchanged from the versions HA currently ships (v0.5.1, referenced by wyoming-openwakeword). All numbers below are cross-verified across the original Python implementation, the C-API reimplementation (pyopen-wakeword), and a real Android port an issue commenter linked — all three agree exactly.

---

## 1. Three model stages, files, shapes, hosting

| Stage | File | Input shape | Output shape | Notes |
|---|---|---|---|---|
| Melspectrogram | `melspectrogram.tflite` | `[1, N]` int16→float32 samples, **N must be a multiple that the interpreter is resized to** (dynamic 2nd dim) | `[1, 1, F, 32]` where F = mel frames produced from N samples | 32 mel bins fixed. See §2 for exact F formula. |
| Embedding (Google `speech_embedding`) | `embedding_model.tflite` | `[B, 76, 32, 1]` (76 mel frames × 32 mel bins × 1 channel; B is batch, can be resized) | `[B, 1, 1, 96]` (squeezed to `[B, 96]` in Python) | 76-frame window, 96-dim embedding. This model is a from-scratch reimplementation of Google's `speech_embedding` TFHub module (Apache-2.0, https://tfhub.dev/google/speech_embedding/1, paper https://arxiv.org/abs/2002.01322). |
| Wakeword head (per model) | e.g. `alexa_v0.1.tflite`, `hey_jarvis_v0.1.tflite` | `[1, 16, 96]` (16 embeddings × 96-dim) — window size read from `get_input_details()[0]['shape'][1]`, confirmed = 16 for the standard pretrained models | `[1, C]` where C = 1 for simple wake word models (sigmoid-like single score) or C>1 for multi-class models (e.g. `timer` has 7 classes) | Class mapping for multi-output models lives in `openwakeword.model_class_mappings` (only `timer` defined there; everything else defaults to `{"0": "0"}`-style 1-class). |

**Verified exact tensor shape constants** (from `pyopen-wakeword/pyopen_wakeword/openwakeword.py`, a byte-for-byte-compatible C-API reimplementation, and cross-checked against `utils.py`):
```python
SAMPLES_PER_CHUNK: Final = 1280   # 80 ms @ 16kHz
MEL_SAMPLES: Final = 1760         # samples fed to melspec model per invoke in steady state (see §2)
NUM_MELS: Final = 32
EMB_FEATURES: Final = 76          # mel-frame window size for embedding model
EMB_STEP: Final = 8               # stride, in mel frames, between embedding windows
WW_FEATURES: Final = 96           # embedding dim
MEL_SHAPE: Final = (1, 1760)                 # melspec model input, resized once
EMB_SHAPE: Final = (1, 76, 32, 1)            # embedding model input
```
And their own doc comment nails the whole pipeline in one place:
```
# melspec = [batch x samples (min: 1280)] => [batch x 1 x window x mels (32)]
# stft window size: 25ms (400)
# stft window step: 10ms (160)
# mel band limits: 60Hz - 3800Hz
# mel frequency bins: 32
#
# embedding = [batch x window x mels (32) x 1] => [batch x 1 x 1 x features (96)]
# ww = [batch x window x features (96)] => [batch x probability]
```

### Hosting / download URLs
All *shared feature models* and *pretrained wake word models* (`.tflite` **and** `.onnx`) are GitHub release assets on `dscripka/openWakeWord`. Exact URLs and sizes for the **v0.5.1** release (the version referenced by the `MODELS`/`FEATURE_MODELS` dicts in the current `main` branch `openwakeword/__init__.py`):

| File | URL | Size (bytes) |
|---|---|---|
| melspectrogram.tflite | `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite` | 1,092,516 |
| melspectrogram.onnx | `.../v0.5.1/melspectrogram.onnx` | 1,087,958 |
| embedding_model.tflite | `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite` | 1,330,312 |
| embedding_model.onnx | `.../v0.5.1/embedding_model.onnx` | 1,326,578 |
| alexa_v0.1.tflite | `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.tflite` | 855,312 |
| hey_jarvis_v0.1.tflite | `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.tflite` | 1,278,912 |
| hey_mycroft_v0.1.tflite | `.../v0.5.1/hey_mycroft_v0.1.tflite` | 860,300 |
| hey_rhasspy_v0.1.tflite | `.../v0.5.1/hey_rhasspy_v0.1.tflite` | 416,140 |
| timer_v0.1.tflite | `.../v0.5.1/timer_v0.1.tflite` | 1,743,316 |
| weather_v0.1.tflite | `.../v0.5.1/weather_v0.1.tflite` | 1,150,224 |
| silero_vad.onnx (optional VAD) | `.../v0.5.1/silero_vad.onnx` | 1,807,522 |

**No official HuggingFace repo** is referenced anywhere in the README or source — models are exclusively GitHub release assets, fetched at install time by `openwakeword.utils.download_models()`.

### `ok_nabu` is NOT in dscripka/openWakeWord at all
I searched every release (`v0.1.1` through `v0.6.0`) of `dscripka/openWakeWord` for "nabu" — **zero matches**. The "Okay Nabu" model that Home Assistant ships is **not an openWakeWord-repo asset**. It is bundled directly inside `rhasspy/pyopen-wakeword` (the library HA's current wyoming-openwakeword add-on depends on) at:
```
https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/models/okay_nabu.tflite   (206,380 bytes)
```
The other model files in that same directory are byte-identical in size to the openWakeWord release assets (just renamed without the `_v0.1` suffix): `alexa.tflite` = 855,312 B, `hey_jarvis.tflite` = 1,278,912 B, `embedding_model.tflite` = 1,330,312 B, `melspectrogram.tflite` = 1,092,516 B — confirming they are literally the same `.tflite` graphs, just repackaged. `okay_nabu.tflite` was presumably trained by Nabu Casa/the Open Home Foundation using the same openWakeWord training pipeline but never published back to the upstream repo's release assets.

Also note: Home Assistant's *other* wake-word engine, **`micro-wake-word`** (`https://github.com/OHF-Voice/micro-wake-word`), is a completely different, unrelated architecture (for ESP32 microcontrollers) — don't confuse it with this pipeline. It is not TFLite-melspectrogram-based in the same way.

---

## 2. Streaming algorithm, step by step

Chunk size: **1280 samples @ 16 kHz = 80 ms**, 16-bit PCM mono. This is a hard architectural assumption baked into every layer (buffer maxlens, stride constants), not just a recommendation — you can feed other sizes and it will accumulate/carry a remainder, but 1280 is the native unit.

### 2a. Raw audio → melspectrogram buffer (`AudioFeatures._streaming_features` / `_streaming_melspectrogram` in `utils.py`)

Exact accumulation logic (quoted, `openwakeword/utils.py` lines 409–452):
```python
def _streaming_features(self, x):
    # Add raw audio data to buffer, temporarily storing extra frames if not an even number of 80 ms chunks
    processed_samples = 0

    if self.raw_data_remainder.shape[0] != 0:
        x = np.concatenate((self.raw_data_remainder, x))
        self.raw_data_remainder = np.empty(0)

    if self.accumulated_samples + x.shape[0] >= 1280:
        remainder = (self.accumulated_samples + x.shape[0]) % 1280
        if remainder != 0:
            x_even_chunks = x[0:-remainder]
            self._buffer_raw_data(x_even_chunks)
            self.accumulated_samples += len(x_even_chunks)
            self.raw_data_remainder = x[-remainder:]
        elif remainder == 0:
            self._buffer_raw_data(x)
            self.accumulated_samples += x.shape[0]
            self.raw_data_remainder = np.empty(0)
    else:
        self.accumulated_samples += x.shape[0]
        self._buffer_raw_data(x)

    # Only calculate melspectrogram once minimum samples are accumulated
    if self.accumulated_samples >= 1280 and self.accumulated_samples % 1280 == 0:
        self._streaming_melspectrogram(self.accumulated_samples)

        # Calculate new audio embeddings/features based on update melspectrograms
        for i in np.arange(self.accumulated_samples//1280-1, -1, -1):
            ndx = -8*i
            ndx = ndx if ndx != 0 else len(self.melspectrogram_buffer)
            x = self.melspectrogram_buffer[-76 + ndx:ndx].astype(np.float32)[None, :, :, None]
            if x.shape[1] == 76:
                self.feature_buffer = np.vstack((self.feature_buffer,
                                                self.embedding_model_predict(x)))

        # Reset raw data buffer counter
        processed_samples = self.accumulated_samples
        self.accumulated_samples = 0

    if self.feature_buffer.shape[0] > self.feature_buffer_max_len:
        self.feature_buffer = self.feature_buffer[-self.feature_buffer_max_len:, :]

    return processed_samples if processed_samples != 0 else self.accumulated_samples
```

`raw_data_buffer` is a `deque(maxlen=sr*10)` (10 s ring buffer of raw int16 samples, stored as a Python list via `.tolist()` — inefficient but that's what it does).

**The critical "extra context" trick** — `_streaming_melspectrogram` (lines 387–401):
```python
def _streaming_melspectrogram(self, n_samples):
    """Note! There seem to be some slight numerical issues depending on the underlying audio data
    such that the streaming method is not exactly the same as when the melspectrogram of the entire
    clip is calculated. ...
    """
    if len(self.raw_data_buffer) < 400:
        raise ValueError("The number of input frames must be at least 400 samples @ 16khz (25 ms)!")

    self.melspectrogram_buffer = np.vstack(
        (self.melspectrogram_buffer, self._get_melspectrogram(list(self.raw_data_buffer)[-n_samples-160*3:]))
    )
```
i.e. every time exactly one (or more) 1280-sample chunk has accumulated, it calls the melspec model not just on the new 1280(·k) samples but on those samples **plus 480 extra samples of left-context** (`160*3`, i.e. 3 STFT hop-lengths) pulled from the buffer's immediate past. This is *why* `pyopen-wakeword`'s equivalent constant is `MEL_SAMPLES = 1760` (= 1280 + 480) rather than 1280 — confirmed independently in that reimplementation and in a third-party Android/Kotlin port (`rawDataBuffer = FloatArray(1760)`, shifting 1280 in and keeping 480 of prior context — see §6). STFT params: window 400 samples (25 ms), hop 160 samples (10 ms), mel bins 32, band-limits 60 Hz–3800 Hz (from `pyopen-wakeword` doc comment).

Frame-count formula used elsewhere for the batch (non-streaming) path (`_get_melspectrogram_batch`, line 270): `n_frames = ceil(x.shape[1]/160 - 3)`. For a 1760-sample streaming call this gives `ceil(1760/160 - 3) = ceil(11-3) = 8` — i.e. **each melspec invocation in steady state yields exactly 8 new mel frames**, which is exactly the embedding stride (`EMB_STEP = 8`), so mel-frame production and embedding-window advancement stay in lockstep, one melspec call → one embedding call, both once per 80 ms audio chunk.

`melspectrogram_buffer` starts as `np.ones((76, 32))` (76 frames of literal 1.0, **not zeros, not silence-derived values** — an arbitrary placeholder) and grows via `vstack`, capped at `melspectrogram_max_len = 10*97 = 970` frames (97 = mel frames/second at 10 ms hop).

### 2b. The x/10 + 2 transform — quoted exactly

`openwakeword/utils.py`, `_get_melspectrogram` (lines 180–208), default arg:
```python
def _get_melspectrogram(self, x: Union[np.ndarray, List], melspec_transform: Callable = lambda x: x/10 + 2):
    ...
    # Get melspectrogram
    outputs = self.melspec_model_predict(x)
    spec = np.squeeze(outputs[0])

    # Arbitrary transform of melspectrogram
    spec = melspec_transform(spec)

    return spec
```
Docstring calls it explicitly "a transform that makes the ONNX melspectrogram model closer to the native Tensorflow implementation from Google (https://tfhub.dev/google/speech_embedding/1)." This transform is applied to **every** melspec model output before it enters the mel buffer — apply it once, right after `invoke()`, before shifting into your ring buffer. `pyopen-wakeword` confirms the identical transform verbatim:
```python
mels = (mels / 10) + 2  # transform to fit embedding
```
Do **not** apply it a second time anywhere downstream.

### 2c. Melspec buffer → embedding model (stride = 8 mel frames)

`_get_embeddings` (non-streaming helper, but same windowing logic as streaming, lines 225–236):
```python
def _get_embeddings(self, x: np.ndarray, window_size: int = 76, step_size: int = 8, **kwargs):
    spec = self._get_melspectrogram(x, **kwargs)
    windows = []
    for i in range(0, spec.shape[0], 8):
        window = spec[i:i+window_size]
        if window.shape[0] == window_size:  # truncate short windows
            windows.append(window)
    batch = np.expand_dims(np.array(windows), axis=-1).astype(np.float32)
    embedding = self.embedding_model_predict(batch)
    return embedding
```
In the streaming path (`_streaming_features`, quoted in full above), the equivalent windowing is done directly against `self.melspectrogram_buffer` using the trailing `76` frames ending at each new stride boundary (`ndx = -8*i` for however many new 1280-sample chunks were just processed — normally exactly one, so `i` ranges just `{0}` and `ndx` = end-of-buffer). Net effect: **one new embedding computed per 1280-sample (80 ms) input chunk**, from the most recent 76 mel frames (spanning ~790 ms of audio: 76 frames × 10 ms hop + 30 ms window overhang).

`feature_buffer` (the embedding history for the wakeword head) grows via `vstack` and is capped at `feature_buffer_max_len = 120` (~10 s of embedding history at one embedding per 80 ms... actually 120 embeddings × 80 ms = 9.6 s, hence the "~10 seconds" comment).

**Warm-up/seed value** — at `__init__` (and on every `reset()`):
```python
self.melspectrogram_buffer = np.ones((76, 32))  # n_frames x num_features
...
self.feature_buffer = self._get_embeddings(np.random.randint(-1000, 1000, 16000*4).astype(np.int16))
```
i.e. the embedding buffer is seeded by running **4 seconds of uniform random int16 noise** (range ±1000, not full-scale ±32767) through the full melspec→embedding pipeline once at startup. This is a very different warm-up strategy than the C-API reimplementation (see §2d) — worth flagging as a potential source of behavioral drift between ports if you don't replicate it (or intentionally choose the alternative, cleaner silence-based warm-up instead — see below).

### 2d. Embedding buffer → wakeword head (window = 16 embeddings)

From `model.py` `Model.predict` (lines 280–302), the wakeword tflite model is invoked over a **16-embedding window** (confirmed via `self.model_inputs[mdl] = self.models[mdl].get_input_details()[0]['shape'][1]`, which is `16` for `alexa_v0.1`/`hey_jarvis_v0.1`/etc.):
```python
elif n_prepared_samples == 1280:
    prediction = self.model_prediction_function[mdl](
        self.preprocessor.get_features(self.model_inputs[mdl])
    )
```
`AudioFeatures.get_features` (lines 454–460):
```python
def get_features(self, n_feature_frames: int = 16, start_ndx: int = -1):
    if start_ndx != -1:
        end_ndx = start_ndx + int(n_feature_frames) if start_ndx + n_feature_frames != 0 else len(self.feature_buffer)
        return self.feature_buffer[start_ndx:end_ndx, :][None, ].astype(np.float32)
    else:
        return self.feature_buffer[int(-1*n_feature_frames):, :][None, ].astype(np.float32)
```
i.e. simplest case: just take the **last 16 rows** of the embedding buffer, shape `[1, 16, 96]`, feed to the wakeword head, once per 80 ms chunk. (The `n_prepared_samples > 1280` branch handles multi-chunk-at-once calls by sliding the 16-window back further and taking a max over the group — only matters if you batch multiple 80 ms chunks into a single `predict()` call.)

### 2e. Cross-check: the C-API reimplementation's ring-buffer version of the same algorithm

`pyopen-wakeword/pyopen_wakeword/openwakeword.py` `OpenWakeWordFeatures.process_streaming` implements the identical math with true fixed-size ring buffers (shift-left + overwrite-tail) instead of Python `vstack`/deque — useful as a direct blueprint for a Kotlin/Java array-based port:
```python
while self.new_audio_samples >= MEL_SAMPLES:              # MEL_SAMPLES = 1760
    audio_tensor[0, :] = self.audio[-self.new_audio_samples : ... + MEL_SAMPLES]
    self.new_audio_samples = max(0, self.new_audio_samples - SAMPLES_PER_CHUNK)  # advance by 1280, not 1760
    ...
    mels = (mels / 10) + 2                                  # <-- same transform, quoted verbatim
    mels = mels.reshape((1, 1, -1, NUM_MELS))
    # shift mel ring buffer left, append new frames
    while self.new_mels >= EMB_FEATURES:                    # EMB_FEATURES = 76
        mels_tensor[0, :, :, 0] = self.mels[-self.new_mels : ... + EMB_FEATURES]
        self.new_mels = max(0, self.new_mels - EMB_STEP)    # advance by 8
        ...
        yield emb  # shape (1,1,-1,96)
```
and `OpenWakeWord.process_streaming` (the wakeword head) does the equivalent ring-buffer shift over `self.embeddings` (size `MAX_EMB = 10s × 8 = 80` rows) and fires the model whenever `>= self.input_windows` (16) new embeddings are available, advancing by 1 embedding each inference — i.e. **fires once per new embedding, not once per chunk**, though in the 1-embedding-per-chunk steady state this is the same cadence.

### 2f. Warm-up / padding at stream start — two different strategies observed

- **Original Python (`openwakeword`)**: `melspectrogram_buffer` seeded to constant `1.0` (76×32 ones), `feature_buffer` seeded by running 4 s of ±1000-range random int16 noise through the real pipeline once. Model.predict also explicitly zeroes the first 5 output frames regardless (`model.py` line ~330): `"# Zero predictions for first 5 frames during model initialization" — if len(self.prediction_buffer[cls]) < 5: predictions[cls] = 0.0`.
- **pyopen-wakeword (C-API, what HA's current add-on runs)**: pre-fills with **silence**, not noise — `AUTOFILL_SECONDS = 8`; on init/reset, `new_audio_samples` is set to `8 * 16000 = 128000` against an all-zero `audio` ring buffer (`MAX_SECONDS=10` → 160,000-sample buffer), which forces 8 seconds' worth of real (but silent) mel/embedding computation to run through the pipeline before the buffers are considered "primed." This is functionally a warm-up delay of several seconds of silence-derived embeddings/predictions rather than openWakeWord's one-shot random-noise seed + "zero first 5 predictions" rule.

For a from-scratch Android port either approach is defensible; the important thing is **do not feed real predictions to the caller until at least ~5 chunks (400 ms) have passed and the buffers contain audio-derived (not garbage) data** — both reference implementations independently converged on "don't trust the first handful of frames."

---

## 3. Detection post-processing

### openWakeWord core library (`model.py`)
- **No hard-coded default threshold** in the library itself — `Model.predict()` just returns a raw float score per model/frame; thresholding is left to the caller. The `patience`/`debounce_time` args (mutually exclusive, lines 340–359) are opt-in and disabled by default:
  ```python
  if patience != {} or debounce_time > 0:
      if threshold == {}:
          raise ValueError(...)
      if patience != {} and debounce_time > 0:
          raise ValueError("... cannot be used together!")
      for mdl in predictions.keys():
          parent_model = self.get_parent_model_from_label(mdl)
          if predictions[mdl] != 0.0:
              if parent_model in patience.keys():
                  scores = np.array(self.prediction_buffer[mdl])[-patience[parent_model]:]
                  if (scores >= threshold[parent_model]).sum() < patience[parent_model]:
                      predictions[mdl] = 0.0
              elif debounce_time > 0:
                  if parent_model in threshold.keys():
                      n_frames = int(np.ceil(debounce_time/(n_prepared_samples/16000)))
                      recent_predictions = np.array(self.prediction_buffer[mdl])[-n_frames:]
                      if predictions[mdl] >= threshold[parent_model] and \
                         (recent_predictions >= threshold[parent_model]).sum() > 0:
                          predictions[mdl] = 0.0
  ```
  `patience`: require N consecutive frames ≥ threshold, else zero out (reduces false positives). `debounce_time`: suppress a positive detection if another positive already occurred within the last `debounce_time` seconds (simple refractory window). Both are dict-keyed per model and **off unless the caller opts in**.
- `prediction_buffer` is a per-label `deque(maxlen=30)` (~2.4 s of history at 80 ms/frame) feeding both the above logic and the "zero first 5 frames" warm-up rule.
- README-recommended threshold: **0.5** ("All of the included openWakeWord models were trained to work well with a default threshold of `0.5` for a positive prediction").
- Optional Silero VAD gate (`vad_threshold`, default 0/disabled): when enabled, looks at VAD scores from **3–4 frames of lookback** (`vad_frames = list(self.vad.prediction_buffer)[-7:-4]`) and zeroes the wakeword score if `max(vad_frames) < vad_threshold`.
- Optional SpeexDSP noise suppression (`enable_speex_noise_suppression`, default off).
- Optional per-model "custom verifier" classifier (sklearn `predict_proba`) that only runs when the base score ≥ `custom_verifier_threshold` (default 0.1) — orthogonal to the debounce logic, not relevant to a first Android port.

### What Home Assistant *actually* runs: `wyoming-openwakeword` (its own, simpler loop)
`wyoming_openwakeword/__main__.py` CLI defaults:
```python
parser.add_argument("--threshold", type=float, default=0.5, ...)
parser.add_argument("--trigger-level", type=int, default=1, ...)
parser.add_argument("--refractory-seconds", type=float, default=2.0, ...)
```
`handler.py` per-chunk logic (this is the actual production streaming loop HA drives, and it's much simpler than the full `Model.predict()` patience/debounce machinery — it re-implements its own trigger-level + refractory scheme on top of the raw per-frame probability stream):
```python
for features in self.oww_features.process_streaming(chunk.audio):
    for detector in self.detectors.values():
        skip_detector = (detector.last_triggered is not None) and (
            (time.monotonic() - detector.last_triggered) < self.refractory_seconds
        )
        for prob in detector.oww_model.process_streaming(features):
            if skip_detector:
                continue
            if prob <= self.threshold:
                continue
            detector.triggers_left -= 1
            if detector.triggers_left > 0:
                continue
            detector.is_detected = True
            detector.last_triggered = time.monotonic()
            await self.write_event(Detection(name=detector.id, timestamp=self.audio_timestamp).event())
```
So HA's production algorithm, in plain terms:
1. Score > threshold (default **0.5**) required.
2. `trigger_level` (default **1**, i.e. disabled/fires on first qualifying frame) — set higher to require N consecutive above-threshold frames before firing (note: unlike openWakeWord's `patience`, this is a simple decrementing counter that is **only reset by the refractory timer clearing `last_triggered`**, not reset when a frame drops back under threshold — read the code carefully if you port it exactly, this is a subtle difference from "N consecutive frames").
3. After firing, a hard **2-second refractory window** (wall-clock `time.monotonic()`, not frame-count based) during which further detections for that same wake word are suppressed outright — but note the audio still flows through `oww_model.process_streaming(features)` even while skipped, so the embedding/wakeword-head state keeps advancing (only the *detection decision* is skipped, not the inference).
4. Uses `time.monotonic()` real-clock refractory rather than a frame-counted one — matters if your Android port runs inference bursty/behind real time.

This is the "simpler streaming loop" the task asked about — and yes, it is meaningfully simpler than the full `openwakeword.Model.predict()` (no VAD gate, no custom verifier, no patience-with-scores-array logic) and is the better reference to copy for a first Android port: **threshold 0.5, trigger_level 1, refractory 2.0 s, real-clock-based.**

---

## 4. Quantization / dtype / file sizes / license

- **All models are float32** — no int8/uint8 quantization anywhere in the pipeline. Confirmed by: every buffer/tensor in `utils.py` and `model.py` is explicitly `np.float32`; `pyopen-wakeword`'s ctypes code reads output via `np.dtype(np.float32).itemsize`; nothing in the README mentions quantization. (README does note that a *different*, unrelated project — `microWakeWord` — targets quantized models for ESP32-class hardware; openWakeWord itself does not.)
- File sizes (v0.5.1 tflite assets, bytes): melspectrogram 1,092,516; embedding_model 1,330,312; alexa_v0.1 855,312; hey_jarvis_v0.1 1,278,912; hey_mycroft_v0.1 860,300; hey_rhasspy_v0.1 416,140; timer_v0.1 1,743,316; weather_v0.1 1,150,224; okay_nabu (from pyopen-wakeword) 206,380. All comfortably small enough to bundle in an APK.
- **License**: quoted directly from README `# License` section:
  > "All of the code in this repository is licensed under the **Apache 2.0** license. All of the included pre-trained models are licensed under the [Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International](https://creativecommons.org/licenses/by-nc-sa/4.0/) license due to the inclusion of datasets with unknown or restrictive licensing as part of the training data."
  So: **code = Apache-2.0, pretrained model weights = CC BY-NC-SA 4.0 (non-commercial)**. This applies to `alexa`/`hey_jarvis`/`hey_mycroft`/`hey_rhasspy`/`timer`/`weather`. The embedding/melspectrogram feature-extraction models trace back to Google's TFHub `speech_embedding` module, which the README says is Apache-2.0, but openWakeWord's own re-implementations of them are distributed under the repo's blanket model license (CC BY-NC-SA) same as the wake word heads — the README doesn't carve out an exception for them, so treat melspectrogram/embedding as CC BY-NC-SA too unless you verify otherwise. **`okay_nabu.tflite`'s license is unstated** in `pyopen-wakeword` (repo is Apache-2.0 for code, `LICENSE` file only — no separate model license note found); given it's clearly derived from the same non-commercial training pipeline, assume the same CC BY-NC-SA restriction applies unless Nabu Casa states otherwise. **This is a real gotcha for a personal-use Echo Dashboard project** (non-commercial personal use is fine; redistributing/selling an app bundling these models would not be).

---

## 5. Exact download URLs — feature models + ok_nabu/hey_jarvis/alexa

**Shared feature models (v0.5.1):**
- `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite`
- `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite`

**Wake word models, v0.1 (as HA's older openWakeWord add-on used directly from this repo):**
- `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/alexa_v0.1.tflite`
- `https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/hey_jarvis_v0.1.tflite`
- No `ok_nabu_v0.1.tflite` exists here (checked all 8 release tags v0.1.1→v0.6.0 — HTTP 404, no "nabu" string in any release's asset list).

**`ok_nabu` (as HA's *current* add-on actually ships it, via `pyopen-wakeword`, which vendors it directly in the pip package rather than downloading it at runtime):**
- `https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/models/okay_nabu.tflite` (note: filename is `okay_nabu`, not `ok_nabu`; the wyoming-side model ID/wake phrase is also `"okay_nabu"` per `pyopen_wakeword/const.py`: `Model.OKAY_NABU = "okay_nabu"`)
- Same repo also vendors matching copies of `alexa.tflite`, `hey_jarvis.tflite`, `hey_mycroft.tflite`, `hey_rhasspy.tflite`, `melspectrogram.tflite`, `embedding_model.tflite` at `https://raw.githubusercontent.com/rhasspy/pyopen-wakeword/main/pyopen_wakeword/models/<name>.tflite` — these are size-identical to the openWakeWord release assets (just without the `_v0.1` suffix), so either source works for those four; `okay_nabu` is *only* available from this repo.
- **No HuggingFace repo exists** for any of these models — I checked the README and found no HF references at all; everything is GitHub-hosted (release assets for the main repo, or vendored-in-package for `pyopen-wakeword`).

---

## 6. Gotchas for running these graphs outside Python — Android-specific findings

This is the most safety-critical section. Findings sourced from three independent artifacts: (a) a `strings` scan I ran on the actual downloaded `melspectrogram.tflite` v0.5.1 binary, (b) GitHub issues #223/#239/#340 on `dscripka/openWakeWord`, and (c) a real third-party Android/Kotlin port (`Willy8m/openWakeWord-Android`) linked from issue #223.

### 6a. Standard `org.tensorflow:tensorflow-lite` (no Flex/select-tf-ops) appears sufficient — melspectrogram does NOT use RFFT/Flex ops
I downloaded `melspectrogram.tflite` (v0.5.1, 1,092,516 bytes) directly and ran `strings` on the raw flatbuffer looking for the custom-op string names that TFLite's Select-TF-Ops (Flex) delegate embeds (`FlexRFFT`, `FlexStft`, `Rfft`, etc. — these appear as literal readable strings in a flatbuffer that uses the Flex delegate, e.g. as seen in unrelated `tensorflow/flutter-tflite` issues #169/#175 for other STFT-based models). **Result: zero matches for "rfft", "fft", "stft", "flex", or "select" anywhere in the binary.** What I *did* find were dozens of tensor names like `convolution`, `convolution_1` … `convolution_11`, `convolution/Squeeze`, `convolution/ExpandDims` — consistent with the STFT/mel-filterbank having been implemented as a sequence of standard `CONV_2D`/`RESHAPE`/`MUL` builtin ops (a common trick: express the DFT as a fixed-weight convolution instead of calling `tf.signal.rfft`, specifically to keep the graph on TFLite's builtin op set). **I could not find any GitHub issue reporting a genuine "unsupported op"/Flex-delegate error for this model** — the two Android-related issues that exist (#223, #239) describe a different failure mode (§6b), not a missing-op error. Confidence: high but not 100% (I did not fully parse the flatbuffer's operator-code table, just `strings`-scanned it; if you hit a real "Select TensorFlow op(s) ... not supported" runtime error, that would falsify this, but nothing in the issue tracker or the binary scan suggests it).

**Practical implication: you likely do NOT need the `org.tensorflow:tensorflow-lite-select-tf-ops` artifact for any of the three model stages.** Plain `org.tensorflow:tensorflow-lite` should load and run all of melspectrogram/embedding/wakeword-head.

### 6b. The real Android crash: dynamic input shape + auto-allocation, not missing ops
Issue **#223** ("how to recreate melspectrogram tflite"), quoted verbatim:
> "in android it is auto applied by tf on initialization and crashes
> ```
> Failed to load models: Internal error: Unexpected failure when preparing tensor allocations: tensorflow/lite/util.cc BytesRequired number of elements overflowed.
> Node number 3 (CONV_2D) failed to prepare.
> Failed to apply the default TensorFlow Lite delegate indexed at 0.
> ```
> i can fix it if i can recreate the melspectrogram model with the required input tensor"

Root cause: `melspectrogram.tflite`'s input tensor has a **dynamic/unspecified second dimension** by design — the Python code explicitly resizes it before every use:
```python
self.melspec_model = tflite.Interpreter(model_path=melspec_model_path, num_threads=ncpu)
self.melspec_model.resize_tensor_input(0, [1, 1280], strict=True)  # initialize with fixed input size
self.melspec_model.allocate_tensors()
```
If you let Android's standard codegen path (ML Model Binding / auto-generated wrapper classes, or any code that calls `interpreter.allocateTensors()` without first calling `resizeInput()`) auto-allocate against whatever default/placeholder shape is baked into the flatbuffer, tensor-size arithmetic overflows and it crashes at `allocateTensors()`, before you even get to `invoke()`. **This is exactly the interpreter-level `resizeInput`-before-`allocateTensors` requirement the task asked about** — confirmed as the actual, reported, real-world Android failure mode (not a Flex-ops issue).

**Fix, straight from the Python and the confirmed-working C-API reimplementation:** always call `interpreter.resizeInput(0, intArrayOf(1, 1760))` (or `1280` if you replicate the very first cold-start call before you have 480 samples of left-context available — see §2a/2f) **then** `interpreter.allocateTensors()`, every time the shape changes, before `run()`/`invoke()`. In Kotlin with the standard `org.tensorflow:tensorflow-lite` `Interpreter` class this is `interpreter.resizeInput(0, intArrayOf(1, N))` followed by `interpreter.allocateTensors()` (or simply call `interpreter.run(...)` after a `resizeInput`, since the standard Kotlin `Interpreter` API auto-reallocates on `run()` if the input shape changed — but you must still explicitly `resizeInput` first; you cannot rely on shape being inferred from the buffer you pass in). Do this **once** at steady state with a fixed size (1760) rather than per-call if you always feed the same-length window, mirroring `pyopen-wakeword`'s single upfront `TfLiteInterpreterResizeInputTensor(...)` call.

The one real-world Android/Kotlin implementation I found (`Willy8m/openWakeWord-Android`, linked from issue #223) **worked around this by not using the standard TFLite interpreter for melspectrogram at all** — it uses `onnxruntime-android` for the melspectrogram stage (`OrtSession`, feeding a `FloatBuffer` with an explicit `longArrayOf(1, 1760)` shape via `OnnxTensor.createTensor`, which ONNX Runtime handles natively without a separate resize call) and reserves TFLite (via Android Studio's auto-generated ML Model Binding classes, i.e. plain `org.tensorflow:tensorflow-lite`) only for the embedding model and wakeword head, both of which have simple fixed shapes (`[1,76,32,1]` and `[1,16,96]`) and loaded/ran without any special handling. This independently confirms: **embedding_model.tflite and the wakeword-head .tflite files are trivially fine on stock Android TFLite; melspectrogram.tflite is the only one that needs careful handling** (and per §6a, that handling is a shape/allocation issue, solvable in pure TFLite by explicit resize-then-allocate — ONNX was this developer's workaround of choice, not a requirement).

That same linked Kotlin code is a nearly line-for-line confirmation of every numeric constant in this document:
```kotlin
private val audioBufferSizeInBytes = 1280 * 4        // 1280 samples, float32 AudioRecord read
private val rawDataBuffer = FloatArray(1760)         // matches MEL_SAMPLES
private val melspecBuffer = Array(1) { Array(76) { Array(32) { FloatArray(1) } } }   // 76×32
private val embeddingBuffer = Array(1) { Array(16) { FloatArray(96) } }               // 16×96
...
System.arraycopy(rawDataBuffer, 1280, rawDataBuffer, 0, 480)   // shift, keep 480 samples context
System.arraycopy(newAudioData, 0, rawDataBuffer, 480, 1280)    // append new 1280
...
for (i in 0 until 68) { melspecBuffer[0][i][j][0] = melspecBuffer[0][i + 8][j][0] }   // shift by 8 (76-8=68)
for (i in 0 until 8) { melspecBuffer[0][68 + i][j][0] = 2 + melspecPredictions[0][0][i][j] / 10 }  // x/10+2 transform
...
for (i in 0 until 15) { embeddingBuffer[0][i][j] = embeddingBuffer[0][i + 1][j] }     // shift by 1 (16-1=15)
embeddingBuffer[0][15][j] = newEmbeddings[j]
```
This confirms every shape/stride number in §1–§2 against a fourth, independent implementation.

### 6c. Other real-world reports (lower relevance, noted for completeness)
- Issue **#340** ("16KB issue with old tensorflow lib"): unrelated to model graph compatibility — it's Android 15's 16 KB memory-page-size requirement for native `.so` libraries. `libtensorflowlite_jni.so` (bundled inside older `org.tensorflow:tensorflow-lite` AARs) had LOAD segments not 16 KB-aligned, which Google Play now rejects for apps targeting Android 15+ (enforced from Nov 1, 2025). **Action item for your port: make sure you pull a recent-enough `tensorflow-lite` AAR version that has been rebuilt with 16 KB-page alignment**, or you'll hit Play Store rejection independent of anything else in this doc. No resolution/comments were posted on that issue as of this research.
- Issue **#239** ("how to infer wakeword on mobile device for android & ios"): just a bare feature request with no answer/comments — confirms there's no official guidance from the maintainer for mobile deployment; you're on your own using the pattern in §6b.

### 6d. Batch dimension
The embedding model's *batch* dimension (not the frame-window dim) is also dynamic in the Python tflite path (`resize_tensor_input(0, [x.shape[0], 76, 32, 1], strict=True)` when batch ≠ 1) — but for a real-time streaming port you will only ever run batch=1, so you can simply fix the embedding model's input shape to `[1, 76, 32, 1]` once at load time and never resize it again. Same for the wakeword head — always batch=1 for streaming. Only the melspectrogram model's sample-count dimension needs the resize-then-allocate dance, and only if you don't fix it to a single constant (1760) forever, which — per §2c — you can, since the steady-state call always feeds exactly 1760 samples.

---

## Summary table of everything you need to hardcode in Kotlin

| Constant | Value |
|---|---|
| Sample rate | 16000 Hz, 16-bit PCM mono |
| Chunk size | 1280 samples (80 ms) |
| Melspec model input (steady-state) | `[1, 1760]` float32 (1280 new + 480 left-context samples) |
| Melspec model output | `[1, 1, 8, 32]` new frames per call (32 mel bins) |
| Melspec transform | `x = x/10 + 2`, applied once right after invoke |
| Mel ring buffer window | last 76 frames × 32 mels, shift-by-8 each update |
| Embedding model input | `[1, 76, 32, 1]` |
| Embedding model output | `[1, 96]` (squeeze from `[1,1,1,96]`) |
| Embedding cadence | 1 new embedding per 80 ms audio chunk (stride 8 mel frames) |
| Embedding ring buffer window | last 16 embeddings × 96 dims, shift-by-1 each update |
| Wakeword head input | `[1, 16, 96]` |
| Wakeword head output | `[1, 1]` (single score) for alexa/hey_jarvis/hey_mycroft/hey_rhasspy/okay_nabu; `[1, 7]` for timer |
| Default threshold | 0.5 |
| Default trigger_level (HA add-on) | 1 (fires on first qualifying frame) |
| Default refractory (HA add-on) | 2.0 s, wall-clock |
| Model dtype | float32 throughout, no quantization |
| Model license | code Apache-2.0 / weights CC BY-NC-SA 4.0 (non-commercial) — `okay_nabu` license unstated, assume same |
| Flex/select-tf-ops needed? | No evidence found — `strings` scan of melspectrogram.tflite shows plain builtin conv ops, no rfft/flex tokens |
| Known Android crash | dynamic melspec input shape + auto-`allocateTensors()` → must explicit `resizeInput` first |
| Known Play Store issue | pre-16KB-page-aligned `tensorflow-lite` AARs rejected on Android 15+ targets (unrelated to this model, just the AAR build) |

## What I could NOT verify
- Whether `okay_nabu.tflite`'s wakeword-head input window is also 16 embeddings (I assumed yes since it shares the same embedding/melspec backbone and file-size pattern as the others, but I did not parse its flatbuffer directly — only inferred from `pyopen-wakeword`'s generic `self.input_windows = input_shape[1]` code path, which reads it dynamically per-model rather than hardcoding 16 for all models).
- The exact license terms Nabu Casa/Open Home Foundation apply to `okay_nabu.tflite` specifically — no LICENSE note accompanies it in `pyopen-wakeword` beyond the repo's blanket Apache-2.0 (which covers code, not necessarily model weights).
- Full confirmation that melspectrogram.tflite contains zero Flex ops — based on a `strings` scan of the binary plus absence of any reported "unsupported op" issue, not a full flatbuffer operator-table parse. If you hit a real runtime "Select TensorFlow op(s) ... not supported" error, this conclusion would need revisiting (try adding `tensorflow-lite-select-tf-ops` as a fallback if that ever happens, but current evidence says it shouldn't be necessary).
