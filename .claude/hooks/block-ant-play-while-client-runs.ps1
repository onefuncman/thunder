# Companion to block-ant-play-while-client-runs.sh: reads the PreToolUse
# payload on stdin, denies `ant play` when a hafen JVM is running.
$j = [Console]::In.ReadToEnd() | ConvertFrom-Json
$cmd = $j.tool_input.command
if (-not $cmd) { exit 0 }
if ($cmd -notmatch '(^|[;&|(]\s*)ant\b[^&|;]*\bplay\b') { exit 0 }
$running = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'hafen'
})
if ($running.Count -gt 0) {
    $out = @{
        hookSpecificOutput = @{
            hookEventName = 'PreToolUse'
            permissionDecision = 'deny'
            permissionDecisionReason = "A Haven client JVM (hafen) is running (PID $($running[0].ProcessId)). 'ant play' would rewrite the play/ jars under it, corrupting its cached zip offsets (ZipException / NoSuchResourceException on lazy loads). Ask the user to close the client first; 'ant bin' alone is safe."
        }
    }
    $out | ConvertTo-Json -Compress -Depth 3
}
exit 0
