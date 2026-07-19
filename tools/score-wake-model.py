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
import sys, wave, numpy as np
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

def score(samples, head_paths):
    heads = {p.split("/")[-1]: graph(p) for p in head_paths}
    audio = np.zeros(1760, dtype=np.float32)
    melring = np.zeros((76, 32), dtype=np.float32)
    embring = np.zeros((16, 96), dtype=np.float32)
    maxes = {n: 0.0 for n in heads}
    # Lead with >16 chunks of silence so the word lands AFTER the 16-chunk warm-up
    # WakeDetector masks (zero-filled rings produce garbage embeddings), then mask
    # warm-up scores exactly like the Kotlin code does.
    s = np.concatenate([np.zeros(24000, np.float32), samples, np.zeros(8000, np.float32)])
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

heads = [f"{ASSETS}/stop.tflite", f"{ASSETS}/ok_ember.tflite", f"{ASSETS}/okay_nabu.tflite"]
for wav in sys.argv[1:]:
    print(wav, "->", {k: round(v, 3) for k, v in score(read_wav_16k(wav), heads).items()})
