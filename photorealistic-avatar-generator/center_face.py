import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw

DETECTOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "face_detection_yunet_2023mar.onnx")
# Square side as a multiple of detected face width: profile-photo framing where hair may touch the display circle.
CROP_TO_FACE = 1.8
OUT_SIZE = 512
# Largest avatar is 36 CSS px, 108 device px at 3x; keep 2x headroom so the source is never upscaled.
MIN_CROP = 216


def nose_centred_crop(path):
    bgr = cv2.imread(path)
    h, w = bgr.shape[:2]
    det = cv2.FaceDetectorYN.create(DETECTOR, "", (w, h), score_threshold=0.8)
    _, faces = det.detect(bgr)
    if faces is None or len(faces) != 1:
        return None, f"expected 1 face, found {0 if faces is None else len(faces)}"
    face = faces[0]
    face_w = face[2]
    nose_x, nose_y = face[8], face[9]
    half = CROP_TO_FACE * face_w / 2
    left, top, right, bottom = nose_x - half, nose_y - half, nose_x + half, nose_y + half
    if left < 0 or top < 0 or right > w or bottom > h:
        return None, f"nose-centred crop {int(2 * half)}px leaves the {w}x{h} image"
    if 2 * half < MIN_CROP:
        return None, f"crop {int(2 * half)}px is below MIN_CROP {MIN_CROP}px"
    img = Image.open(path).convert("RGB")
    crop = img.crop((round(left), round(top), round(right), round(bottom)))
    return crop.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS), f"face {face_w:.0f}px, crop {2 * half:.0f}px"


def circle_preview(img, px):
    small = img.resize((px, px), Image.LANCZOS)
    mask = Image.new("L", (px * 4, px * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, px * 4, px * 4), fill=255)
    out = Image.new("RGB", (px, px), "white")
    out.paste(small, mask=mask.resize((px, px), Image.LANCZOS))
    return out


def preview_sheet(img):
    # Full-size with a centre cross, then the circle at 256 and at 36 CSS px @3x.
    guide = img.copy()
    d = ImageDraw.Draw(guide)
    c = OUT_SIZE // 2
    d.line((c - 12, c, c + 12, c), fill="red", width=2)
    d.line((c, c - 12, c, c + 12), fill="red", width=2)
    sheet = Image.new("RGB", (OUT_SIZE + 256 + 108 + 40, OUT_SIZE), "white")
    sheet.paste(guide, (0, 0))
    sheet.paste(circle_preview(img, 256), (OUT_SIZE + 20, 0))
    sheet.paste(circle_preview(img, 108), (OUT_SIZE + 256 + 30, 0))
    return sheet


if __name__ == "__main__":
    for src in sys.argv[1:]:
        base = src.rsplit(".", 1)[0]
        crop, note = nose_centred_crop(src)
        print(f"{src}: {note}")
        if crop is not None:
            crop.save(f"{base}-centred.png")
            preview_sheet(crop).save(f"{base}-preview.png")
