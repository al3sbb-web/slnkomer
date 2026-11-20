# Slnkomer (Android)

Ukazuje, o koľko sa orientácia telefónu (plochy panela) odchyľuje od aktuálnej polohy slnka
pre GPS polohu a zvolený čas.

## Ako získať APK cez GitHub (bez inštalácie čohokoľvek)
1. Na github.com si vytvor nový repozitár, napríklad `slnkomer` (môže byť súkromný).
2. Na stránke repozitára klikni na „uploading an existing file“ a pretiahni tam **obsah** tohto priečinka
   vrátane skrytého priečinka `.github` (v prehliadači ho pretiahni ako priečinok).
3. Klikni na „Commit changes“. Zostavenie sa spustí automaticky, sledovať ho môžeš v záložke **Actions**.
4. Po 3 až 5 minútach otvor dokončený beh a dole v časti **Artifacts** stiahni `Slnkomer-APK`.
   Je to zip so súborom `Slnkomer.apk`.
5. APK pošli do telefónu a nainštaluj ho. Android sa opýta na povolenie inštalácie z neznámeho zdroja.

## Ako zostaviť APK v Android Studiu
Otvor priečinok (File → Open), počkaj na synchronizáciu a zvoľ Build → Build App Bundle(s) / APK(s) → Build APK(s).

## Technické údaje
- Kotlin, bez ďalších knižníc; Android 8.0 a novší (minSdk 26)
- Orientácia: senzor TYPE_ROTATION_VECTOR (gyroskop, akcelerometer a magnetometer spolu)
- Magnetická deklinácia sa počíta automaticky z polohy (GeomagneticField, model WMM)
- Poloha slnka: algoritmus NOAA s refrakciou (presnosť cca 0,05°)
