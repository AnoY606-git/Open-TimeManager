# Open TimeManager

[English](README.en.md) · [Документация](docs/README.md) · [Безопасность](SECURITY.md) · [GPL-3.0-only](LICENSE)

Open TimeManager — открытый локальный трекер рабочих часов для Android 12 и новее. Он помогает запустить таймер в два касания, исправить или добавить запись вручную, увидеть итоги и сохранить собственные данные без аккаунта, рекламы и облачного сервера.

Приложение не запрашивает `INTERNET`. Проекты, заметки, настройки и история находятся во внутреннем хранилище Android. Автоматический cloud backup Android отключён; перенос данных происходит только по явному действию через системный выбор файла.

## Что уже работает

- один устойчивый таймер с запуском, паузой, продолжением, завершением и безопасным переключением проекта;
- проекты с цветами, заметка активной записи и архивирование проекта без удаления истории;
- ручные записи, поиск, редактирование, подтверждаемое удаление, постраничный выбор проекта и ограниченная подгрузка старой истории;
- отчёты за неделю, месяц или выбранный период до 10 лет, итоги по проектам и CSV в UTF-8;
- зашифрованные копии `.otmbackup` с метаданными проектов и настройками, повторяемый проверенный предпросмотр, режимы «Объединить» и «Заменить»;
- уведомление foreground service, плитка быстрых настроек и виджет с защищёнными от устаревшего нажатия командами;
- русский и английский интерфейс, системная/светлая/тёмная тема, dynamic color;
- адаптивная навигация для телефона, горизонтального окна, split-screen и широкого экрана;
- локальный режим приватности, скрывающий имя проекта на системных поверхностях.

Таймер считает прошедшее время по монотонным часам устройства, а календарные границы — по UTC и текущей зоне. После перезагрузки или потери надёжной монотонной опоры незавершённая запись требует явной проверки, вместо угадывания рабочего времени.

## Скриншоты

Реальное приложение в эмуляторе Android 16 / API 36, только демонстрационные данные.
[Условия съёмки и пример данных](docs/screenshots/README.md).

| Таймер | История |
| --- | --- |
| <img src="docs/screenshots/ru-timer.png" width="250" alt="Таймер на паузе и выбранный демонстрационный проект"> | <img src="docs/screenshots/ru-history.png" width="250" alt="История демонстрационных рабочих записей"> |
| **Отчёты** | **Настройки** |
| <img src="docs/screenshots/ru-reports.png" width="250" alt="Отчёт с итогами по демонстрационным проектам"> | <img src="docs/screenshots/ru-settings.png" width="250" alt="Настройки темы, языка и приватности"> |

## Установка и сборка

Минимальная версия — Android 12 (API 31), целевая — Android 16 / API 36. Готовый APK в репозитории не подписывается production-ключом. Артефакты GitHub Actions — отладочный APK и неподписанный release APK; перед распространением release APK владелец должен подписать его отдельно.

Для воспроизводимой локальной сборки на Linux x86_64 нужны Python 3.12+ и `curl`:

```bash
python3 scripts/bootstrap.py --accept-licenses
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Bootstrap проверяет SHA-256 закреплённых JDK 17, Android command-line tools и Gradle Wrapper. Можно не использовать bootstrap: установите JDK 17 и Android SDK API 36 с platform-tools, задайте `JAVA_HOME` и `ANDROID_HOME` или `ANDROID_SDK_ROOT`. Обёртка ниже распознаёт эти стандартные переменные; явные `OTM_JAVA_HOME`/`OTM_ANDROID_HOME` имеют приоритет.

Обёртка сохраняет стандартные `ANDROID_USER_HOME` и `GRADLE_USER_HOME`, если не заданы соответствующие `OTM_*`-переопределения. Bootstrap не перезаписывает существующий `local.properties`: если в нём указан `sdk.dir`, согласуйте его с выбранным SDK, чтобы Gradle и обёрнутый `adb` использовали один комплект инструментов.

### Windows / PowerShell

Linux-bootstrap и `scripts/with-toolchain.sh` не предназначены для нативного Windows. Установите JDK 17 и [Android command-line tools](https://developer.android.com/studio#command-tools), распаковав последние в `Android\Sdk\cmdline-tools\latest`. Для проекта используйте путь без кириллицы, например `C:\src\Open-TimeManager`: Android Gradle Plugin проверяет путь на Windows.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
sdkmanager --sdk_root="$env:ANDROID_HOME" --licenses
sdkmanager --sdk_root="$env:ANDROID_HOME" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
.\gradlew.bat --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Замените `JAVA_HOME` на реальный каталог JDK 17. Зависимости проекта загружает Gradle Wrapper; отдельно устанавливать Gradle не нужно. Для Python-проверок нужен Python 3.11+: `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` и `python scripts/check_repository.py`. Только тесты исполнения Bash-обёртки пропускаются на нативном Windows; для их выполнения используйте Linux или WSL.

### Проверка устройства и установка

Для установки нужен Android 12+ эмулятор или подключённое устройство с включённой USB-отладкой; подтвердите RSA-запрос на экране устройства. После сборки проверьте, что обёрнутая команда `adb devices` показывает его в состоянии `device`, и установите точный debug-артефакт:

```bash
scripts/with-toolchain.sh adb devices
scripts/with-toolchain.sh adb install -r app/build/outputs/apk/debug/app-debug.apk
```

На Windows после настройки окружения выше используйте:

```powershell
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices -l
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK подписан автоматически созданным Android debug/test-ключом и предназначен только для тестирования. `app/build/outputs/apk/release/app-release-unsigned.apk` не установится, пока владелец не подпишет его своим release-ключом.

