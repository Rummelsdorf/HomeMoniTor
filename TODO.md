# TODOs – HomeMoniTor

Offene Punkte aus dem Code Review / Folgeaufgaben.

## ✅ Erledigt

- [x] SensorManager-Stop / Kamera-Cleanup (`MainActivity.onDestroy`)
- [x] `DELONION` beim Hidden-Service-Stopp (`OrbotHiddenService.stop`)
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
- [x] Onion-Schlüssel in `OrbotHiddenService` ist jetzt verschlüsselt gespeichert
- [x] Zeitlimit (`soTimeout`) auf lokalen Server-Socket gesetzt (5000 ms)
- [x] Einfache Rate-Begrenzung (max. 100 gleichzeitige Verbindungen) im `LocalWebServer`
- [x] OkHttp `dispatcher.executorService` im `onDestroy` heruntergefahren
- [x] Galerie-Downsampling in `loadGallery()` über `inJustDecodeBounds` + `inSampleSize`

## ⏳ Offene Punkte

- [x] `EncryptedSharedPreferences` in `OnionStore` und `OrbotHiddenService` (Build-Verified: `assembleDebug` OK)
- [x] `gradlew` / `gradle` Wrapper-Workaround durchgeführt (`JAVA_HOME` auf JDK 17 gesetzt)
- [ ] RSA-Schlüsselgröße auf 2048 Bits erhöhen (`KeyGenParameterSpec.setKeySize(2048)`) — unterstützt ab API 28
- [ ] RSA-Schlüsselgröße auf 2048 Bits erhöhen (`KeyGenParameterSpec.setKeySize(2048)`) — unterstützt ab API 28
- [ ] `EncryptedSharedPreferences` in `OrbotHiddenService` für `homemonitor_prefs` umziehen (aktuell nur Key verschlüsselt)
- [ ] Authentifizierung am Webserver / Hidden Service – laut Entscheidung des Nutzers **nicht erforderlich** (Hidden Service gilt als versteckt genug), aber Optional-Token (`?token=...`) ist implementiert und kann aktiviert werden.

## 🔒 Security / Privacy

- [ ] Authentifizierung am Webserver / Hidden Service – laut Entscheidung des Nutzers **nicht erforderlich** (Hidden Service gilt als versteckt genug).
- [x] Verschlüsselte Schlüssel-/Adressspeicherung (`EncryptedSharedPreferences`).
- [ ] Zusätzliche Auth-Option (statisches Token) ist implementiert, aber standardmäßig deaktiviert (`authToken = null`).
