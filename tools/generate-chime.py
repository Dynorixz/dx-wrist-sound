"""Generate our original, gently decaying three-note notification. No third-party audio."""
from pathlib import Path
import math
import struct
import wave

target = Path(__file__).resolve().parents[1] / "wear/src/main/res/raw/phone_loss_chime.wav"
target.parent.mkdir(parents=True, exist_ok=True)
rate = 22050
notes = [(0.0, 523.251), (0.28, 659.255), (0.56, 783.991)]
samples = []
for i in range(int(rate * 2.0)):
    t = i / rate
    value = 0.0
    for start, frequency in notes:
        age = t - start
        if 0 <= age < 1.3:
            envelope = min(age / 0.012, 1) * math.exp(-4.2 * age) * min((1.3-age)/0.1, 1)
            value += envelope * (math.sin(2*math.pi*frequency*age) + 0.18*math.sin(4*math.pi*frequency*age)) * 0.30
    samples.append(struct.pack('<h', round(max(-1, min(1, value)) * 32767)))
with wave.open(str(target), 'wb') as output:
    output.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
    output.writeframes(b''.join(samples))
print(f"Chime: {target.name}, {target.stat().st_size} bytes, 2 seconds")