CI добавляет к APK контрольные суммы SHA-256 и точный исходный commit. Перед распространением подписанного APK выполните [проверочный список поставки](docs/RELEASING.md): после подписи контрольная сумма изменяется, а получателю должен быть доступен соответствующий исходный код.

Для полной проверки репозитория:

```bash
python3 scripts/check_repository.py
scripts/with-toolchain.sh ./gradlew --no-daemon \
  :core:test :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:compileDebugAndroidTestKotlin
scripts/with-toolchain.sh python3 scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Проверка release APK использует `aapt2` из Build Tools 36.0.0 по `ANDROID_HOME`/`ANDROID_SDK_ROOT` либо явный `--aapt2`. Она проверяет итоговые package/SDK/permissions после manifest merger, отсутствие debug/test-only, целостность ZIP и точные офлайн legal assets; подпись для этой проверки не требуется. Обёртка выбирает тот же SDK, что и при сборке; со своим настроенным SDK можно вызывать Python напрямую.

## Данные и безопасность

Файлы backup используют совместимый контейнер `OTMB1`: PBKDF2-HMAC-SHA256 (310 000 итераций), AES-256-GCM и аутентифицированный заголовок. Текущий payload schema v2 сохраняет цвета и даты проектов, тему, dynamic color, privacy mode и дневную цель; v1 читается с безопасными значениями по умолчанию. Язык выбирается в настройках приложения или наследуется от системы; этот выбор управляется Android/AppCompat и не переносится в backup. Приложение применяет мобильный операционный предел **4 МиБ** и до 20 000 проектов, записей и сегментов; базовый формат и core-кодек имеют жёсткий верхний предел 50 МиБ и 200 000 элементов. Эти большие пределы описывают формат, а не объём, который принимает Android UI.

Пароль копии восстановить нельзя. Неверный пароль и повреждение намеренно выглядят одинаково. Перед экспортом приложение завершает ожидающее намерение настроек и берёт строгий последовательный снимок Room и DataStore; ошибка чтения или согласования отменяет создание файла. Успешный режим «Заменить» безвозвратно удаляет текущие локальные проекты и историю перед импортом в одной транзакции — сначала сохраните отдельную проверенную копию. При ошибке или отмене транзакция Room откатывается, но это не отмена после успешного подтверждения. Настройки DataStore согласуются после Room-коммита через устойчивый журнал; последующие ручные изменения упорядочиваются тем же журналом и не могут быть перезаписаны более старым намерением восстановления. Если DataStore уже сохранил выбор, но системное privacy-зеркало не записалось, UI показывает предупреждение, а журнал повторяет запись после перезапуска. Общей транзакции между двумя хранилищами приложение не заявляет. Восстановление блокируется, пока таймер запущен, приостановлен или требует проверки.

Подробнее: [руководство пользователя](docs/USER_GUIDE.md), [модель безопасности](docs/SECURITY.md), [формат backup и CSV](docs/BACKUP_FORMAT.md).

## Ограничения preview-версии

- нет синхронизации, командных функций, веб-кабинета, геолокации, payroll и импорта CSV;
- перенос выполняется вручную через Storage Access Framework;
- системные кнопки зависят от разрешения уведомлений и ограничений прошивки/OEM; сам учёт внутри приложения остаётся доступен;
- release APK не подписан и не опубликован в Google Play;
- независимый криптографический аудит, проверка Google Play policy и подтверждённые метрики батареи/производительности не проводились;
- инструментальные тесты подготовлены для API 31 и 36 и запускаются в CI; локальная сборка сама по себе не доказывает прохождение на физическом устройстве.

## Участие и лицензия

Предложения и исправления приветствуются по правилам [CONTRIBUTING.md](CONTRIBUTING.md). Уязвимости сообщайте приватно по [SECURITY.md](SECURITY.md), не публикуя чувствительные детали в issue.

Собственный код Open TimeManager распространяется только по GNU GPL v3. Сторонние компоненты и происхождение адаптированных инструментов перечислены в [ATTRIBUTION.md](ATTRIBUTION.md) и [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). В приложении эти тексты доступны офлайн в настройках.
