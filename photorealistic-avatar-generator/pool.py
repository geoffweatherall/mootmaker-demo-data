"""Generate the photorealistic avatar pool: 100 man-N and 100 woman-N, nose-centred 512x512 JPEGs.

Usage: pool.py [--reroll man-3,woman-17 ...]
Resumable: existing outputs are kept. --reroll discards the named images and retries from the next seed.
"""
import json
import os
import random
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "..", "impl", "src", "main", "resources", "avatars-photo")
RAW_DIR = os.path.join(HERE, "work", "raw")
MANIFEST_PATH = os.path.join(HERE, "manifest.json")

MODEL = "stable-diffusion-v1-5/stable-diffusion-v1-5"
STEPS, GUIDANCE, WIDTH, HEIGHT = 25, 7.0, 512, 768
MAX_ATTEMPTS = 8
PER_SEX = 100
ETHNICITY_COUNTS = [("white European", 50), ("South Asian", 20), ("East Asian", 15), ("Black", 15)]

NEGATIVE = (
    "cartoon, illustration, painting, drawing, 3d render, anime, deformed, disfigured, blurry, lowres, "
    "bad anatomy, extra limbs, hands, hand on face, fingers, arm raised, multiple people, two people, "
    "cropped head, watermark, text, signature"
)
EXPRESSIONS = [
    "a warm closed-mouth smile", "a slight smile", "a friendly smile", "a relaxed neutral expression",
    "a gentle smile", "a confident smile",
]
POSES = ["facing the camera", "facing the camera", "head turned slightly, looking at the camera"]
BACKGROUNDS = [
    "softly blurred office background", "plain light grey studio background", "blurred bookshelf background",
    "blurred bright window background", "plain neutral wall background", "blurred modern workplace background",
]
CLOTHING = {
    "woman": ["a cream blouse", "a navy blazer", "a grey cardigan", "a black blazer", "a green sweater",
              "a white shirt", "a burgundy jumper", "a denim shirt", "a teal blouse", "a charcoal blazer",
              "a striped top", "a mustard sweater", "a light blue shirt", "a black turtleneck"],
    "man": ["a light blue shirt", "a grey sweater", "a navy suit jacket", "a white shirt", "a dark sweater",
            "a green polo shirt", "a tweed jacket", "a blue checked shirt", "a black t-shirt under a blazer",
            "an open-collar grey shirt", "a navy quarter-zip jumper", "a denim shirt", "a charcoal suit jacket"],
}


# (old, new) substitutions for prompts that reliably misfire whatever the seed, e.g. SD 1.5 draws a lone
# "moustache" as a stuck-on cartoon one, and a coloured garment next to a hair colour bleeds into the hair.
# Entries are tied to the exact prompts ETHNICITY_COUNTS produces, so changing the mix invalidates them all.
PROMPT_OVERRIDES = {}


def hair_for(rng, sex, ethnicity, age):
    older = age >= 52 and rng.random() < 0.6
    if sex == "woman":
        if older:
            return rng.choice(["short grey hair", "a silver bob haircut", "shoulder-length grey hair", "short silver hair"])
        if ethnicity == "white European":
            colour = rng.choice(["blonde", "light brown", "brown", "dark brown", "auburn", "red", "black", "honey blonde"])
        elif ethnicity == "Black":
            return rng.choice(["natural curly hair", "braided hair", "short natural hair", "long straightened hair"])
        else:
            colour = "black"
        return f"{rng.choice(['long', 'shoulder-length', 'short', 'wavy', 'tied back'])} {colour} hair"
    if older:
        hair = rng.choice(["short grey hair", "thinning grey hair", "white hair", "a shaved head", "receding grey hair"])
    elif ethnicity == "white European":
        hair = f"{rng.choice(['short', 'short', 'receding', 'curly', 'side-parted', 'cropped'])} " \
               f"{rng.choice(['blond', 'light brown', 'brown', 'dark brown', 'black', 'red'])} hair"
    elif ethnicity == "Black":
        hair = rng.choice(["short cropped hair", "a shaved head", "short twists"])
    else:
        hair = rng.choice(["short black hair", "side-parted black hair", "cropped black hair"])
    if rng.random() < 0.35:
        hair += rng.choice([" and a short beard", " and stubble", " and a trimmed beard", " and a moustache"])
    return hair


def build_specs():
    rng = random.Random(20260930)
    specs = []
    for sex in ("man", "woman"):
        ethnicities = [e for e, n in ETHNICITY_COUNTS for _ in range(n)]
        rng.shuffle(ethnicities)
        for n, ethnicity in enumerate(ethnicities, 1):
            age = rng.randint(23, 64)
            glasses = " and glasses" if rng.random() < 0.2 else ""
            prompt = (
                f"upper body portrait photograph of a {age} year old {ethnicity} {sex} with "
                f"{hair_for(rng, sex, ethnicity, age)}{glasses}, wearing {rng.choice(CLOTHING[sex])}, "
                f"centered in the frame, {rng.choice(POSES)}, {rng.choice(EXPRESSIONS)}, "
                f"{rng.choice(BACKGROUNDS)}, soft natural lighting, sharp focus, high detail skin"
            )
            # Seeds are disjoint per image: man-N uses 100000+N*100+attempt, woman-N 200000+...
            base_seed = (100000 if sex == "man" else 200000) + n * 100
            if f"{sex}-{n}" in PROMPT_OVERRIDES:
                old, new = PROMPT_OVERRIDES[f"{sex}-{n}"]
                assert old in prompt, f"{sex}-{n}: override target {old!r} not in prompt"
                prompt = prompt.replace(old, new)
            specs.append({"name": f"{sex}-{n}", "sex": sex, "age": age, "ethnicity": ethnicity,
                          "prompt": prompt, "base_seed": base_seed})
    return specs


