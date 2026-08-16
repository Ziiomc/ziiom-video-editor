# Ziiom Video Editor

Editor de video de escritorio para Windows, pensado como una base simple, rápida y ampliable.

## Estado

MVP 0.1.

## Funciones incluidas

- Importar video, audio e imágenes.
- Biblioteca multimedia.
- Vista previa de video e imágenes.
- Línea de tiempo con pistas de video y audio.
- Arrastrar medios a la línea de tiempo.
- Seleccionar, duplicar y eliminar clips.
- Dividir video en la posición actual.
- Recortar inicio y final.
- Velocidad de reproducción de 0.25x a 4x.
- Volumen de clip.
- Escala, rotación y opacidad en vista previa.
- Texto superpuesto y posicionamiento con arrastre.
- Zoom de línea de tiempo.
- Deshacer / rehacer.
- Guardar y abrir proyectos JSON.
- Exportar el clip de video seleccionado a MP4/H.264 mediante FFmpeg.
- Preparado para empaquetarse como instalador de Windows con Electron Builder.

## Ejecutar en desarrollo

```bash
npm install
npm start
```

## Crear instalador para Windows

En Windows:

```bash
npm install
npm run dist
```

El instalador se generará dentro de `dist/`.

## Próximas etapas

1. Composición/exportación de toda la línea de tiempo.
2. Música de fondo y mezcla multipista.
3. Transiciones.
4. Filtros de color.
5. Formatos 16:9, 9:16, 1:1 y 4:5.
6. Subtítulos automáticos.
7. Eliminación de silencios.
8. IA para edición automática.

## Tecnología

- Electron
- HTML/CSS/JavaScript
- FFmpeg (`ffmpeg-static`)
- Electron Builder
