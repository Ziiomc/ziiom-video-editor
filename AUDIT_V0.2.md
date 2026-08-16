# Auditoría funcional v0.2

## Problemas corregidos

- La línea de tiempo ahora permite buscar una posición haciendo clic dentro de un clip o sobre la regla temporal.
- Se añadió un cabezal de reproducción visible y sincronizado entre visor y timeline.
- Los controles de volumen, escala, rotación, opacidad, velocidad y recorte se aplican en vivo sin recargar el video completo.
- Se corrigió el layout para evitar que la timeline o el inspector se superpongan en ventanas de menor altura.
- Los clips ya no saltan a otra fila: V1 y A1 usan posicionamiento temporal horizontal estable.
- Se añadió reordenamiento de clips mediante arrastrar y soltar.
- Los botones + Video y + Audio ahora importan el tipo de archivo correspondiente.
- Se añadió autoguardado local y recuperación de la última sesión.
- Se añadieron atajos de teclado para reproducir, mover el cursor, dividir, eliminar, guardar, deshacer y rehacer.
- Se reforzó la seguridad de Electron activando sandbox y webSecurity, manteniendo contextIsolation y nodeIntegration desactivado.

## Exportación

La exportación deja de procesar solo el clip seleccionado. La v0.2 renderiza la secuencia completa de V1, concatena clips, mezcla A1 y aplica recorte, velocidad, volumen, escala, rotación, opacidad y textos sobre el resultado final.

Salida actual: MP4 H.264 + AAC, 1280x720, 30 fps.

## Pruebas realizadas

- Validación sintáctica de renderer.js, main.js y preload.js con Node.
- Prueba automatizada de UI en Chromium con un harness de medios simulado.
- Verificación de búsqueda temporal al 75% de un clip: 4.50 s sobre un clip de 6 s.
- Verificación de ajustes en vivo: escala 150% y opacidad 40% reflejadas inmediatamente.
- Verificación de layout a 1364x677 sin superposición entre workspace y timeline.
- Verificación de dos clips en V1 conservando exactamente la misma coordenada vertical.
- Prueba end-to-end de exportación FFmpeg con dos clips, pista musical y texto. Resultado válido H.264/AAC 1280x720.
