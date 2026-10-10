# Dazz Clone (Android)

Retro-Film-Kamera für Android im Stil von Dazz Cam: Live-Vorschau mit Filmlook,
10 "Kameras", Datumsstempel, Instant-Rahmen, Timer, Blitz, Import aus der Galerie.

## Installieren

Auf dem Handy die neueste Version unter **Releases** öffnen
(https://github.com/ENjxzlt/Dazz/releases/latest), `DazzClone.apk` antippen,
Installation aus unbekannten Quellen erlauben.

Neue Version veröffentlichen: Tag pushen, z. B. `git tag v1.1 && git push origin v1.1`.

Das APK ist mit dem Debug-Key signiert (nicht für den Play Store geeignet).

## Selbst bauen

```bash
./gradlew assembleRelease   # braucht JDK 17 + Android SDK (compileSdk 35)
```

## Architektur

| Datei | Aufgabe |
|---|---|
| `Cameras.kt` | Presets – jeder Wert ist ein Shader-Uniform |
| `FilmShader.kt` | Ein GLSL-Pass: Farbe, Split-Toning, Fade, Halation, Chroma, Blur, Light Leak, Vignette, Korn, VHS, Pixel |
| `PreviewRenderer.kt` | CameraX → SurfaceTexture (OES) → Shader → GLSurfaceView |
| `StillProcessor.kt` | Gleicher Shader offscreen (EGL + FBO) auf das Vollauflösungsfoto |
| `PhotoStore.kt` | Datumsstempel, Instant-Rahmen, Speichern via MediaStore (`Pictures/DazzClone`) |
| `MainActivity.kt` | UI, CameraX-Binding, Auslöser, Timer, Blitz, Import |

Vorschau und Foto nutzen denselben Shader – was du siehst, wird gespeichert
(Korn/Light Leak werden pro Foto neu gewürfelt).

Mindestversion: Android 10 (API 29).
