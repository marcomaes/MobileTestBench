#!/bin/sh
# Turn a screen recording into a small GIF for the README.
# Usage: scripts/make-gif.sh input.mov output.gif [width] [fps]
# Record with macOS: Cmd+Shift+5, "Record Selected Portion", select the IDE window.
set -eu

if [ "$#" -lt 2 ]; then
  echo "usage: $0 input.mov output.gif [width=1100] [fps=12]" >&2
  exit 2
fi
in="$1"; out="$2"; width="${3:-1100}"; fps="${4:-12}"

command -v ffmpeg >/dev/null 2>&1 || { echo "ffmpeg is required (brew install ffmpeg)" >&2; exit 1; }

# One palette for the whole clip keeps the file small and the colours stable.
ffmpeg -hide_banner -loglevel error -y -i "$in" \
  -vf "fps=$fps,scale=$width:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" \
  -loop 0 "$out"

bytes=$(wc -c < "$out" | tr -d ' ')
echo "$out: $((bytes / 1024)) KB"
if [ "$bytes" -gt 8388608 ]; then
  echo "More than 8 MB: record a shorter clip, or pass a smaller width and fps (for example 900 10)." >&2
fi
