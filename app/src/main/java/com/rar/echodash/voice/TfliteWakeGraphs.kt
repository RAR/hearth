package com.rar.echodash.voice

import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the three TFLite graphs (shared melspectrogram + embedding + the chosen wake-word head)
 * from assets/wake/ and adapts them to [WakeDetector.TfGraph]. Untested Android glue — the pure
 * inference math is covered by WakeDetectorTest against fake graphs.
 *
 * CRITICAL: the melspectrogram model has a dynamic sample-count input, so we MUST resizeInput to
 * [1, 1760] then allocateTensors() once at load; otherwise Android's auto-allocation overflows
 * ("BytesRequired number of elements overflowed", openWakeWord issue #223). Embedding and head
 * have fixed shapes and need no resize. Models are float32; standard tensorflow-lite (no
 * select-tf-ops) is sufficient — the melspec graph uses builtin conv ops, verified in research.
 */
object TfliteWakeGraphs {
    private const val TAG = "TfliteWakeGraphs"
    private const val MEL_SAMPLES = 1760
    private const val MEL_OUT = 8 * 32
    private const val EMB_IN = 76 * 32
    private const val EMB_OUT = 96
    private const val HEAD_IN = 16 * 96
    private const val HEAD_OUT = 1

    /** Returns (melspec, embedding, head) graphs, or null on ANY failure (caller falls back). */
    fun load(
        assets: AssetManager,
        wakeWord: String,
    ): Triple<WakeDetector.TfGraph, WakeDetector.TfGraph, WakeDetector.TfGraph>? {
        return try {
            // The bundled melspectrogram.tflite is PRE-PATCHED (tools/patch-melspec-shape.py)
            // to a static [1, 1760] input. Upstream ships it with shape [1, 1] / signature
            // [-1, -1], and the Java Interpreter constructor allocates tensors before any
            // resizeInput can run, overflowing CONV_2D prepare ("BytesRequired number of
            // elements overflowed", openWakeWord issue #223) — resize-then-allocate CANNOT
            // fix it on Android. Never replace this asset with the raw upstream file.
            val mel = Interpreter(loadModel(assets, "melspectrogram.tflite"))
            val emb = Interpreter(loadModel(assets, "embedding_model.tflite"))
            val head = Interpreter(loadModel(assets, "$wakeWord.tflite"))
            Triple(
                graph(mel, MEL_SAMPLES, MEL_OUT),
                graph(emb, EMB_IN, EMB_OUT),
                graph(head, HEAD_IN, HEAD_OUT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to load wake models for '$wakeWord'", e)
            null
        }
    }

    private fun loadModel(assets: AssetManager, name: String): ByteBuffer {
        val bytes = assets.open("wake/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    /** Wrap a fixed-shape interpreter as a flat-float TfGraph using direct float buffers. */
    private fun graph(interp: Interpreter, inSize: Int, outSize: Int) = WakeDetector.TfGraph { input ->
        val inBuf = ByteBuffer.allocateDirect(inSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        inBuf.put(input)
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        interp.run(inBuf, outBuf)
        outBuf.rewind()
        FloatArray(outSize) { outBuf.get() }
    }
}
