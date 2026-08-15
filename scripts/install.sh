#!/usr/bin/env bash
# CC Buddy install/update/uninstall script.
#
# Usage (run from inside a clone of this repo):
#   ./scripts/install.sh            install (or update, if already installed)
#   ./scripts/install.sh update     same as above, explicit
#   ./scripts/install.sh uninstall  remove the daemon and the Claude Code plugin
#
# What this does:
#   1. Builds buddy-daemon and links its `buddy` command onto PATH via npm.
#   2. Registers this checkout as a Claude Code plugin marketplace and
#      installs the `cc-buddy` plugin from it (adds /buddy-scan, /buddy-pair,
#      /buddy-list, /buddy-unpair to every Claude Code session).
#
# Re-running with `update` after a `git pull` picks up any changes to either
# half without needing to uninstall first -- the marketplace source is this
# checkout's own path, so `claude plugin marketplace update` just re-reads it.
set -euo pipefail

MARKETPLACE_NAME="cc-buddy-marketplace"
PLUGIN_NAME="cc-buddy"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '\033[1;36m==>\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$1" >&2; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$1" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required but not found on PATH. $2"
}

marketplace_exists() {
  claude plugin marketplace list 2>/dev/null | grep -qF "$MARKETPLACE_NAME"
}

plugin_installed() {
  claude plugin list 2>/dev/null | grep -qF "$PLUGIN_NAME"
}

build_daemon() {
  log "Installing and building buddy-daemon..."
  (cd "$REPO_ROOT/buddy-daemon" && npm install --no-fund --no-audit && npm run build)

  log "Linking the 'buddy' command onto your PATH (npm link)..."
  if ! (cd "$REPO_ROOT/buddy-daemon" && npm link) 2>/tmp/cc-buddy-npm-link.err; then
    warn "npm link failed (often a permissions issue with your global npm prefix)."
    warn "$(cat /tmp/cc-buddy-npm-link.err)"
    warn "You can still run the daemon directly: node $REPO_ROOT/buddy-daemon/dist/cli.js start"
  fi
  rm -f /tmp/cc-buddy-npm-link.err
}

install_plugin() {
  log "Registering the cc-buddy plugin marketplace with Claude Code..."
  if marketplace_exists; then
    claude plugin marketplace update "$MARKETPLACE_NAME"
  else
    claude plugin marketplace add "$REPO_ROOT"
  fi

  log "Installing the cc-buddy plugin (/buddy-scan, /buddy-pair, /buddy-list, /buddy-unpair)..."
  if plugin_installed; then
    claude plugin update "$PLUGIN_NAME@$MARKETPLACE_NAME" -y
  else
    claude plugin install "$PLUGIN_NAME@$MARKETPLACE_NAME" -y -s user
  fi
}

do_install() {
  require git "Install git and re-run."
  require node "Install Node.js 18+ and re-run."
  require npm "Install Node.js 18+ (which includes npm) and re-run."
  require claude "Install Claude Code first: https://claude.com/claude-code"

  build_daemon
  install_plugin

  echo
  log "Done. Restart any open Claude Code sessions to pick up the new commands."
  log "Then: run 'buddy start' (or 'buddy start --cwd /path/to/project') instead of 'claude' to enable pairing."
  log "On your phone: install the CC Buddy Android app, then run /buddy-pair <ip> <pin> from a 'buddy start' session."
}

do_uninstall() {
  require claude "Claude Code isn't on PATH -- nothing to uninstall there."

  if plugin_installed; then
    log "Uninstalling the cc-buddy plugin..."
    claude plugin uninstall "$PLUGIN_NAME" -y || warn "Plugin uninstall reported an error; continuing."
  else
    warn "cc-buddy plugin isn't installed, skipping."
  fi

  if marketplace_exists; then
    log "Removing the cc-buddy marketplace registration..."
    claude plugin marketplace remove "$MARKETPLACE_NAME" || warn "Marketplace removal reported an error; continuing."
  fi

  if command -v npm >/dev/null 2>&1; then
    log "Unlinking the 'buddy' command..."
    (cd "$REPO_ROOT/buddy-daemon" && npm unlink -g >/dev/null 2>&1) || warn "Nothing to unlink (buddy wasn't linked globally)."
  fi

  echo
  log "Uninstalled. This checkout at $REPO_ROOT is untouched -- delete it yourself if you no longer want it."
}

case "${1:-install}" in
  install|update) do_install ;;
  uninstall|remove) do_uninstall ;;
  *) die "Unknown command '$1'. Use install, update, or uninstall." ;;
esac
