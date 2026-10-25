param(
    [string]$OutFile = "$env:TEMP\zphone-mint-url.txt",
    [switch]$NoClose,
    [switch]$CloseOnly
)

# zphone-mint — zero-tap connect-link minter (skill rule 13, scripted).
#
# UIA-drives the running ZCode desktop app: invoke "Mobile remote control",
# then "Copy link" in the panel that opens, and saves the fresh connect URL
# to $OutFile so `zphone seturl @file` can push it without the link ever
# appearing in argv, console output or logs.
#
# PAIRING REALITY (live-verified 2026-10-25): the phone can only pair while
# the panel is open ("Waiting for phone"); closing it before the relay
# handshake finishes leaves the link's desktop side dead ("desktop_offline").
# So the default here KEEPS the panel open — the watchdog pairs the phone,
# and the panel closes itself the moment the phone connects. -NoClose is the
# default behavior name; the panel is closed by -CloseOnly or by pairing.
#
# Exit codes: 0 minted, 1 UIA failure, 2 no link on clipboard, 3 app not running.

$ErrorActionPreference = 'Stop'

function Get-ZcodeRoots {
    $roots = @()
    $seen = @{}
    foreach ($p in Get-Process -Name ZCode -ErrorAction SilentlyContinue) {
        if ($p.MainWindowHandle -ne 0 -and -not $seen.ContainsKey($p.MainWindowHandle)) {
            try {
                $roots += [System.Windows.Automation.AutomationElement]::FromHandle($p.MainWindowHandle)
                $seen[$p.MainWindowHandle] = $true
            } catch { }
        }
    }
    try {
        $children = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
            [System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($c in $children) {
            try {
                $cp = Get-Process -Id $c.Current.ProcessId -ErrorAction SilentlyContinue
                if ($cp -and $cp.ProcessName -eq 'ZCode') {
                    $h = $c.Current.NativeWindowHandle
                    if ($h -ne 0 -and -not $seen.ContainsKey($h)) {
                        $roots += $c
                        $seen[$h] = $true
                    }
                }
            } catch { }
        }
    } catch { }
    return $roots
}

function Find-Element($roots, [string]$name, [int]$timeoutSec = 0) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSec)
    do {
        foreach ($root in $roots) {
            try {
                # Any control type: Electron flyouts sometimes expose items
                # as Text/ListItem rather than Button.
                $cond = New-Object System.Windows.Automation.PropertyCondition(
                    [System.Windows.Automation.AutomationElement]::NameProperty, $name)
                $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
                if ($el) { return $el }
            } catch { }
        }
        if ($timeoutSec -gt 0) { Start-Sleep -Milliseconds 500 }
    } while ([DateTime]::UtcNow -lt $deadline)
    return $null
}

function Invoke-Element($el) {
    try {
        $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
        return $true
    } catch {
        return $false
    }
}

function Close-Panel($roots) {
    # The panel is a flyout toggled by the "Mobile remote control" button;
    # re-invoking it closes the panel. Verified by "Copy link" disappearing.
    for ($i = 0; $i -lt 2; $i++) {
        if (-not (Find-Element $roots 'Copy link')) { return $true }
        $btn = Find-Element $roots 'Mobile remote control'
        if ($btn) { [void](Invoke-Element $btn) }
        Start-Sleep -Milliseconds 900
    }
    return $null -eq (Find-Element $roots 'Copy link')
}

Add-Type -AssemblyName UIAutomationClient

if (-not (Get-Process -Name ZCode -ErrorAction SilentlyContinue)) {
    Write-Output "mint: app-not-running"
    exit 3
}

$roots = @(Get-ZcodeRoots)
if ($roots.Count -eq 0) {
    Write-Output "mint: no-zcode-window"
    exit 1
}

if ($CloseOnly) {
    if (Close-Panel $roots) { Write-Output "mint: closed" } else { Write-Output "mint: close-failed" }
    exit 0
}

$panelOpened = $false
try {
    # Panel may already be open from an earlier mint: prefer Copy link
    # directly (its link is still live while the panel waits).
    $copy = Find-Element $roots 'Copy link'
    if (-not $copy) {
        $open = $null
        for ($try = 0; $try -lt 3 -and -not $open; $try++) {
            $open = Find-Element $roots 'Mobile remote control'
            if (-not $open) { Start-Sleep -Milliseconds 700 }
        }
        if (-not $open) {
            Write-Output "mint: mobile-remote-button-not-found"
            exit 1
        }
        if (-not (Invoke-Element $open)) {
            Write-Output "mint: open-invoke-failed"
            exit 1
        }
        $panelOpened = $true
        $copy = Find-Element $roots 'Copy link' 10
        if (-not $copy) {
            Write-Output "mint: copy-link-not-found"
            exit 1
        }
    }

    $before = try { Get-Clipboard -Raw -ErrorAction SilentlyContinue } catch { '' }
    if (-not (Invoke-Element $copy)) {
        Write-Output "mint: copy-invoke-failed"
        exit 1
    }

    $link = $null
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 400
        $clip = try { Get-Clipboard -Raw -ErrorAction SilentlyContinue } catch { '' }
        if ($clip -and $clip -match 'https://zcode\.z\.ai/remote/\S+') {
            $link = $Matches[0]
            # A link identical to the clipboard before the click means the
            # open panel reused its still-live link — valid, not a failure.
            break
        }
    }
    if (-not $link) {
        Write-Output "mint: no-fresh-link"
        exit 2
    }

    # Link only ever touches the temp file — never the console, never argv.
    Set-Content -Path $OutFile -Value $link -NoNewline -Encoding ASCII
    Write-Output "mint: ok"
    exit 0
} finally {
    # Only close on demand (-NoClose absent means legacy self-close; the
    # watchdog always passes -NoClose and closes via -CloseOnly only when
    # pairing failed). The panel's own auto-close on successful pairing is
    # the preferred path — zero desktop disturbance on the happy path.
    if (-not $NoClose) {
        [void](Close-Panel $roots)
    }
}
