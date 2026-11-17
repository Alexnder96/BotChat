# BotChat

App Android que inicia sesión con **tu cuenta de Telegram** y muestra **un solo chat: el de tu bot**.
Escribes la consulta, y la respuesta aparece sola en cuanto el bot contesta. Incluye filtros para ocultar
palabras e imágenes de lo que responde el bot, protegidos con contraseña.

Hecha en Java sobre [TDLib](https://core.telegram.org/tdlib) (la librería oficial de Telegram), sin servidores
intermedios: la app habla directamente con Telegram.

## Características

- **Un solo chat**: solo se muestra la conversación con el bot que configures; todo lo demás se descarta.
- **Mensajes completos**: texto con formato, enlaces, `/comandos` tocables, fotos, documentos, videos, audios y notas de voz.
- **Botones del bot**: botones en línea bajo los mensajes y teclado de respuestas rápidas.
- **Mensajes editados**: el "Buscando…" del bot se reemplaza por el resultado.
- **Enviar con Enter** (o la flecha de enviar del teclado).
- **Filtros con contraseña** (menú ⋮ → Filtros):
  - *Palabras clave*: la palabra o frase se quita del mensaje y el resto se ve igual. Cuenta solo la palabra
    completa (`hoy` no se quita de `hoyo`) y funciona con frases (`solo por hoy`) aunque haya espacios
    dobles o saltos de línea. No distingue mayúsculas ni tildes.
  - *Imágenes por nombre*: oculta solo la foto o el archivo; el pie de foto, los botones y la hora se siguen viendo.
- **Seguridad**:
  - Base de datos de Telegram cifrada con una clave guardada en el Android Keystore.
  - Contraseña guardada solo como hash PBKDF2 con sal; bloqueo con espera creciente tras 4 intentos fallidos.
  - Bloqueo de capturas de pantalla (siempre en Filtros, Configuración y Login; opcional en el chat).
  - `allowBackup` desactivado.
- **Estable**: sobrevive a la rotación de pantalla, reintenta cargar el historial y reinicia TDLib si se cae.

## Requisitos

| Qué | Versión |
|---|---|
| Android Studio | Koala (2024.1) o más nuevo |
| Android Gradle Plugin | 8.9.2 |
| Gradle | 8.11.1 (lo descarga el wrapper) |
| Java | 11 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| ABIs incluidas de TDLib | `arm64-v8a`, `armeabi-v7a`, `x86_64` |

## Cómo compilarla

1. Clona el repositorio y ábrelo con Android Studio:
   ```bash
   git clone https://github.com/Alexnder96/BotChat.git
   ```
2. Espera a que Gradle sincronice (*File → Sync Project with Gradle Files*).
3. Conecta un teléfono (o usa un emulador) y pulsa **Run**, o genera el APK con
   *Build → Build Bundle(s) / APK(s) → Build APK(s)*.

El repositorio ya incluye `app/libs/tdlib.jar` y las librerías nativas en `app/src/main/jniLibs/`,
así que no hay que descargar nada de TDLib aparte.

## Primer uso (todo se configura dentro de la app)

No hay que tocar código ni poner claves en ningún archivo. Al abrir la app aparece **Configura tu bot**:

| Dato | De dónde sale |
|---|---|
| API ID | https://my.telegram.org → "API development tools" (es un número) |
| API Hash | La misma página |
| Usuario del bot | El usuario del bot, sin `@` (también acepta `t.me/...`) |
| Título | Opcional: mostrar un título arriba del chat (por defecto `CHAT BOT`) |

Después inicias sesión con tu número de Telegram (código y, si la tienes, la contraseña de dos pasos).
Cada persona usa su propio API ID, API Hash y bot. Para cambiarlos más tarde: **⋮ → Configuración**
(si cambias el API ID o el API Hash se cierra la sesión y hay que entrar de nuevo).

## Contraseña de Filtros y Configuración

**Filtros** y **Configuración** piden la misma contraseña. La primera vez te pide crearla (mínimo 4 caracteres).
Ambas pantallas se cierran solas al salir de la app.

**¿Olvidaste la contraseña?** En la ventana de contraseña toca **Olvidé mi contraseña → Restablecer el bot**.
Se borra todo (filtros, contraseña, configuración y sesión de Telegram) y la app queda como recién instalada.

## Estructura del proyecto

```
app/src/main/java/com/botchat/app/
├── BotApp.java              Arranque: carga configuración y filtros
├── data/                    Datos y conexión con Telegram (sin pantallas)
│   ├── Config.java            API ID, API Hash, bot, título y opción de capturas
│   ├── Telegram.java          Conexión con TDLib; descarta lo que no sea del chat del bot
│   ├── SecureStore.java       Clave de la base de datos dentro del Android Keystore
│   ├── DbKeyPolicy.java       Decide qué clave usar y cuándo migrar o borrar la base (lógica pura)
│   ├── MediaLoader.java       Descarga y caché de fotos y archivos
│   ├── MessageStore.java      Lista ordenada de mensajes (alta, edición, borrado)
│   └── TdFileProvider.java    Abrir con otra app los archivos del bot
├── filters/                 Todo lo de filtros (lógica pura, con pruebas)
│   ├── Filters.java           Fachada: listas, contraseña y bloqueo por intentos
│   ├── FilterRules.java       Reglas inmutables: qué texto se quita y qué se oculta
│   ├── TextRules.java         Normalización y búsqueda de palabras/frases completas
│   ├── PasswordHasher.java    PBKDF2-HMAC-SHA256 con sal
│   └── Lockout.java           Espera creciente tras intentos fallidos
└── ui/                      Pantallas
    ├── ChatActivity.java      El chat
    ├── MessageAdapter.java    Cómo se dibuja cada mensaje y qué se oculta
    ├── FilterGate.java        Ventanas de contraseña
    ├── FiltersActivity.java   Edición de las listas de filtros
    ├── SettingsActivity.java  Configuración
    ├── LoginActivity.java     Número, código y contraseña de dos pasos
    └── TextFormatter.java     Negritas, enlaces y /comandos tocables
```

Las carpetas `data/` y `filters/` tienen las partes que no dependen de la interfaz, para poder probarlas
sin un teléfono.

## Pruebas

Hay pruebas unitarias de las reglas de texto, los filtros, el hash de contraseña, el bloqueo por intentos,
la política de clave de la base de datos y la lista de mensajes:

```bash
./gradlew test
```

(En Windows: `gradlew.bat test`.)

## Seguridad: qué se guarda y dónde

- Tu API ID, API Hash, usuario del bot, filtros y hash de la contraseña se guardan **solo en el teléfono**,
  en el almacenamiento privado de la app. No hay nada de eso en el repositorio.
- La base de datos de TDLib (mensajes y sesión) se cifra con una clave aleatoria de 32 bytes protegida por el
  Android Keystore. Si Android invalida esa clave (por ejemplo, al cambiar el bloqueo de pantalla), la sesión
  local se borra y hay que iniciar sesión de nuevo.
- Si usas este código, no subas a ningún repositorio tu API Hash ni los datos de tu sesión.

## Problemas frecuentes

| Problema | Qué hacer |
|---|---|
| *"The project is using an incompatible version of the Android Gradle plugin"* | Actualiza Android Studio, o cambia la versión del plugin en el `build.gradle` de la raíz por la que te sugiera el mensaje. |
| No llegan los mensajes del bot | Revisa que el usuario del bot esté bien escrito (sin `@`) y que ya hayas iniciado el bot desde Telegram al menos una vez. |
| La app pide iniciar sesión otra vez | Es normal si se cambió el API ID/API Hash, se restableció el bot o Android invalidó la clave de cifrado. |
| Un filtro de palabra no oculta nada | Solo cuenta la palabra completa y se ignoran mayúsculas y tildes; prueba con la palabra exacta. |

## Créditos y licencia

- Conexión con Telegram mediante [TDLib](https://github.com/tdlib/td) (licencia Boost Software License 1.0).
- Telegram es una marca de Telegram FZ-LLC. Este proyecto no está afiliado ni respaldado por Telegram.
- Licencia del código de la app: agrega aquí la que prefieras (por ejemplo MIT) o déjalo sin licencia si es de uso personal.
