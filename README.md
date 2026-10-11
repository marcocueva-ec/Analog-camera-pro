# AnalogPro Cinema Camera v2.0 (Android Camera2 + VFX Ópticos Interactivos + 3D LUTs + RAW/DNG + 4K Video)

Aplicación fotográfica y de video profesional para Android con interfaz inspirada en monitores de cine, soporte para orientación **Vertical y Horizontal sin deformación (Zero Squeeze)**, emulación en tiempo real de películas **Fujifilm / Kodak / CineStill**, efectos ópticos interactivos que reaccionan a la posición real del sol y altas luces, control multi-lente y galería persistente con laboratorio de edición.

## Novedades de la Versión 2.0
- **Cero Deformación (No Squeeze) y Giro Instantáneo**: La matriz del `TextureView` calcula la relación de aspecto exacta del sensor en **16:9**, **4:3** y **OPEN GATE** sin estirar jamás la imagen y sin perder la señal de video al girar el teléfono entre vertical y horizontal.
- **Interfaz Dual Fiel al Diseño de Referencia**:
  - **HUD Cinematográfico**: Marcos guía en las esquinas, indicador de batería `[||||]`, conmutador `[AUTO / PRO]`, selector de formato `[16:9 / 4:3 / OPEN GATE]`, estado `● REC`, telemetría `4K / 30 fps / ISO` y retícula de enfoque central `[ + ]`.
  - **Dock Translúcido Adaptativo (`FILMS`, `VFX`, `PRO`, `LENS`)**: Se ubica horizontalmente abajo en modo vertical y verticalmente a la derecha en modo horizontal.
  - **Disparador Cromado + Botón de Grabación de Video**: Incluye botón dedicado junto al obturador para grabar video `.mp4` en la máxima resolución del sensor (`4K UHD / 1080p` a `35 Mbps`).
- **Menú `FILMS` con Imagen Representativa y Previsualización en Tiempo Real**: Cada película (Fujifilm Classic Chrome, Velvia 50, Superia, Acros, Kodak Portra 400, Gold 200, Ektar 100, Tri-X 400, CineStill 800T, QuickSnap y FunSaver) muestra su propia miniatura visual y aplica el look en tiempo real sobre el visor.
- **Menú `VFX` con Interacción Luminosa en Tiempo Real**:
  - **Flares Solares Interactivos**: Analiza en tiempo real dónde está el sol o la fuente de luz principal en la cámara y proyecta los fantasmas ópticos a lo largo del eje real del lente.
  - **Light Leaks Direccionales**: Las fugas de luz cálida reaccionan dinámicamente al ángulo y posición de la luz incidente.
  - **Light Leaks / Destellos Anamórficos**: Detecta las coordenadas exactas de los puntos de altas luces en la escena y genera trazos horizontales anamórficos (cian o ámbar) directamente sobre ellos.
  - **Halation, Grano Analógico, Marco Polaroid y Desenfoque Macro (Bokeh)**.
- **Menú `PRO` + Detección Inteligente de Baja Luz**:
  - Control manual de **ISO**, **Velocidad de Obturación**, **EV**, **Enfoque Manual Macro (Objetos cercanos)** y formatos **JPG / PNG / RAW (.DNG)**.
  - **Detector de Baja Luz**: Sugiere en pantalla activar el flash cuando la escena es oscura e incluye **Control de Intensidad del Flash (10% a 100%)**.
- **Menú `LENS`**: Detecta todas las cámaras físicas del teléfono (Ultra Gran Angular `0.5x`, Principal `1x`, Teleobjetivo `3x`, Frontal) para alternar entre ellas.
- **Galería Persistente (`MediaStore`) + Modo Editor**: Las fotos tomadas permanecen siempre guardadas en `DCIM/AnalogPro` incluso al cerrar la app, con miniatura en vivo en la esquina, zoom con gestos (`Pinch-to-Zoom`) y **Modo Editor** para importar cualquier foto de tu teléfono y aplicarle Films, LUTs, Flares interactivos, Anamórficos y Grano.