def main():
    out_dir, raw_dir, manifest_path = OUT_DIR, RAW_DIR, MANIFEST_PATH
    reroll = set(sys.argv[sys.argv.index("--reroll") + 1].split(",")) if "--reroll" in sys.argv else set()
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(raw_dir, exist_ok=True)
    manifest = json.load(open(manifest_path))["images"] if os.path.exists(manifest_path) else {}

    specs = build_specs()
    todo = []
    for s in specs:
        path = os.path.join(out_dir, f"{s['name']}.jpg")
        entry = manifest.get(s["name"], {})
        if s["name"] in reroll and os.path.exists(path):
            os.remove(path)
            entry["rejected_seeds"] = entry.get("rejected_seeds", []) + [entry.pop("seed")]
            manifest[s["name"]] = entry
        if not os.path.exists(path):
            todo.append(s)
    print(f"{len(todo)} to generate", flush=True)
    if not todo:
        return

    import numpy as np
    import torch
    from diffusers import DPMSolverMultistepScheduler, StableDiffusionPipeline
    from diffusers.hooks import apply_group_offloading
    from PIL import Image

    sys.path.insert(0, HERE)
    from center_face import nose_centred_crop

    # GTX 1650: fp16 produces NaN and fp32 weights don't fit in 4 GB, so run fp32 streamed per layer.
    pipe = StableDiffusionPipeline.from_pretrained(MODEL, torch_dtype=torch.float32, variant="fp16", safety_checker=None)
    pipe.scheduler = DPMSolverMultistepScheduler.from_config(pipe.scheduler.config, use_karras_sigmas=True)
    cuda, cpu = torch.device("cuda"), torch.device("cpu")
    pipe.unet.enable_group_offload(cuda, offload_device=cpu, offload_type="leaf_level", use_stream=True)
    pipe.vae.enable_group_offload(cuda, offload_device=cpu, offload_type="leaf_level", use_stream=True)
    apply_group_offloading(pipe.text_encoder, cuda, offload_device=cpu, offload_type="leaf_level", use_stream=True)
    pipe.set_progress_bar_config(disable=True)

    t0 = time.time()
    for k, s in enumerate(todo, 1):
        entry = manifest.get(s["name"], {})
        used = set(entry.get("rejected_seeds", [])) | set(entry.get("failed_seeds", []))
        seeds = [s["base_seed"] + a for a in range(100) if s["base_seed"] + a not in used][:MAX_ATTEMPTS]
        failed = []
        for seed in seeds:
            raw = os.path.join(raw_dir, f"{s['name']}-{seed}.png")
            pipe(s["prompt"], negative_prompt=NEGATIVE, num_inference_steps=STEPS, guidance_scale=GUIDANCE,
                 width=WIDTH, height=HEIGHT, generator=torch.Generator("cpu").manual_seed(seed)).images[0].save(raw)
            crop, note = nose_centred_crop(raw)
            # SD 1.5 occasionally renders a black-and-white photo; colour ones measure >= 0.2 mean saturation.
            if crop is not None and np.asarray(crop.convert("HSV"))[..., 1].mean() / 255 < 0.08:
                crop, note = None, "monochrome"
            print(f"[{k}/{len(todo)} {(time.time() - t0) / 60:.0f}m] {s['name']} seed {seed}: {note}", flush=True)
            if crop is not None:
                crop.convert("RGB").save(os.path.join(out_dir, f"{s['name']}.jpg"), quality=90, optimize=True)
                entry.update({k2: s[k2] for k2 in ("sex", "age", "ethnicity", "prompt")})
                entry["seed"] = seed
                break
            failed.append(seed)
        entry["failed_seeds"] = sorted(set(entry.get("failed_seeds", [])) | set(failed) - {entry.get("seed")})
        manifest[s["name"]] = entry
        with open(manifest_path, "w") as f:
            json.dump({"model": MODEL, "steps": STEPS, "guidance": GUIDANCE, "size": [WIDTH, HEIGHT],
                       "scheduler": "DPMSolverMultistep, karras sigmas", "negative": NEGATIVE,
                       "crop": "nose-centred square, 1.8x YuNet face width, resized to 512", "images": manifest},
                      f, indent=2)
    print(f"done in {(time.time() - t0) / 60:.0f} min", flush=True)


if __name__ == "__main__":
    main()
