# Dazz Clone (Android)

Retro-Film-Kamera für Android im Stil von Dazz Cam: Live-Vorschau mit Filmlook,
10 "Kameras", Datumsstempel, Instant-Rahmen, Timer, Blitz, Import aus der Galerie.

## Installieren

1. Repo → **Actions** → letzter Lauf von *Android APK* → Artifact `dazz-clone-apk` herunterladen.
2. ZIP entpacken, `app-release.apk` aufs Handy, Installation aus unbekannten Quellen erlauben.

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
