#!/usr/bin/env bash
# Örnek cümle setini ayrıştırıcıdan geçirir (kullanıcı "test et" deyince). Android gerekmez.
set -euo pipefail
cd "$(dirname "$0")/.."
O="$(mktemp -d)"
javac -nowarn -encoding UTF-8 -d "$O" android/src/app/notdefteri/Parser.java tests/ParserRun.java
java -cp "$O" app.notdefteri.ParserRun tests/ornekler.tsv
