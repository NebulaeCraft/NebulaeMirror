#!/usr/bin/env bash
# Fetch Mojang's 1.21.1 server+client jars (sha1-checked) and derive the generator's reference data:
#   work/mc/1.21.1/generated/reports/blocks.json   every block + state (server data generator)
#   work/mc/1.21.1/textures-block.txt              every vanilla block texture (client jar)
# Needs a Java 21+ runtime: JAVA=/path/to/java ops/mc/fetch_121.sh
set -euo pipefail
VERSION=1.21.1
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/work/mc/$VERSION"
JAVA="${JAVA:-java}"
mkdir -p "$OUT"
cd "$OUT"
URL=$(curl -fsS https://piston-meta.mojang.com/mc/game/version_manifest_v2.json \
  | python3 -c "import json,sys;print([v['url'] for v in json.load(sys.stdin)['versions'] if v['id']=='$VERSION'][0])")
curl -fsS "$URL" -o version.json
python3 -c "
import json;d=json.load(open('version.json'))['downloads']
for k in ('server','client'): print(k,d[k]['url'],d[k]['sha1'])" > urls.txt
while read -r kind url sha; do
  if ! { [ -f "$kind.jar" ] && echo "$sha  $kind.jar" | shasum -c - >/dev/null 2>&1; }; then
    curl -fsS -o "$kind.jar" "$url"
    echo "$sha  $kind.jar" | shasum -c -
  fi
done < urls.txt
"$JAVA" -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports --output generated > datagen.log 2>&1
# block/ and item/ textures: the 1.21 block atlas stitches both directories.
unzip -l client.jar 'assets/minecraft/textures/block/*' 'assets/minecraft/textures/item/*' | awk '{print $4}' | grep '\.png$' \
  | sed 's#assets/minecraft/textures/##; s#\.png$##' | sort > textures-block.txt
echo "blocks: $(python3 -c "import json;print(len(json.load(open('generated/reports/blocks.json'))))"), textures: $(wc -l < textures-block.txt)"
