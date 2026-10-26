# HomeMoniTor

HomeMoniTor ist eine native Android-App zur diskreten Überwachung und zum sicheren Fernzugriff auf Kamerabilder. Die App ermöglicht es, das Android-Gerät als mobile Kamera zu nutzen und den Stream direkt über das **Tor-Netzwerk** als **Onion Service** bereitzustellen.

## Features

*   **Kamera-Streaming:** Nutzt moderne [CameraX](https://developer.android.com/training/cameraview/camerax) APIs für stabile Bildaufnahmen (Front- und Rückkamera).
*   **Tor-Integration:** Exponiert einen lokalen Webserver über das Tor-Netzwerk (Hidden Service), um den Zugriff via `.onion`-Adresse zu ermöglichen.
*   **Orbot-Unterstützung:** Versucht automatisch, einen ephemeren Onion-Service über den Orbot-Control-Port zu registrieren.
*   **QR-Code Management:** Generiert QR-Codes für die Onion-Adresse zum einfachen Verbindungsaufbau und enthält einen Scanner zum Hinzufügen weiterer Onion-Endpunkte.
*   **Lokale Galerie:** Speichert Aufnahmen direkt in den privaten Bilder-Ordner des Geräts.

## Tech-Stack

*   **Sprache:** Kotlin
*   **Plattform:** Android (Min SDK 24, Target SDK 34)
*   **Kamera:** Android CameraX (1.4.0-alpha04)
*   **Netzwerk:** OkHttp 4.12.0 (für lokale SOCKS-Proxy-Requests)
*   **QR-Code:** ZXing (3.5.3)
*   **Architektur:** ViewBinding, Singleton-Pattern für Hardware-Services.

## Voraussetzungen

Für die volle Funktionalität im Tor-Netzwerk ist **[Orbot](https://orbot.app/)** auf dem Gerät erforderlich. 

## Manuelle Einrichtung des Hidden Service

Falls die automatische Einrichtung über den Orbot-Control-Port (9051) fehlschlägt, kann der Dienst manuell in Orbot konfiguriert werden:

1.  Öffne Orbot.
2.  Gehe zu **Einstellungen** -> **Versteckte Dienste**.
3.  Erstelle einen neuen Dienst mit folgenden Daten:
    *   **Virtueller Port:** 8080
    *   **Ziel-Port:** 8080
    *   **Ziel-IP:** 127.0.0.1
4.  Starte Orbot neu. Die App verwendet nun diesen Tunnel.

## Lizenz

Dieses Projekt ist Open-Source-Software.

---
*Hinweis: Diese App ist ein experimentelles Tool und setzt den verantwortungsvollen Umgang mit Netzwerkprivatsphäre und Kameradaten voraus.*
