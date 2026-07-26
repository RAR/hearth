"""Score 16 kHz mono WAVs against wake-word head models OFFLINE, replicating
WakeDetector.kt's streaming pipeline exactly (rings, x/10+2, 16-chunk warm-up mask).

Use this to validate a new head model BEFORE bundling it: synthesize the phrase with
piper (`python -m piper -m en_US-lessac-medium -f out.wav -- "phrase"`, resample to
16 kHz mono), then run this against the new head plus a known-good head as control.
A healthy head scores >0.9 on its own phrase and ~0 on others; a dead/collapsed model
(seen live 2026-07-18: a "Stop" head scoring ~1e-6 on everything, incl. its own phrase)
scores ~0 across the board. Needs: pip install ai-edge-litert numpy.

Usage: python score-wake-model.py utterance1_16k.wav [utterance2_16k.wav ...]
(edit `heads` below to point at the models under test)

Pipeline (must match WakeDetector.kt):
  - PCM 16 kHz mono int16 -> RAW float sample values (no +-1 normalization)
  - audioRing 1760 = 480 context + 1280 new; melspec -> 8x32 frames, x/10 + 2
  - melRing 76x32 -> embedding -> 96; embRing 16x96 -> head -> score
"""
import os, sys, wave, numpy as np
from ai_edge_litert.interpreter import Interpreter

ASSETS = "/home/rar/android_simpla_ha_dash/app/src/main/assets/wake"

def graph(path, in_shape=None):
    i = Interpreter(model_path=path)
    if in_shape is not None:
        i.resize_tensor_input(i.get_input_details()[0]["index"], in_shape)
    i.allocate_tensors()
    inp = i.get_input_details()[0]
    out = i.get_output_details()[0]
    def run(x):
        i.set_tensor(inp["index"], x.astype(np.float32).reshape(inp["shape"]))
        i.invoke()
        return i.get_tensor(out["index"]).flatten()
    return run

def read_wav_16k(path):
    with wave.open(path) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1, (path, w.getframerate(), w.getnchannels())
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)

mel = graph(f"{ASSETS}/melspectrogram.tflite")  # pre-patched to [1,1760]
emb = graph(f"{ASSETS}/embedding_model.tflite")

def score(samples, head_paths, lead=24000):
    heads = {p.split("/")[-1]: graph(p) for p in head_paths}
    audio = np.zeros(1760, dtype=np.float32)
    melring = np.zeros((76, 32), dtype=np.float32)
    embring = np.zeros((16, 96), dtype=np.float32)
    maxes = {n: 0.0 for n in heads}
    # Lead with >16 chunks of silence so the word lands AFTER the 16-chunk warm-up
    # WakeDetector masks (zero-filled rings produce garbage embeddings), then mask
    # warm-up scores exactly like the Kotlin code does.
    s = np.concatenate([np.zeros(lead, np.float32), samples, np.zeros(8000, np.float32)])
    chunk_i = 0
    for off in range(0, len(s) - 1280 + 1, 1280):
        audio[:480] = audio[1280:]
        audio[480:] = s[off:off + 1280]
        m = mel(audio).reshape(8, 32) / 10.0 + 2.0
        melring[:-8] = melring[8:]
        melring[-8:] = m
        e = emb(melring)
        embring[:-1] = embring[1:]
        embring[-1] = e
        chunk_i += 1
        for n, h in heads.items():
            v = float(h(embring)[0])
            if chunk_i > 16 and v > maxes[n]:
                maxes[n] = v
    return maxes

# Every bundled head, so a capture is scored against the model that fired AND the controls.
# Filtered by existence: `stop.tflite` has never been bundled, and an absent asset should
# drop out of the comparison rather than abort the run.
heads = [p for p in (f"{ASSETS}/{n}.tflite" for n in
                     ("stop", "ok_ember", "okay_nabu", "alexa", "hey_jarvis"))
         if os.path.exists(p)]


def score_any_alignment(samples, head_paths, steps=8):
    """Max score over every chunk alignment, not just one.

    The device scores a continuous stream, so its 1280-sample chunk boundaries fall wherever
    the mic happened to start; replaying a clip from offset 0 picks ONE arbitrary alignment.
    That is not a detail: on a real near-miss capture, ok_ember scored 0.101 to 0.658 across
    the eight alignments of the same audio, and only offset 640 reproduced the 0.48 the device
    logged. Sweeping and taking the max answers the question that actually matters — "can this
    audio trigger this model?" — instead of "did it trigger at one alignment we chose".
    """
    hop = 1280 // steps
    maxes = {p.split("/")[-1]: 0.0 for p in head_paths}
    for k in range(steps):
        for name, v in score(samples, head_paths, lead=24000 + k * hop).items():
            if v > maxes[name]:
                maxes[name] = v
    return maxes


for wav in sys.argv[1:]:
    result = score_any_alignment(read_wav_16k(wav), heads)
    print(wav, "->", {k: round(v, 3) for k, v in sorted(result.items(), key=lambda kv: -kv[1])})
