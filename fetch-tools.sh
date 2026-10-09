#!/bin/bash
# Скачивает минимум для сборки без Android SDK: android.jar (API 29) и dx.
set -e
cd "$(dirname "$0")"
mkdir -p third_party && cd third_party
tmp=$(mktemp -d)
curl -sSL -o "$tmp/d2j.zip" https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip
unzip -q -o -j "$tmp/d2j.zip" 'dex-tools-v2.4/lib/dx-30.0.2.jar' -d .
mv dx-30.0.2.jar dx.jar
git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms "$tmp/ap"
git -C "$tmp/ap" sparse-checkout set android-29
cp "$tmp/ap/android-29/android.jar" .
rm -rf "$tmp"
echo "tools ready"
