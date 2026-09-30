# Photorealistic avatar generator

Generates the demo avatar pool in `../impl/src/main/resources/avatars-photo/`: `man-1.jpg` …
`man-100.jpg` and `woman-1.jpg` … `woman-100.jpg`. Each is a 512×512 JPEG of a generated,
non-existent person, cropped square with the nose tip at the centre. It is a one-off local tool,
not part of the Lambda or its build. See `mootmaker/designs/photorealistic-demo-avatars.md`.

## How it works

1. `pool.py` builds 200 prompts deterministically: sex, age 23–64, ethnicity mix, hair, glasses,
   clothing, expression, pose and background, all drawn from a fixed-seed RNG. The mix per sex is
   `ETHNICITY_COUNTS`, currently 50 white European, 20 South Asian, 15 East Asian and 15 Black.

   **The committed pool and `manifest.json` were generated at an older mix: 80/10/5/5.** Changing
   the mix reshuffles every prompt, so the script no longer reproduces those images, and
   `--reroll` would produce a person from the new mix. To get a consistent pool at the current mix,
   delete the images and `manifest.json` and regenerate all 200.
2. Stable Diffusion 1.5 renders each prompt at 512×768 with a per-image seed.
3. `center_face.py` finds the face with OpenCV's YuNet detector and crops a square of 1.8× the
   face width, centred on the nose tip. The script rejects the attempt and tries the next seed if:
   - there isn't exactly one face;
   - the crop would leave the image;
   - the crop is under 216 px;
   - the result is black-and-white.
4. `manifest.json` records every accepted image's prompt and seed, plus the seeds that failed or
   were rejected. It is what makes the pool reproducible, so commit it with the images.

## Setup

Python 3.14 and an NVIDIA GPU. It was built on a 4 GB GTX 1650.

```bash
python3 -m venv ~/.venvs/avatar-gen
~/.venvs/avatar-gen/bin/pip install -r requirements.txt
```

The model (`stable-diffusion-v1-5/stable-diffusion-v1-5`, about 2 GB) downloads to
`~/.cache/huggingface` on first run.

## Running

```bash
cd photorealistic-avatar-generator
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True ~/.venvs/avatar-gen/bin/python pool.py
```

It is resumable: images that already exist are kept, so an interrupted run carries on where it
stopped. The first full pool took about 6 hours on the GTX 1650, rerolls included, averaging 2.2 attempts per
accepted image at about 48 s each. The run
outlives the Claude Code background time limit, so start it detached (`setsid nohup …`).

**Review every image before committing.** Detection can't catch things like malformed hands,
glitched eyes or cartoon moustaches. To make contact sheets, which land in `work/sheets/`:

```bash
~/.venvs/avatar-gen/bin/python sheets.py man 1 25
```

To regenerate bad images from their next unused seeds:

```bash
~/.venvs/avatar-gen/bin/python pool.py --reroll man-12,woman-13
```

If a prompt misfires whatever the seed, add an `(old, new)` substitution to `PROMPT_OVERRIDES` in
`pool.py` and reroll it. For example, a lone "moustache" comes out as a stuck-on cartoon one.

## Why it runs the way it does

- **fp32, streamed to the GPU one layer at a time.** GTX 16-series cards produce NaN (black images)
  from SD 1.5 in fp16. fp32 weights don't fit in 4 GB next to a desktop, so leaf-level group
  offload streams them in, peaking at about 0.8 GB of VRAM. On a card with more VRAM and working
  fp16, both measures can go.
- **SD 1.5, not FLUX.2 [klein].** The design prefers FLUX.2 [klein], but it needs 6–10 GB of VRAM.
- **Rendered taller than square**, so there's usually room to centre the nose without running out of
  image.

## Licences

- **Stable Diffusion 1.5:** CreativeML OpenRAIL-M, which permits redistributing its outputs.
  Check again if the model is ever changed.
- **YuNet** (`face_detection_yunet_2023mar.onnx`): MIT, from
  [opencv_zoo](https://github.com/opencv/opencv_zoo). Its licence is in
  `face_detection_yunet_LICENSE`.
