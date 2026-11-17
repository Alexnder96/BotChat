# Mi Bot — app Android para un solo chat de Telegram

La app inicia sesión con **tu cuenta** de Telegram y muestra únicamente el chat con un bot.
Escribes la consulta, y la respuesta aparece sola en cuanto el bot contesta.

## Primer uso (todo se configura dentro de la app)

Ya no hay que tocar código. Al abrir la app por primera vez aparece la pantalla **Configura tu bot**:

| Dato | De dónde sale |
|---|---|
| API ID | https://my.telegram.org → "API development tools" (número) |
| API Hash | La misma página |
| Usuario del bot | El usuario del bot, sin `@` (también acepta `t.me/...`) |
| Mostrar el título del bot | Interruptor Sí/No, y el texto del título (por defecto `CHAT BOT`) |

Luego inicias sesión con tu número de Telegram y listo. Cada persona configura su propio bot.

Para cambiar cualquiera de estos datos después: **⋮ → Configuración**.
Si cambias el API ID o el API Hash, se cierra la sesión de Telegram y hay que iniciar sesión otra vez.

## Contraseña, Filtros y Configuración (menú ⋮)

**Filtros** y **Configuración** piden la misma contraseña (la primera vez te pide crearla, mínimo 4 caracteres,
y se escribe oculta con ●●●). Ambas pantallas se cierran solas si sales de la app.

Dentro de **Filtros** hay dos listas:

- **Palabras clave ocultas**: esa palabra se QUITA de los mensajes del bot (texto y pie de foto); el resto del
  mensaje se ve igual. Ejemplo: con `jubilate`, "hola solo por hoy jubilate" se ve "hola solo por hoy".
  Solo cuenta la palabra completa (`hoy` no se quita de `hoyo`). Si el mensaje era solo esa palabra, se oculta.
- **Imágenes ocultas por nombre**: se oculta la foto/archivo cuyo nombre contenga ese texto. Solo se oculta la
  foto: el pie de foto, los botones y la hora del mensaje se siguen viendo (si el mensaje no tiene pie de foto
  ni botones, no queda nada que mostrar y se oculta entero).
  En las fotos normales el nombre es el del archivo que descarga la app (el que ves en la galería, por ejemplo
  `5071069394380721250_121.jpg`); puedes escribirlo completo, sin `.jpg` o solo el número: vale para todos los
  tamaños de esa foto. En archivos enviados como 📎 se usa su nombre (`logo` oculta `logo_empresa.png`).
  Mientras hay filtros de imagen, cada foto espera a descargarse antes de mostrarse (así una foto filtrada
  nunca aparece ni un instante).

No distinguen mayúsculas ni tildes. Tus propios mensajes nunca se tocan.

**¿Olvidaste la contraseña?** En la ventana de contraseña toca **Olvidé mi contraseña** → *Restablecer el bot*.
Se borra TODO: filtros, contraseña, configuración (API ID, API Hash, usuario) y se cierra la sesión de Telegram.
La app queda como recién instalada. (Así quien no sepa la contraseña no puede ver tus códigos.)

## Estructura del código (`app/src/main/java/com/botchat/app/`)

