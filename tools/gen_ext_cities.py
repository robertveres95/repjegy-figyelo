#!/usr/bin/env python3
"""A Chrome-bővítmény városnév-táblájának (chrome-extension/cities.js) előállítása az app adataiból:
az Airports.kt magyar/német nevei és városcsoportjai + az airports.tsv angol városnevei.
Futtatás a tároló gyökeréből: python3 tools/gen_ext_cities.py"""
import json, re, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
kt = (root / "shared/src/main/kotlin/hu/repjegy/figyelo/Airports.kt").read_text(encoding="utf-8")
def block(name):
    m = re.search(r"val %s = mapOf\((.*?)\n    \)" % name, kt, re.S)
    return dict(re.findall(r'"([^"]+)" to "([^"]+)"', m.group(1)))
hu, de = block("hungarianNames"), block("germanNames")
gen, gde = block("groupEnglishNames"), block("groupGermanNames")
fix = block("englishCityFixes")
groups = re.findall(r'Triple\("([^"]+)", "[A-Z]{2}", "([A-Z,]+)"\)', kt)
en = {}
for line in (root / "app/src/main/assets/airports.tsv").read_text(encoding="utf-8").splitlines():
    p = line.split("\t")
    if len(p) >= 5:
        raw = p[2].split(",")[0].split(" (")[0].split("(")[0].strip()
        en[p[0]] = fix.get(p[0]) or raw or p[1]
out = {}
for code in sorted(set(hu) | set(de) | set(fix)):
    if code not in en:
        continue  # az app listájában sincs (nem keresett reptér)
    e = en[code]
    out[code] = [hu.get(code, e), e, de.get(code, e)]
for hucity, codes in groups:
    e = gen.get(hucity, hucity)
    out[codes] = [hucity, e, gde.get(hucity, e)]
js = ("'use strict';\n// Városnevek a felület nyelvén [magyar, angol, német] – generálva: tools/gen_ext_cities.py\n"
      "const REFI_CITIES = " + json.dumps(out, ensure_ascii=False, separators=(",", ":")) + ";\n"
      "/** A város neve a böngésző nyelvén (magyar / német / minden más: angol); ismeretlen kódnál a tárolt név. */\n"
      "function refiCity(codes, label) {\n"
      "  const e = REFI_CITIES[String(codes || '').toUpperCase()];\n"
      "  if (!e) return label || codes || '';\n"
      "  const l = (typeof REFI_LANG !== 'undefined' ? REFI_LANG : (typeof navigator !== 'undefined' && navigator.language || '')).toLowerCase();\n"
      "  return l.startsWith('hu') ? e[0] : l.startsWith('de') ? e[2] : e[1];\n"
      "}\n")
(root / "chrome-extension/cities.js").write_text(js, encoding="utf-8")
print(len(out), "név")
