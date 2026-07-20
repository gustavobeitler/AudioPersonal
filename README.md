# Audio Personal

Audio Personal es un reproductor de música local y controlador de volumen para Windows, diseñado para manejar una biblioteca musical propia mediante voz.

## Funciones principales

- Control vertical del volumen maestro de Windows.
- Vúmetro estéreo, mute y balance.
- Reproductor integrado con playlist, arrastre de archivos y ecualizador de diez bandas.
- Búsqueda de música por artista y título dentro de carpetas locales.
- Activación por la palabra «computadora».
- Órdenes de reproducción, volumen, mute y apertura o cierre del reproductor.
- Reconocimiento local y bilingüe mediante Vosk, sin enviar la voz a servicios externos.
- Tema claro u oscuro de acuerdo con Windows.
- Vista completa y compacta.
- Inicio automático con Windows.

## Privacidad

El catálogo musical, la configuración y los modelos de reconocimiento permanecen en la computadora del usuario. Audio Personal no incorpora telemetría ni sube grabaciones de voz. Los modelos de Vosk se descargan desde sus fuentes oficiales durante la configuración inicial.

## Requisitos

- Windows 10 u 11 de 64 bits.
- Microsoft .NET Framework 4.8.
- Micrófono configurado en Windows.

## Compilación

1. Instalar Visual Studio 2022 o las herramientas de compilación con .NET Framework 4.8.
2. Abrir `AudioPersonal.csproj`.
3. Compilar en configuración `Release` y plataforma `x64`.

También existe un flujo reproducible en GitHub Actions. Cada cambio compila la aplicación y ejecuta las pruebas automáticas de reconocimiento e interpretación de órdenes.

## Política de firma de código (Code signing policy)

Firma de código gratuita proporcionada por [SignPath.io](https://signpath.io/), certificado de [SignPath Foundation](https://signpath.org/).

- Responsable, mantenedor y revisor: [Gustavo Beitler](https://github.com/gustavobeitler).
- Aprobador de solicitudes de firma: [Gustavo Beitler](https://github.com/gustavobeitler).
- Las versiones destinadas a usuarios finales se publicarán únicamente después de incorporar una firma Authenticode válida al ejecutable.
- Los binarios firmados deben proceder exclusivamente de la compilación automática y pública de este repositorio.
- Audio Personal no transfiere información a otros sistemas conectados a una red salvo cuando el usuario solicita o autoriza expresamente una operación, como descargar los modelos de reconocimiento de voz desde sus fuentes oficiales.

## Licencia

Audio Personal se publica bajo GNU General Public License v3.0. Consulte `LICENSE` y `THIRD-PARTY-NOTICES.md`.
