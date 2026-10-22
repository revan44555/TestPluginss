#!/usr/bin/env bash
# NinjagoIzlesene/src/main/res altindaki KULLANILMAYAN sablon artiklarini siler.
# Eklenti kodu bu kaynaklara hic referans vermiyor (bos AndroidManifest,
# kaynaklari olmayan saf Kotlin eklentisi). Ikili boyutunu ve bakim
# yukunu azaltir.
set -euo pipefail
cd "$(dirname "$0")/NinjagoIzlesene/src/main/res"
rm -f drawable/ic_android_24dp.xml
rm -rf layout
rm -rf values-pl
# values/strings.xml icindeki sablon dizgileri de eklentinin kullanmadigi
# "hello_fragment" / "loaded_from_app_trans" anahtarlaridir; strings.xml'i
# tamamen silmek de guvenlidir ama bos birakmak icin icerigi temiz tutalim:
cat > values/strings.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Ninjagoİzlesene</string>
</resources>
XML
echo "Sablon artiklari temizlendi."
