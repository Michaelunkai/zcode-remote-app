#!/data/data/com.termux/files/usr/bin/bash
# Deploys the agent harness into Termux on the phone (run INSIDE Termux):
#   bash deploy-to-termux.sh
# Copies this directory to ~/ondevice/agent-harness and smoke-checks imports.
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/ondevice/agent-harness"

mkdir -p "$DEST"
cp "$SRC/agent.py" "$SRC/config.example.json" "$SRC/README.md" "$DEST/"
if [ -f "$SRC/config.json" ]; then
    cp "$SRC/config.json" "$DEST/"
fi

cd "$DEST"
python -c "import ast,sys; ast.parse(open('agent.py').read()); print('HARNESS_DEPLOYED_OK syntax-ok')"
echo "Next: cp config.example.json config.json  # then paste the panel token"