| Paquete / archivo | Para qué sirve |
|---|---|
| `BotApp.java` | Carga la configuración y los filtros al arrancar. |
| **`data/`** | Datos y conexión con Telegram (sin pantallas). |
| `data/Config.java` | API ID, API Hash, usuario del bot, título y la opción "Proteger pantalla". |
| `data/Telegram.java` | La conexión con Telegram. Descarta todo lo que no sea del chat del bot. Reinicia TDLib si se cae (máx. 5 veces). |
| `data/SecureStore.java` | Guarda la clave de cifrado de la base de datos dentro del Android Keystore. |
| `data/DbKeyPolicy.java` | Decide (lógica pura) qué clave usar y cuándo migrar o borrar la base. |
| `data/MediaLoader.java` | Descarga y cachea fotos/archivos del chat. |
| `data/MessageStore.java` | Lista ordenada de mensajes (alta, edición, borrado). |
| `data/TdFileProvider.java` | Abrir con otra app los archivos que manda el bot. |
| **`filters/`** | Todo lo de filtros (lógica pura, con pruebas). |
| `filters/Filters.java` | Fachada: listas, contraseña (hash con sal) y bloqueo por intentos. |
| `filters/FilterRules.java` | Reglas inmutables: qué texto se quita, qué mensaje/foto se oculta. |
| `filters/TextRules.java` | Normaliza (mayúsculas/tildes) y busca palabras completas. |
| `filters/PasswordHasher.java` | PBKDF2-HMAC-SHA256, 600 000 iteraciones (migra hashes antiguos de 60 000). |
| `filters/Lockout.java` | Espera creciente tras 4 fallos (30 s, 60 s… máx. 15 min). |
| **`ui/`** | Pantallas. |
| `ui/ChatActivity.java` | La pantalla del chat: enviar, recibir, botones del bot, menú ⋮. Sobrevive a la rotación. |
| `ui/MessageAdapter.java` | Cómo se dibuja cada mensaje (y oculta los filtrados). |
| `ui/FilterGate.java` | Ventanas de contraseña: crear, pedir, cambiar y restablecer el bot. |
| `ui/FiltersActivity.java` | Pantalla donde se editan las listas de filtros. |
| `ui/SettingsActivity.java` | Configuración (primer uso y ⋮ → Configuración). |
| `ui/LoginActivity.java` | Número, código y contraseña de dos pasos. |
| `ui/TextFormatter.java` | Negritas, enlaces y `/comandos` tocables. |
| `res/values/strings.xml` | Todos los textos de la app (nombre incluido). |
| `res/values/colors.xml` | Los colores. |
| `app/src/test/…` | 58 pruebas unitarias de filtros, contraseña, bloqueo, clave de BD y lista de mensajes. |

## Seguridad y estabilidad

- **Base de datos cifrada**: TDLib guarda tus mensajes y sesión cifrados con una clave aleatoria de 32 bytes que
  vive en el Android Keystore (AES-GCM, no sale del teléfono). Una instalación que ya tenía datos sin cifrar se
  migra sola la primera vez. Si Android pierde la clave (p. ej. cambio de bloqueo de pantalla que la invalida),
  la sesión local se borra y hay que iniciar sesión de nuevo: es lo esperado, no un fallo.
- **Contraseña de filtros**: PBKDF2 con 600 000 iteraciones. Tras 4 intentos fallidos se bloquea con espera que
  se duplica (máx. 15 min). Se verifica fuera del hilo de pantalla ("Comprobando…").
- **Bloquear capturas en el chat** (⋮ → Configuración): impide capturas y la vista previa en "recientes" del
  chat. Las pantallas de Filtros, Configuración y Login siempre están protegidas.
- **Rotación**: el chat ya no se recrea al girar el teléfono (se conserva lo escrito y la posición).
- **Reintentos**: si el historial no carga a la primera, se reintenta cada 3 s; TDLib se reinicia solo si falla.
- **Release**: `minifyEnabled` y `shrinkResources` activados (las reglas de TDLib están en `proguard-rules.pro`).
- Ejecutar las pruebas: `./gradlew test` (o clic derecho en `app/src/test` → Run Tests).

## Qué soporta

- Texto con formato, enlaces y comandos tocables
- Fotos (se descargan solas; toca para verlas en grande)
- Documentos, videos, audios y notas de voz (toca para descargar y abrir)
- Botones debajo de los mensajes y teclado de respuestas rápidas del bot
- Mensajes que el bot edita ("Buscando…" → resultado)
- Mantener pulsado un mensaje copia el texto
- La tecla Enter (o la flecha de enviar del teclado) envía el mensaje, igual que el botón

## Si Android Studio no sincroniza

- **"The project is using an incompatible version of the Android Gradle plugin"**: tu Android Studio es
  anterior a Koala (2024.1). Actualízalo, o cambia `8.5.2` en `build.gradle` (el de la raíz) por la
  versión que te sugiera el mensaje.
- **Pide instalar "Android SDK Platform 34"**: acepta, es una descarga única.

## Importante

- La sesión queda guardada en el teléfono: quien tenga la app abierta puede escribirle al bot como tú.
  En Telegram → Ajustes → Dispositivos puedes cerrar esta sesión cuando quieras.
- No compartas tu API Hash. Se guarda en el teléfono, en el almacenamiento privado de la app.
- La app usa TDLib 1.8.56, la librería oficial de Telegram (licencia Boost 1.0), ya incluida en
  `app/libs/tdlib.jar` y `app/src/main/jniLibs/`.
