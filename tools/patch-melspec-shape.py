"""Patch melspectrogram.tflite's dynamic input dim to a static [1, 1760].

The flatbuffer stores each tensor's shape as a vector of int32. We locate subgraph 0's
input tensor via the generated read API, compute the byte offset of its shape vector
elements inside the buffer, and overwrite them in place (same length -> no re-serialize).
"""
import sys
import flatbuffers
from tflite.Model import Model

src, dst = sys.argv[1], sys.argv[2]
buf = bytearray(open(src, "rb").read())

m = Model.GetRootAsModel(bytes(buf), 0)
sg = m.Subgraphs(0)
print("subgraph inputs:", [sg.Inputs(i) for i in range(sg.InputsLength())])

ti = sg.Inputs(0)
t = sg.Tensors(ti)
name = t.Name().decode()
shape = [t.Shape(j) for j in range(t.ShapeLength())]
print(f"input tensor #{ti} name={name!r} shape={shape}")

# Also check shape_signature (newer models store the dynamic form there).
sig = [t.ShapeSignature(j) for j in range(t.ShapeSignatureLength())]
print("shape_signature:", sig)

# Compute byte offset of the shape vector's data inside the buffer.
# Generated accessor: t.Shape(j) reads int32 at _tab.Vector(o) + j*4.
from flatbuffers import number_types as N
o = flatbuffers.number_types.UOffsetTFlags.py_type(t._tab.Offset(4))  # field 4 = shape
assert o != 0
vec = t._tab.Vector(o)
print("shape vector at byte offset", vec)

want = [1, 1760]
assert len(shape) == len(want), f"unexpected rank {shape}"
import struct
for j, v in enumerate(want):
    struct.pack_into("<i", buf, vec + j * 4, v)

# Patch shape_signature too if present (dynamic dims are -1 there).
# Tensor vtable: shape=4, type=6, buffer=8, name=10, quantization=12, is_variable=14,
# sparsity=16, shape_signature=18.
o2 = flatbuffers.number_types.UOffsetTFlags.py_type(t._tab.Offset(18))
if o2 != 0:
    vec2 = t._tab.Vector(o2)
    n2 = t._tab.VectorLen(o2)
    print("shape_signature vector at", vec2, "len", n2)
    for j, v in enumerate(want[:n2]):
        struct.pack_into("<i", buf, vec2 + j * 4, v)

open(dst, "wb").write(bytes(buf))

# Re-parse and verify.
m2 = Model.GetRootAsModel(bytes(buf), 0)
t2 = m2.Subgraphs(0).Tensors(m2.Subgraphs(0).Inputs(0))
print("patched shape:", [t2.Shape(j) for j in range(t2.ShapeLength())],
      "sig:", [t2.ShapeSignature(j) for j in range(t2.ShapeSignatureLength())])
print("wrote", dst, len(buf), "bytes")
