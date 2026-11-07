#!/bin/sh
# Runs the mirror server in the foreground (what launchd expects). Usage: run.sh /path/to/server-dir
# Environment: JAVA (default: java on PATH), HEAP (default 10G).
# The sync timer lives in the mod (config/nebulaemirror-sync.json), so this is the only scheduled job.
set -e
cd "$1"
exec "${JAVA:-java}" "-Xmx${HEAP:-10G}" -jar fabric-server-launch.jar nogui
