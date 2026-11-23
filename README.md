# Posición Teléfono

App Android mínima que **registra la posición del GPS cada 10 segundos** (hora, latitud, longitud, precisión y velocidad) y después **muestra en un mapa por dónde anduviste**. Está pensada para recorridos en bicicleta, donde la línea de tiempo de Google Maps es imprecisa. Lee el GPS directamente, sin mezclar wifi ni antenas.

- Probada para el **Moto G52** (Android 12/13). Funciona en cualquier Android 12 o más nuevo.
- **Cero dependencias**: la app usa solo el SDK de Android. El APK pesa unos 60 KB.
- Graba con la pantalla apagada mediante un servicio en primer plano, con una notificación fija que tiene un botón «Detener».

## Instalar

1. Bajá `posicion-telefono-X.Y.apk` desde **Releases** del repo.
2. En el teléfono, abrilo y permití «Instalar apps desconocidas» para el navegador o el gestor de archivos.
3. Al tocar **Iniciar registro** la primera vez, aceptá la ubicación **precisa** («solo mientras se usa la app» alcanza) y las notificaciones.
4. Tocá **Permitir** en el aviso de batería, así Android no corta el registro en el bolsillo.

## Usar

- **Iniciar registro** antes de salir y **Detener** al llegar. Cada vez se crea un recorrido nuevo.
- Tocá un recorrido para verlo en el mapa. La línea se pinta según la calidad del GPS: **azul** hasta 10 m, **naranja** hasta 25 m y **rojo** si es impreciso. Tocá un punto para ver la hora, la precisión y la velocidad.
- Mantené apretado un recorrido para **guardar el CSV y el GPX en `Descargas/PosicionTelefono`**. El GPX se abre en cualquier visor, por ejemplo gpx.studio o JOSM.

### Formato del CSV

```
time,lat,lon,accuracy_m,speed_kmh,unix_ms
2026-11-22 14:05:30,-34.6037345,-58.3815704,4.0,18.5,1795367130000
```

La hora es local y los decimales van con punto, sin importar el idioma del teléfono. Los campos vacíos indican que el GPS no informó ese dato.

## Privacidad y seguridad

- Los recorridos se guardan en el **almacenamiento privado de la app**, donde ninguna otra app puede leerlos. Están excluidos del backup en la nube y de la transferencia a otro teléfono (`data_extraction_rules.xml`). Solo salen del teléfono si los exportás.
- El registro **no usa internet**. Internet se usa únicamente para bajar los mosaicos de OpenStreetMap del mapa, siempre por HTTPS (el tráfico sin cifrar está deshabilitado). Se baja con un tope de tamaño por mosaico y se guarda en caché en disco.
- Ningún componente está exportado salvo el lanzador, los `PendingIntent` son inmutables y el nombre de archivo que recibe la pantalla del mapa se valida contra una lista blanca, lo que evita *path traversal*.

## Compilar y testear

Hace falta el JDK 17 y el Android SDK, que viene con Android Studio.

```
./gradlew testDebugUnitTest   # tests unitarios (JVM, sin emulador)
./gradlew lintDebug           # análisis estático de Android
./gradlew assembleRelease     # APK en app/build/outputs/apk/release/
```

GitHub Actions corre los tests y lint en cada push (`.github/workflows/ci.yml`).

El release se firma con la clave de `firma/keystore.properties`. Esa carpeta **no se sube a git**: guardala en un lugar seguro, porque sin ella no se pueden instalar actualizaciones encima de la versión anterior. Si no existe, el release se firma con la clave de debug.

## Estructura

| Archivo | Qué hace |
|---|---|
| `RecorderService` | Servicio en primer plano: GPS a 1 Hz y guarda 1 punto cada 10 s en el CSV, con flush inmediato |
| `Sampler` | Decide qué fix guardar según el reloj monotónico del propio fix |
| `TrackPoint`, `Track` | Formato CSV, lectura tolerante a líneas cortadas, distancia (haversine) y GPX |
| `TrackStore` | Archivos privados y exportación a Descargas (MediaStore) |
| `TrackMapView`, `TileCache`, `Mercator` | Mapa propio: mosaicos OSM, zoom con pellizco, color por precisión |
| `MainActivity`, `MapActivity` | Las dos pantallas |

Mapa © colaboradores de [OpenStreetMap](https://www.openstreetmap.org/copyright).
