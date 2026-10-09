#!/bin/bash
# Сборка APK без Android SDK: javac + dx + ручные AXML/ARSC + jarsigner
set -e
cd "$(dirname "$0")"
TOOLS=${TOOLS:-third_party}
[ -f "$TOOLS/android.jar" ] || ./fetch-tools.sh
AJAR=$TOOLS/android.jar
DX=$TOOLS/dx.jar
rm -rf build && mkdir -p build/classes build/apk/res/drawable-xxxhdpi-v4 build/apk/res/mipmap-xxxhdpi-v4
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath $AJAR -d build/classes src/ru/andrey/guitartuner/*.java 2>&1 | grep -v -E 'JAVA_TOOL|warning|Note:' || true
java -cp $DX com.android.dx.command.Main --dex --min-sdk-version=21 --output=build/apk/classes.dex build/classes 2>&1 | grep -v JAVA_TOOL || true
cp icon/bg.png build/apk/res/drawable-xxxhdpi-v4/ic_bg.png
cp icon/fg.png build/apk/res/drawable-xxxhdpi-v4/ic_fg.png
cp icon/legacy.png build/apk/res/mipmap-xxxhdpi-v4/ic_launcher.png
python3 tools/axml.py build/apk/AndroidManifest.xml
python3 tools/arsc.py build/apk
python3 - <<'P'
import zipfile, os
os.chdir('build')
with zipfile.ZipFile('MoyTuner.apk','w') as z:
    for root,_,files in os.walk('apk'):
        for f in sorted(files):
            p=os.path.join(root,f); arc=os.path.relpath(p,'apk')
            z.write(p, arc, zipfile.ZIP_STORED if arc=='resources.arsc' or arc.endswith('.png') else zipfile.ZIP_DEFLATED)
P
[ -f tuner.keystore ] || keytool -genkeypair -keystore tuner.keystore -storepass tuner123 -keypass tuner123 -alias tuner -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=Andrey, O=Guitar Tuner"
jarsigner -keystore tuner.keystore -storepass tuner123 -sigalg SHA256withRSA -digestalg SHA-256 build/MoyTuner.apk tuner 2>&1 | grep -v -E 'JAVA_TOOL|^$|Warning|self-signed|POSIX' || true
ls -la build/MoyTuner.apk
