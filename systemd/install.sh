#!/usr/bin/env bash
# Installs (or removes) a systemd user timer for one court case.
# The units are rendered from the *.in templates next to this script, using
# the folder this repository lives in and the clojure found on PATH.
set -euo pipefail

usage() {
  echo "Usage: $0 [--dry-run | --uninstall] <number>/<year>   e.g. $0 62325/2023" >&2
  exit 2
}

mode=install
case "${1:-}" in
  --dry-run)   mode=dry-run; shift ;;
  --uninstall) mode=uninstall; shift ;;
esac
[[ $# -eq 1 && "$1" =~ ^([0-9]+)/([0-9]{4})$ ]] || usage
case_id=$1
unit="court-checker-${BASH_REMATCH[1]}-${BASH_REMATCH[2]}"

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
app_dir=$(dirname "$here")
# keep units portable: use systemd's %h specifier for paths under $HOME
unit_app_dir=${app_dir/#"$HOME"\//%h/}
dest="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

if [[ $mode == uninstall ]]; then
  systemctl --user disable --now "$unit.timer" 2>/dev/null || true
  rm -f "$dest/$unit.service" "$dest/$unit.timer"
  systemctl --user daemon-reload
  echo "Removed $unit"
  exit 0
fi

clojure_bin=$(command -v clojure) || { echo "clojure not found on PATH" >&2; exit 1; }

render() {
  sed -e "s|@CASE_ID@|$case_id|g" \
      -e "s|@APP_DIR@|$unit_app_dir|g" \
      -e "s|@CLOJURE@|$clojure_bin|g" "$1"
}

if [[ $mode == dry-run ]]; then
  for ext in service timer; do
    echo "# $dest/$unit.$ext"
    render "$here/court-checker.$ext.in"
    echo
  done
  exit 0
fi

mkdir -p "$dest"
render "$here/court-checker.service.in" > "$dest/$unit.service"
render "$here/court-checker.timer.in"   > "$dest/$unit.timer"
systemctl --user daemon-reload
systemctl --user enable --now "$unit.timer"
echo "Installed $unit (code: $app_dir)"
systemctl --user list-timers "$unit.timer" --no-pager
