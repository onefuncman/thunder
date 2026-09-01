#!/bin/sh
# PreToolUse hook: block `ant play` while a Haven client JVM is running.
# Rewriting the play/ jars under a live client corrupts its cached zip
# central directory -> ZipException "invalid LOC header" on lazy class and
# resource loads. Cheap raw prefilter here; real work in the companion .ps1.
in=$(cat)
case "$in" in
    *ant*play*) ;;
    *) exit 0 ;;
esac
printf '%s' "$in" | powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$CLAUDE_PROJECT_DIR/.claude/hooks/block-ant-play-while-client-runs.ps1"
exit 0
