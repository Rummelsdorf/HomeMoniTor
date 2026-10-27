# TODOs – HomeMoniTor

Offene Punkte aus dem Code Review / Folgeaufgaben.

## ✅ Erledigt

- [x] SensorManager-Stop / Kamera-Cleanup (`MainActivity.onDestroy`)
- [x] `DEL_ONION` beim Hidden-Service-Stopp (`OrbotHiddenService.stop`)
- [x] `android:usesCleartextTraffic` → `false`
- [x] Bitmap-Downsampling (`inSampleSize`) in der Galerie/Viewer
- [x] HTTP-Method-Check (405) im lokalen Webserver
- [x] QR-Scanner implementiert (CameraX + ZXing → `QrScannerActivity`, Onion-Adresse via `OnionStore` merken)
- [x] Stabile Onion-Adresse pro Installation (Variante C: persistenter Key + `ADD_ONION <key> <lifetime>`)

## ⏳ Offene Punkte

- [ ] Tor-/Orbot-Protokoll-Verifikation (`ADD_ONION` mit RSA-1024-Key, Lifetime) auf echtem Orbot-Gerät
- [ ] `cam`-Parameter im Snapshot-Viewer (`loadSnapshot`) tatsächlich verwenden
- [ ] Speichern in Galerie nur bei gültigem Onion-Eintrag durchführen
- [ ] Rate-Limits am lokalen HTTP-Server ergänzen

## 🔒 Security / Privacy

- [ ] Authentifizierung am Webserver / Hidden Service – laut Entscheidung des Nutzers **nicht erforderlich** (Hidden Service gilt als versteckt genug).
