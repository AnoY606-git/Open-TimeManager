# Сборка из исходников / Building from source

[Русский](#русский) · [English](#english) · [Документация](README.md)

## Русский

Для обычной установки проще скачать [готовый APK](https://github.com/AnoY606-git/Open-TimeManager/releases/latest).
Эта страница — для тех, кто хочет изменить код или запустить проверки.
Все команды выполняются из корня репозитория.

### Что понадобится

- Git и JDK 17.
- Android SDK: `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`.
- Python 3.11+ для проверок скриптов. Необязательная автоматическая настройка Linux требует Python 3.12+ и `curl`.

Отдельно устанавливать Gradle не нужно: проект использует Gradle Wrapper.
Версии инструментов для автоматической настройки указаны в [toolchain.json](../gradle/toolchain.json).

### Linux и WSL

Автоматическая настройка поддерживает Linux x86_64. Она скачает инструменты в
локальный каталог `.toolchain` и проверит их контрольные суммы. Флаг
`--accept-licenses` разрешает принять лицензии Android SDK:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon :app:assembleDebug
```

Если JDK и SDK уже установлены, можно использовать их напрямую. Задайте
`JAVA_HOME` и `ANDROID_HOME` либо `ANDROID_SDK_ROOT`, затем запустите:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Обёртка `scripts/with-toolchain.sh` тоже умеет работать с этими переменными.
Она выбирает инструменты в порядке: явные `OTM_JAVA_HOME`/`OTM_ANDROID_HOME`,
локальная `.toolchain`, стандартные переменные окружения. Не меняйте набор
инструментов между сборкой и установкой APK.

### Windows и PowerShell

Установите JDK 17 и [Android command-line tools](https://developer.android.com/studio#command-tools).
Распакуйте инструменты SDK так, чтобы существовал каталог
`Android\Sdk\cmdline-tools\latest\bin`. Держите проект в пути без кириллицы,
например `C:\src\Open-TimeManager`: это ограничение Android Gradle Plugin на Windows.

Замените путь к JDK в примере своим:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
sdkmanager --sdk_root="$env:ANDROID_HOME" --licenses
sdkmanager --sdk_root="$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
.\gradlew.bat --no-daemon :app:assembleDebug
```

`bootstrap.py` и Bash-обёртка не предназначены для нативного Windows. В WSL
используйте инструкции для Linux, не смешивая Windows SDK с Linux-инструментами.

### Проверки перед отправкой изменений

На Linux с автоматически настроенными инструментами:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin
```

На Windows с окружением из примера выше:

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/check_repository.py
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Тесты исполнения Bash пропускаются на нативном Windows; запускайте их в Linux
или WSL. CI для push, pull request и ручного запуска выполняет только debug-проверки
и публикует debug APK с SHA-256 и commit исходников. Стабильную release-сборку
владелец выполняет отдельно по [инструкции выпуска](RELEASING.md).

### Запуск на эмуляторе

Используйте отдельный эмулятор Android 12+ с вымышленными данными.
Сначала посмотрите список устройств и замените `SERIAL` выбранным идентификатором:

```bash
scripts/with-toolchain.sh adb devices -l
scripts/with-toolchain.sh adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

На Windows:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Для инструментальных тестов оставьте подключённым только тестовый эмулятор
или явно выберите его через `ANDROID_SERIAL`:

```bash
ANDROID_SERIAL=SERIAL scripts/with-toolchain.sh ./gradlew --no-daemon :app:connectedDebugAndroidTest
```

```powershell
$env:ANDROID_SERIAL = 'SERIAL'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

CI запускает эти тесты на API 31 и 36. Компиляция тестов без эмулятора не заменяет
их выполнение. Не используйте установку с важной историей для тестов восстановления.

### Где лежат APK

| Файл | Назначение |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | Тестовая сборка с debug-подписью. |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | Неподписанная release-сборка; перед установкой её нужно подписать. |

Debug-подпись отличается от подписи опубликованного приложения. Не удаляйте
приложение с рабочими данными ради установки тестовой сборки. Для распространения
используйте [порядок выпуска релиза](RELEASING.md).

### Если сборка не запускается

- **Gradle не видит Java:** проверьте `JAVA_HOME` и версию JDK — нужна 17.
- **SDK не найден:** проверьте `ANDROID_HOME` и `sdk.dir` в локальном `local.properties`.
  Они должны указывать на один SDK. Автоматическая настройка не перезаписывает этот файл.
- **На Windows ошибка пути:** перенесите рабочую копию в каталог без кириллицы.
- **Обёртка выбрала не те инструменты:** задайте явные `OTM_JAVA_HOME` и `OTM_ANDROID_HOME`.
  Стандартные `ANDROID_USER_HOME` и `GRADLE_USER_HOME` сохраняются, если не заданы
  соответствующие `OTM_*`-переопределения.

## English

For everyday use, download the [ready-to-install APK](https://github.com/AnoY606-git/Open-TimeManager/releases/latest).
Use this page to build changes or run checks. Run all commands from the repository root.

### Requirements

- Git and JDK 17.
- Android SDK with `platform-tools`, `platforms;android-36`, and `build-tools;36.0.0`.
- Python 3.11+ for script tests. The optional Linux bootstrap needs Python 3.12+ and `curl`.

Gradle Wrapper downloads Gradle for you. Pinned tool versions are listed in
[toolchain.json](../gradle/toolchain.json).

### Linux and WSL

The Linux x86_64 bootstrap downloads tools into `.toolchain`, verifies their
checksums, and accepts Android SDK licenses when requested with this option:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon :app:assembleDebug
```

To use an existing installation, set `JAVA_HOME` and `ANDROID_HOME` or
`ANDROID_SDK_ROOT`, then run:

```bash
./gradlew --no-daemon :app:assembleDebug
```

The Bash wrapper also supports existing tools. Its selection order is explicit
`OTM_JAVA_HOME`/`OTM_ANDROID_HOME`, local `.toolchain`, then standard environment
variables. Use the same SDK for building and installing.

### Windows and PowerShell

Install JDK 17 and [Android command-line tools](https://developer.android.com/studio#command-tools).
Extract the SDK tools into `Android\Sdk\cmdline-tools\latest`.
Use a project path without non-ASCII characters, such as `C:\src\Open-TimeManager`,
to avoid Android Gradle Plugin's Windows path restriction. Replace the example JDK path:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
sdkmanager --sdk_root="$env:ANDROID_HOME" --licenses
sdkmanager --sdk_root="$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
.\gradlew.bat --no-daemon :app:assembleDebug
```

The bootstrap and Bash wrapper do not run on native Windows. In WSL, follow the
Linux instructions and keep Linux and Windows SDK installations separate.

### Check your changes

On Linux with bootstrapped tools:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin
```

With your own JDK/SDK, omit `scripts/with-toolchain.sh`. On Windows with the
environment above:

```powershell
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/check_repository.py
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Only Bash execution tests are skipped on native Windows; run those in Linux or WSL.
CI for pushes, pull requests, and manual runs performs debug checks and uploads
the debug APK with its SHA-256 and source commit. The owner builds stable releases
separately using the [release checklist](RELEASING.md#english).

### Run on an emulator

Use a dedicated Android 12+ emulator with made-up records. Find its serial, then
replace `SERIAL` in the install command:

```bash
scripts/with-toolchain.sh adb devices -l
scripts/with-toolchain.sh adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

For instrumentation, leave only the test emulator connected or select it explicitly:

```bash
ANDROID_SERIAL=SERIAL scripts/with-toolchain.sh ./gradlew --no-daemon :app:connectedDebugAndroidTest
```

```powershell
$env:ANDROID_SERIAL = 'SERIAL'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

CI tests API 31 and 36. Compiling test sources is not the same as running them.
Do not run restore tests against an installation containing important records.

### Build outputs and common problems

- `app/build/outputs/apk/debug/app-debug.apk` is signed with a test key.
- `app/build/outputs/apk/release/app-release-unsigned.apk` needs signing before installation.

Test builds cannot update the official release if their signatures differ.
Do not erase working data to install them. See the [release checklist](RELEASING.md#english)
before distributing a build.

If tools are not found, check JDK 17's `JAVA_HOME`, the SDK path, and `sdk.dir` in
`local.properties`. Bootstrap leaves that file unchanged. For wrapper overrides,
use `OTM_JAVA_HOME`/`OTM_ANDROID_HOME`. Standard `ANDROID_USER_HOME` and
`GRADLE_USER_HOME` remain in use unless their `OTM_*` overrides are set.
