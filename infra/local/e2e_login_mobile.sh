#!/usr/bin/env bash
# Login móvel no emulador (adb). Rodar na raiz do repo, com adb reverse 8180/8081 e ENGENHEIRO_SENHA_LOCAL em ~/.config/engenheiro/senha-local.
# uso: e2e_login_mobile.sh <pacote> <usuario> <nome-captura>
A=~/Android/Sdk/platform-tools/adb
$A shell am force-stop com.android.chrome; $A shell am force-stop "$1"
$A shell monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
$A shell input tap 540 1438; sleep 9
$A shell input tap 540 1076; sleep 1; $A shell input text "$2"; sleep 1; $A shell input keyevent 61; sleep 1
$A shell input text "$(cat ~/.config/engenheiro/senha-local)"; sleep 1; $A shell input keyevent 66; sleep 15
$A shell dumpsys activity activities | grep -m1 topResumedActivity
$A exec-out screencap -p > docs/evidence/telas-mobile/$3.png
