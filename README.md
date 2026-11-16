# Ziiom Video Studio — NVIDIA Cosmos3 Nano

Aplicación de escritorio para Windows construida con **Electron**. Combina un nuevo **estudio de creación de escenas con IA** y el **editor de video clásico de Ziiom** en la misma aplicación.

## Cosmos Studio

- **Texto → video** e **Imagen → video** con carga de referencia JPG/PNG/WebP.
- Prompt cinematográfico de hasta 4000 caracteres.
- Formatos **16:9, 9:16, 1:1, 4:3 y 3:4**.
- Resoluciones **256p, 480p o 720p**; 480p recomendado para pruebas.
- Duración de 1–8 s, 24 FPS, semilla opcional y 25/35/50 pasos.
- Prueba de enrutamiento API **sin ejecutar una generación**.
- Indicadores de progreso reales por etapas; no se inventan porcentajes.
- Guardado automático de MP4 y metadatos en la carpeta **Videos/Ziiom Cosmos Studio**.
- Biblioteca de escenas, reproducción y botón **Añadir al editor**.
- Acceso al Playground oficial de NVIDIA.

**Atención:** NVIDIA documenta la ruta `https://ai.api.nvidia.com/v1/cosmos/nvidia/cosmos3-nano`, pero diferentes cuentas están recibiendo **HTTP 404**. El editor ofrece un diagnóstico explícito cuando esto ocurre; **no puede habilitar el endpoint ni garantiza generaciones gratuitas**. Si la ruta falla, abre el Playground con el botón incluido. La conexión API en producción depende de los permisos y disponibilidad que NVIDIA otorgue a tu cuenta.

Fuente: [NVIDIA Cosmos3 Nano Model Card](https://build.nvidia.com/nvidia/cosmos3-nano/modelcard).

## Inicio en Windows

Requisitos: **Node.js 22** y conexión a Internet.

```powershell
git clone https://github.com/Ziiomc/ziiom-video-editor.git
cd ziiom-video-editor
npm install
npm start
```

La aplicación abrirá en **Escenas IA**, con la opción de regresar al **Editor de video** desde la barra superior.

### Conectar NVIDIA

1. Abre [NVIDIA Build / Cosmos3 Nano](https://build.nvidia.com/nvidia/cosmos3-nano) y consigue tu NVIDIA API Key.
2. En **Motor NVIDIA**, pega la clave y pulsa **Conectar**.
3. Pulsa **Comprobar ruta API**. Esta prueba envía un cuerpo inválido `{}` para identificar si existe el enrutamiento, **sin generar un video**. No confirma permisos de inferencia.
4. Si la ruta responde, escribe tu prompt y genera una escena corta (480p, 1 segundo) para validar la generación.
5. Si recibes **404**, no sigas repitiendo llamadas: es un problema de ruta o acceso que debe resolver NVIDIA.

Por seguridad, la clave se mantiene **solo en la memoria del proceso Electron**. Se borra al cerrar la aplicación y no se copia a proyectos, código, archivos MP4 o metadatos. También admite la variable de entorno local `NVIDIA_API_KEY`, si prefieres configurarla tú mismo. **Nunca publiques esa clave en GitHub ni envíes capturas donde aparezca**.

> El servidor de NVIDIA devuelve MP4 con pista VP9. Si un reproductor no admite VP9 en MP4, utiliza VLC o conviértelo antes de editar; el MP4 original queda guardado.

## Editor existente

Se conservan sus funciones: biblioteca de medios, línea de tiempo de video/audio, importar medios, recortar, dividir, velocidad, volumen, escala, rotación, opacidad, textos, guardar proyectos y exportación mediante FFmpeg.

Las escenas generadas se incorporan con **Añadir al editor**, sin borrar ni modificar proyectos existentes.

## Pruebas e instalador

```powershell
npm run check
npm test
npm run dist
```

El instalador NSIS sin firma se crea en `dist/`. También existe el flujo [Cosmos Studio · Windows](.github/workflows/cosmos-studio-windows.yml) en GitHub Actions, que comprueba sintaxis, pruebas del contrato NVIDIA y construye un instalador Windows descargable como artefacto si la compilación termina correctamente.

**Nota de seguridad:** el instalador no está firmado digitalmente; Windows puede advertirlo. Antes de instalar revisa el origen y el hash del archivo.

## Licencia

MIT.
