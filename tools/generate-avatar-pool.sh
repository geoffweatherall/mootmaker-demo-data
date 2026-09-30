#!/usr/bin/env bash
# Regenerates the bundled avatar pool in impl/src/main/resources/avatars/.
#
# Authoring-time tooling only. Nothing in the build, the jar or the Lambda runs this or depends on
# Node: the PNGs it writes are committed, and they are what ships. Run it again only to change the
# pool - a different size, style or palette - and commit the result.
#
# The images are DiceBear's "Notionists Neutral" style. Licences in DiceBear are PER STYLE, not
# library-wide: this one's design is CC0 1.0 (public domain, by Zoish), which is why it was chosen.
# Several other styles are CC BY 4.0 and would oblige this project to show designer credit. If you
# change STYLE below, check https://www.dicebear.com/licenses/ first - the script refuses to run
# against a style whose own definition does not declare CC0 1.0.
#
# Output is deterministic: each file comes from a fixed seed, so re-running with the same versions
# reproduces the same pool byte for byte.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Must stay comfortably above any plausible TARGET_PEOPLE: demo-data never gives two people the
# same image, and fails loudly rather than repeating one. Keep in step with SampleData.AVATAR_POOL_SIZE.
POOL_SIZE=200
STYLE=notionists-neutral
DICEBEAR_VERSION=10.7.0
# At least the 256x256 the API normalises to, so nothing is ever scaled up.
SIZE=256
# Opaque on purpose. The API flattens every avatar to JPEG, which has no alpha channel.
BACKGROUNDS=(b6e3f4 c0aede d1d4f9 ffd5dc ffdfbf)

OUTPUT=impl/src/main/resources/avatars
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

(cd "$WORK" && npm init -y >/dev/null && npm install --silent "dicebear@${DICEBEAR_VERSION}")

# The licence the CLI itself carries for this style, read from the definition it renders from.
DEFINITION="$WORK/node_modules/@dicebear/styles/src/${STYLE}.json"
LICENCE="$(node -e 'console.log(require(process.argv[1]).meta.license.name)' "$DEFINITION")"
if [[ "$LICENCE" != "CC0 1.0" ]]; then
  echo "Refusing to generate: the '${STYLE}' design is licensed '${LICENCE}', not CC0 1.0." >&2
  exit 1
fi
echo "Style '${STYLE}' is ${LICENCE}: $(node -e 'console.log(require(process.argv[1]).meta.license.text)' "$DEFINITION")"

rm -f "$OUTPUT"/avatar-*.png
mkdir -p "$OUTPUT"
for i in $(seq 1 "$POOL_SIZE"); do
  n="$(printf '%03d' "$i")"
  (cd "$WORK" && npx dicebear "$STYLE" out --format png --size "$SIZE" \
    --seed "mootmaker-${n}" --backgroundColor "${BACKGROUNDS[@]}" >/dev/null)
  mv "$WORK/out/${STYLE}-0.png" "$OUTPUT/avatar-${n}.png"
done

echo "Wrote $(find "$OUTPUT" -name 'avatar-*.png' | wc -l) avatars to $OUTPUT"
distinct="$(sha256sum "$OUTPUT"/avatar-*.png | awk '{print $1}' | sort -u | wc -l)"
if [[ "$distinct" -ne "$POOL_SIZE" ]]; then
  echo "Only $distinct of $POOL_SIZE avatars are distinct. demo-data tells them apart by hash, so" >&2
  echo "every one must differ - change the seeds or the palette." >&2
  exit 1
fi
