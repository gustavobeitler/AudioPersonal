# Hoja de ruta del Reproductor de música Android

## Principios permanentes

- Reproducir música local de forma inmediata: abrir, elegir una canción o pulsar Play y escuchar.
- Sin publicidad, esperas, pantallas engañosas ni interrupciones promocionales.
- Sin temporizador, apagado automático ni cambio de modo por horario.
- Interfaz intuitiva: las funciones avanzadas nunca deben impedir la reproducción básica.
- Pausa obligatoria si se desconectan auriculares Bluetooth, cableados o USB.
- Compilaciones limpias: retirar código, recursos y artefactos que hayan quedado obsoletos.
- Mantener compatibilidad de actualización mediante la firma beta estable.

## Beta 0.7 — Estabilidad fundamental

1. Corregir definitivamente Play, Pausa, Anterior y Siguiente al abrir, cerrar y volver a abrir la aplicación.
2. Mantener una conexión directa entre la interfaz y el servicio de reproducción.
3. Restaurar cola, pista, posición y estado cuando Android recree el servicio.
4. Evitar órdenes duplicadas durante Cargando.
5. Volumen de la aplicación en tiempo real, sin cortes ni reconstrucción del procesamiento.
6. Botones físicos de volumen con repetición controlada.
7. Pruebas automáticas de la máquina de estados y compilación limpia.

## Beta 0.8 — Biblioteca y cola

1. Cola visible y predecible.
2. Reproducir siguiente, mover y quitar pistas.
3. Exploración real por carpetas.
4. Limpieza de archivos eliminados o inaccesibles.
5. Exclusión de carpetas y filtro de audios demasiado cortos.
6. Mejor identificación de canción, artista, álbum y carátula.

## Beta 0.9 — Continuidad y nivelación

1. Reproducción sin pausas cuando el formato lo permita.
2. ReplayGain para normalizar diferencias de volumen entre pistas.
3. Revisión del motor de audio y posible migración completa a Media3.
4. Mejora de Sonido FM sin comprometer estabilidad ni volumen.
5. Guardado de volumen y límite nocturno por perfil de salida.

## Beta 0.10 — Perfiles de sonido

1. Perfiles múltiples de auriculares elegidos manualmente por marca y modelo.
2. Búsqueda automática con Wi-Fi y confirmación al usar datos móviles.
3. Perfiles verificados, estimados y estándar claramente diferenciados.
4. Perfil seguro para parlantes del teléfono.
5. Lista personal vacía en la versión pública; Sunvito S20 solo durante las pruebas personales.

## Beta 0.11 — Interfaz y accesibilidad

1. Terminación visual de todas las pantallas.
2. Controles claros para una sola mano.
3. Tamaños de texto y contraste adaptables.
4. Indicaciones de carga, error y salida de audio sin mensajes técnicos.
5. Revisión en distintos tamaños de pantalla y versiones de Android.

## Beta 0.12 — Preparación pública

1. Pruebas prolongadas con pantalla apagada y reproducción nocturna.
2. Consumo de batería y memoria.
3. Privacidad y explicación del uso de internet para perfiles.
4. Importación y exportación robusta de listas.
5. Eliminación de perfiles personales y datos de prueba.
6. Revisión de firma, nombre definitivo, icono y paquete de distribución.

## Versión 1.0

- Reproductor gratuito, estable y sin publicidad.
- Funciones básicas disponibles sin configuración previa.
- Perfiles personales agregados por cada usuario.
- Donación opcional mediante PayPal, separada de la reproducción y sin insistencia.
- Manual mínimo porque el uso principal debe entenderse directamente desde la interfaz.
