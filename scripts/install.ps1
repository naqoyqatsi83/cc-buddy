# CC Buddy install/update/uninstall script (Windows PowerShell).
#
# Usage (run from inside a clone of this repo):
#   .\scripts\install.ps1              install (or update, if already installed)
#   .\scripts\install.ps1 update       same as above, explicit
#   .\scripts\install.ps1 uninstall    remove the daemon and the Claude Code plugin
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

param(
    [Parameter(Position = 0)]
    [ValidateSet("install", "update", "uninstall", "remove")]
    [string]$Command = "install"
)

$ErrorActionPreference = "Stop"

$MarketplaceName = "cc-buddy-marketplace"
$PluginName = "cc-buddy"
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Warn($msg) { Write-Host "!! $msg" -ForegroundColor Yellow }
function Die($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

function Require-Command($name, $hint) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Die "$name is required but not found on PATH. $hint"
    }
}

function Marketplace-Exists {
    $list = & claude plugin marketplace list 2>$null
    return ($list -join "`n") -match [regex]::Escape($MarketplaceName)
}

function Plugin-Installed {
    $list = & claude plugin list 2>$null
    return ($list -join "`n") -match [regex]::Escape($PluginName)
}

function Build-Daemon {
    Write-Step "Installing and building buddy-daemon..."
    Push-Location (Join-Path $RepoRoot "buddy-daemon")
    try {
        npm install --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { Die "npm install failed." }
        npm run build
        if ($LASTEXITCODE -ne 0) { Die "npm run build failed." }

        Write-Step "Linking the 'buddy' command onto your PATH (npm link)..."
        npm link
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "npm link failed (often needs an elevated/Administrator PowerShell on Windows)."
            Write-Warn "You can still run the daemon directly: node `"$RepoRoot\buddy-daemon\dist\cli.js`" start"
        }
    } finally {
        Pop-Location
    }
}

function Install-Plugin {
    Write-Step "Registering the cc-buddy plugin marketplace with Claude Code..."
    if (Marketplace-Exists) {
        claude plugin marketplace update $MarketplaceName
    } else {
        claude plugin marketplace add $RepoRoot
    }

    Write-Step "Installing the cc-buddy plugin (/buddy-scan, /buddy-pair, /buddy-list, /buddy-unpair)..."
    if (Plugin-Installed) {
        claude plugin update "$PluginName@$MarketplaceName" -y
    } else {
        claude plugin install "$PluginName@$MarketplaceName" -y -s user
    }
}

function Do-Install {
    Require-Command "git" "Install Git for Windows and re-run."
    Require-Command "node" "Install Node.js 18+ and re-run."
    Require-Command "npm" "Install Node.js 18+ (which includes npm) and re-run."
    Require-Command "claude" "Install Claude Code first: https://claude.com/claude-code"

    Build-Daemon
    Install-Plugin

    Write-Host ""
    Write-Step "Done. Restart any open Claude Code sessions to pick up the new commands."
    Write-Step "Then: run 'buddy start' (or 'buddy start --cwd C:\path\to\project') instead of 'claude' to enable pairing."
    Write-Step "On your phone: install the CC Buddy Android app, then run /buddy-pair <ip> <pin> from a 'buddy start' session."
}

function Do-Uninstall {
    Require-Command "claude" "Claude Code isn't on PATH -- nothing to uninstall there."

    if (Plugin-Installed) {
        Write-Step "Uninstalling the cc-buddy plugin..."
        claude plugin uninstall $PluginName -y
    } else {
        Write-Warn "cc-buddy plugin isn't installed, skipping."
    }

    if (Marketplace-Exists) {
        Write-Step "Removing the cc-buddy marketplace registration..."
        claude plugin marketplace remove $MarketplaceName
    }

    if (Get-Command npm -ErrorAction SilentlyContinue) {
        Write-Step "Unlinking the 'buddy' command..."
        Push-Location (Join-Path $RepoRoot "buddy-daemon")
        try { npm unlink -g 2>$null | Out-Null } catch { Write-Warn "Nothing to unlink (buddy wasn't linked globally)." }
        Pop-Location
    }

    Write-Host ""
    Write-Step "Uninstalled. This checkout at $RepoRoot is untouched -- delete it yourself if you no longer want it."
}

switch ($Command) {
    { $_ -in "install", "update" } { Do-Install }
    { $_ -in "uninstall", "remove" } { Do-Uninstall }
}
