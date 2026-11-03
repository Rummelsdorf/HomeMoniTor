# TODOs – HomeMoniTor

Offene Punkte aus dem Code Review / Folgeaufgaben.

## ✅ Erledigt

- [x] SensorManager-Stop / Kamera-Cleanup (`MainActivity.onDestroy`)
- [x] `android:usesCleartextTraffic` → `false`
- [x] Bitmap-Downsampling (`inSampleSize`) in der Galerie/Viewer
- [x] HTTP-Method-Check (405) im lokalen Webserver
- [x] QR-Scanner implementiert (CameraX + ZXing → `QrScannerActivity`, Onion-Adresse via `OnionStore` merken)
- [x] Stabile Onion-Adresse pro Installation (Variante C: persistenter Key + `ADD_ONION <key> <lifetime>`)

## ✅ Architektur & Sicherheit (nach Review)

- [x] ViewModel-Einführung (`MainViewModel`) für `latestJpeg`- und Sensorstatus (surviviert Config-Changes)
- [x] Thread-sicherer Zugriff auf `latestJpeg` via dediziertem Callback und `MainViewModel`
- [x] Manueller JSON-Serializer durch Moshi ersetzt (`SensorManager.collectJson`)
- [x] `LocalWebServer` erhält OkHttp-Client mit SOCKS-Proxy (`127.0.0.1:9050`) für Remote-Fetch via Onion
- [x] `loadSnapshot()` fetchen nun tatsächlich vom Onion-Endpunkt, falls eine gültige Adresse eingegeben wurde
- [x] `cam`-Parameter (`front`/`back`) wird vom Viewer zur Remote-Anfrage übergeben
- [x] `EncryptedSharedPreferences` für `OnionStore` (AES-256-GCM) statt Klartext-`SharedPreferences`
- [x] Zeitlimit (`soTimeout`) auf lokalen Server-Socket gesetzt (5000 ms)
- [x] Einfache Rate-Begrenzung (max. 100 gleichzeitige Verbindungen) im `LocalWebServer`
- [x] OkHttp `dispatcher.executorService` im `onDestroy` heruntergefahren
- [x] Galerie-Downsampling in `loadGallery()` über `inJustDecodeBounds` + `inSampleSize`

## ⏳ Offene Punkte

- [x] `EncryptedSharedPreferences` in `OnionStore` (Build-Verified: `assembleDebug` OK)
- [x] `gradlew` / `gradle` Wrapper-Workaround durchgeführt (`JAVA_HOME` auf JDK 17 gesetzt)
- [x] `OrbotHiddenService` entfernt – Hidden Service wird ausschließlich über `OrbotOnionManager` (Orbot Intent-API) verwaltet; `ownOnionAddress` wird in `MainActivity` gehalten und `LocalWebServer` über einen Provider bereitgestellt
- [x] `OrbotOnionManager`: Hostname-Ermittlung über mehrere Extra-Keys robust gemacht (Orbot-Versionen liefern unterschiedliche Keys)
- [ ] Authentifizierung am Webserver / Hidden Service – laut Entscheidung des Nutzers **nicht erforderlich** (Hidden Service gilt als versteckt genug), aber Optional-Token (`?token=...`) ist implementiert und kann aktiviert werden.

## 🔒 Security / Privacy

- [ ] Authentifizierung am Webserver / Hidden Service – laut Entscheidung des Nutzers **nicht erforderlich** (Hidden Service gilt als versteckt genug).
- [x] Verschlüsselte Schlüssel-/Adressspeicherung (`EncryptedSharedPreferences`) in `OnionStore`.
- [ ] Zusätzliche Auth-Option (statisches Token) ist implementiert, aber standardmäßig deaktiviert (`authToken = null`).
- [x] `OrbotHiddenService` entfernt: keine Klartext-/RSA-Schlüsselspeicherung mehr, keine Control-Port-Authentifizierung.

--- Weitere Fehlerquellen (Code-Review 30.10.) ---

- [ ] `QrScannerActivity`: `CAMERA`-Permission nicht explizit angefordert → Crash auf echtem Gerät möglich
- [ ] `CameraManager.bindToLifecycle()`: `IllegalArgumentException` wenn CameraX Provider fehlt (Emulator ohne Hardware)
- [ ] `GalleryAdapter`: `OutOfMemoryError` bei großen Bitmaps nicht abgefangen
- [ ] `MainActivity`: `latestJpeg` Race Condition (Webserver-Thread + UI-Thread)
- [ ] `isOrbotAccessible()`: Hintergrund-Thread, aber `finish()` sofort → Dialog könnte nicht sichtbar sein
- [ ] `BuildConfig.BUILD_DATE`: Gradle-Cache kann altes Datum liefern
- [x] `OrbotHiddenService` entfernt (ersetzt durch `OrbotOnionManager` Intent-API)
