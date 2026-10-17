# Подготовка APK к распространению / Release checklist

Этот список применим к стабильным и preview-поставкам и связывает итоговый APK
с соответствующим исходным кодом. Наличие версии в changelog или release notes
не подтверждает подпись, успешные проверки, публикацию или независимый аудит.
CI создаёт только debug APK и **неподписанный** release APK; production-подпись
владелец выполняет отдельно локальным ключом, хранящимся вне Git.

## 1. Исходный код и проверки

- Выпускайте из чистого Git worktree с известным commit; запишите полный
  `git rev-parse HEAD`. Версии `versionCode` и `versionName` находятся в
  `app/build.gradle.kts`; для 1.0.0 ожидаются `versionCode = 2` и
  `versionName = "1.0.0"`. Изменения пользователя отражайте в `CHANGELOG.md`,
  описание поставки — в `docs/releases/<version>.md`. Финальный commit должен
  включать версию, документацию и все изменения, из которых собран APK; не
  выдавайте предыдущий preview-commit за исходники стабильной сборки.
- Выполните Python-проверки из `CONTRIBUTING.md` и полный Gradle quality gate из
  README: core/app unit tests, debug/release lint, оба APK и компиляцию
  instrumentation-тестов. Указывайте фактические результаты, а не только команды.
- Отдельно подтвердите instrumentation на API 31 и 36 и необходимые сценарии
  на физическом устройстве. Сборка APK не заменяет запуск на Android. Для тестов
  используйте синтетические данные; не публикуйте рабочие записи и backup-файлы.
  Указывайте API, эмулятор или физическое устройство и результат; если сценарий
  не выполнен, явно запишите «не проверено», а не подразумевайте успех.
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
  подписи production-ключом владельца. Храните ключ, пароли и резервную копию
  ключа вне Git, исходных архивов, публичных каталогов и CI-артефактов; debug-ключ
  не заменяет production-ключ. Выравнивание APK выполняют **до** подписи; после
  подписи файл нельзя изменять. Используйте поддерживаемые SDK `zipalign` и
  `apksigner`.
- Проверьте окончательный файл через `apksigner verify --verbose --print-certs`.
  Повторите проверку `scripts/check_release_apk.py` на итоговом подписанном APK:
  проверка содержимого не заменяет проверку подписи, и наоборот.
- Для обновления существующей установки требуется совместимая подпись и
  подходящий `versionCode`. У preview/debug может быть другой ключ. **Сначала
  создайте и проверьте отдельную зашифрованную backup-копию**; CSV не заменяет
  backup для восстановления. Не удаляйте приложение и не очищайте его данные
  ради обхода несовпадения подписи: это уничтожит локальные записи. При
  несовпадении остановите обновление и отдельно спланируйте перенос с проверенной
  копией, не обещая установку поверх существующего приложения.

## 3. Контрольные суммы и происхождение

CI сохраняет вместе с APK `metadata/SHA256SUMS` и
`metadata/SOURCE_COMMIT.txt`. Эти суммы относятся к **точным CI-файлам**, включая
неподписанный release, а не к будущей подписанной копии. После production-подписи
задайте окончательное имя APK и вычислите SHA-256 заново:

```bash
sha256sum path/to/final-signed.apk
```

```powershell
Get-FileHash -Algorithm SHA256 path/to/final-signed.apk
```

Для 1.0.0 подготовьте следующие release assets вне Git:

| Файл | Содержимое |
| --- | --- |
| `Open-TimeManager-1.0.0.apk` | Окончательный APK, подписанный production-ключом владельца. |
| `SHA256SUMS` | SHA-256 окончательного подписанного APK и точное имя файла; при добавлении других артефактов вычислите их суммы отдельно. |
| `SOURCE_COMMIT.txt` | Полный исходный commit сборки; release tag должен указывать на тот же commit. |
| `SIGNING_CERT.txt` | Публичные сведения сертификата и его SHA-256 fingerprint из проверки окончательного APK; без ключей и паролей. |
| `VERIFICATION.md` | Фактически выполненные проверки, ссылки на CI и явно указанные непроверенные сценарии. |
| `Open-TimeManager-1.0.0-source.tar.gz` | Corresponding Source точного release commit, созданный через `git archive`, без локальных файлов и ключей. |

Это ожидаемые имена, а не утверждение, что файлы уже созданы или опубликованы.
Генерируйте metadata из фактической сборки; не подставляйте примерные hashes и
не копируйте checksum неподписанного CI APK. Сопроводите поставку версией,
назначением подписи (production) и списком реально выполненных проверок с
ссылками на отчёты и явными пробелами проверки. Отчёты CI сохраняются отдельно
от APK, в том числе при ошибках тестов; само наличие отчёта не означает успех.
Перед публикацией убедитесь, что отчёты не содержат личных данных.

## 4. Соответствующий исходный код и лицензии

Open TimeManager распространяется по GPL-3.0-only. Получатель APK должен иметь
доступ к соответствующему исходному коду, включая изменения этой поставки и
необходимые материалы сборки, в соответствии с GPL. Ссылка на недоступный ему
приватный репозиторий этого не обеспечивает. Подготовьте доступный получателям
исходный архив точного release commit с необходимыми материалами сборки либо
другой подходящий способ предоставления Corresponding Source. Проверьте доступ
получателя до распространения APK.

Не меняйте видимость репозитория без явного разрешения владельца. Перед
переходом в публичный доступ проверьте публикуемые файлы, Git-историю и доступные артефакты на
секреты, личные данные и внутренние материалы. Публичный репозиторий должен
содержать именно соответствующий APK исходный commit, а не только более новую
ветку.

Сохраните `LICENSE`, `NOTICE`, `AUTHORS.md`, `ATTRIBUTION.md`,
`THIRD_PARTY_NOTICES.md`, упакованные офлайн legal assets и применимые сторонние
license/notice-тексты. Не включайте в исходную или бинарную поставку `.toolchain`,
SDK, кеши, signing-материалы, секреты, частные `.otmbackup` и внутренние рабочие
заметки. Сверьте юридический inventory с реальным release runtime classpath при
изменении зависимостей.

## 5. Публикация

- Сверьте release notes с фактическими результатами проверок и metadata; не
  заявляйте прохождение CI, проверку на телефоне или независимый аудит без
  соответствующего подтверждения.
- Свяжите release tag, `SOURCE_COMMIT.txt`, доступный Corresponding Source и
  точный подписанный APK. Загрузите финальные assets и проверьте скачанные
  файлы по checksum и сертификату.
- Отдельно подтвердите фактическую видимость репозитория и доступность исходников
  и assets получателям. Только после этого сообщайте, что релиз опубликован.
  Публикация APK в GitHub Releases не означает публикацию в Google Play.

**English summary:** release from a clean, identified source commit; record real
verification results; sign outside the repository; verify the final signature;
recompute SHA-256 after signing; supply matching Corresponding Source and legal
notices to APK recipients. Debug and unsigned CI artifacts are not production
releases. Existing preview/debug installations may use a different key: create
and verify an encrypted backup first; never uninstall or clear data to force an
update. Public visibility requires explicit owner authorization, a privacy/history
review, and actual source access. Record
unperformed checks honestly; release documentation alone is not proof of signing
or publication. Expected 1.0.0 assets are `Open-TimeManager-1.0.0.apk`,
`SHA256SUMS`, `SOURCE_COMMIT.txt`, and `SIGNING_CERT.txt`, generated from the
actual final build.
