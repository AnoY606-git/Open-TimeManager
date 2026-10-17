# Подготовка APK к распространению / Release checklist

Этот список связывает поставляемое приложение с проверенным исходным кодом. Он не
означает, что preview уже подписан production-ключом, опубликован или прошёл
независимый аудит. CI выпускает только debug APK и **неподписанный** release APK.

## 1. Исходный код и проверки

- Выпускайте из чистого Git worktree с известным commit; запишите полный
  `git rev-parse HEAD`. Версии `versionCode` и `versionName` находятся в
  `app/build.gradle.kts`; изменения пользователя отражайте в `CHANGELOG.md`.
- Выполните Python-проверки из `CONTRIBUTING.md` и полный Gradle quality gate из
  README: core/app unit tests, debug/release lint, оба APK и компиляцию
  instrumentation-тестов. Указывайте фактические результаты, а не только команды.
- Отдельно подтвердите instrumentation на API 31 и 36 и необходимые сценарии
  на физическом устройстве. Сборка APK не заменяет запуск на Android. Для тестов
  используйте синтетические данные; не публикуйте рабочие записи и backup-файлы.
- Проверьте отсутствие сетевых и широких storage-разрешений в итоговом manifest,
  отключённый Android backup, офлайн-юридические тексты и корректность RU/EN UI.
  Выполните `python scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk`
  с настроенным SDK (для Linux-bootstrap — через `scripts/with-toolchain.sh`).
  Проверка анализирует собранный APK, а не только исходный manifest.

## 2. Артефакт и подпись

- `app/build/outputs/apk/debug/app-debug.apk` автоматически подписан локальным
  debug/test-ключом и предназначен только для тестирования. Не выдавайте его за
  production-поставку.
- `app/build/outputs/apk/release/app-release-unsigned.apk` требует отдельной
  подписи ключом владельца. Выравнивание APK выполняют **до** подписи; после
  подписи файл нельзя изменять. Используйте поддерживаемые SDK `zipalign` и
  `apksigner`, сохраняя ключи и пароли вне репозитория и CI-артефактов.
- Проверьте окончательный файл через `apksigner verify --verbose --print-certs`.
  Для обновления существующей установки требуется прежний ключ. Не удаляйте
  приложение ради обхода несовпадения подписи: это удалит его локальные данные.

## 3. Контрольные суммы и происхождение

CI сохраняет вместе с APK манифест SHA-256 и исходный commit. Эти суммы относятся
к **точным CI-файлам**, включая неподписанный release, а не к будущей подписанной
копии. После production-подписи вычислите SHA-256 заново:

```bash
sha256sum path/to/final-signed.apk
```

```powershell
Get-FileHash -Algorithm SHA256 path/to/final-signed.apk
```

Сопроводите поставку версией, полным commit/tag, именем APK, окончательной суммой,
назначением подписи (test/production), публичным отпечатком сертификата и списком
реально выполненных проверок. Отчёты CI сохраняются отдельно от APK, в том числе
при ошибках тестов; перед публикацией убедитесь, что в них нет личных данных.

## 4. Соответствующий исходный код и лицензии

Open TimeManager распространяется по GPL-3.0-only. Получатель APK должен иметь
доступ к соответствующему исходному коду, включая изменения этой поставки и
необходимые материалы сборки, в соответствии с GPL. Ссылка на недоступный ему
приватный репозиторий этого не обеспечивает. Не меняйте видимость репозитория
автоматически: подготовьте доступный получателям исходный архив или другой
подходящий способ предоставления Corresponding Source.

Сохраните `LICENSE`, `NOTICE`, `AUTHORS.md`, `ATTRIBUTION.md`,
`THIRD_PARTY_NOTICES.md` и применимые сторонние license/notice-тексты. Не включайте
в исходную или бинарную поставку `.toolchain`, SDK, кеши, signing-материалы,
секреты и внутренние рабочие заметки. Сверьте юридический inventory с реальным
release runtime classpath при изменении зависимостей.

**English summary:** release from a clean, identified source commit; record real
verification results; sign outside the repository; verify the final signature;
recompute SHA-256 after signing; supply matching Corresponding Source and legal
notices to APK recipients. Debug and unsigned CI artifacts are not production
releases.
