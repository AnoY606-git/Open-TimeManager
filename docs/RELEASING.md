# Подготовка APK к распространению / Release checklist

Этот список применим к стабильным и preview-поставкам и связывает итоговый APK
с соответствующим исходным кодом. Наличие версии в changelog или release notes
не подтверждает подпись, успешные проверки, публикацию или независимый аудит.
CI создаёт только debug APK и **неподписанный** release APK; production-подпись
владелец выполняет отдельно локальным ключом, хранящимся вне Git.

## 1. Исходный код и проверки

- Выпускайте из чистого Git worktree с известным commit; запишите полный
  `git rev-parse HEAD`. Версии `versionCode` и `versionName` находятся в
  `app/build.gradle.kts`; для 1.1.0 ожидаются `versionCode = 3` и
  `versionName = "1.1.0"`. Изменения пользователя отражайте в `CHANGELOG.md`,
  описание поставки — в `docs/releases/<version>.md`. Финальный commit должен
  включать версию, документацию и все изменения, из которых собран APK; не
  выдавайте предыдущий preview-commit за исходники стабильной сборки.
- Выполните Python-проверки из `CONTRIBUTING.md` и полный Gradle quality gate из
  README: core/app unit tests, debug/release lint, оба APK и компиляцию
  instrumentation-тестов. Указывайте фактические результаты, а не только команды.
- Отдельно подтвердите instrumentation на эмуляторах API 31 и 36. Сборка APK не
  заменяет запуск на Android. Физическое устройство используйте только с явным
  разрешением владельца; если оно не проверялось, так и укажите. Для тестов
  используйте синтетические данные; не публикуйте рабочие записи и backup-файлы.
  Указывайте API, эмулятор или физическое устройство и результат; если сценарий
  не выполнен, явно запишите «не проверено», а не подразумевайте успех.
- Проверьте отсутствие сетевых и широких storage-разрешений в итоговом manifest,
  отключённый Android backup, офлайн-юридические тексты и корректность RU/EN UI.
  Выполните `python scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk`
  с настроенным SDK (для Linux-bootstrap — через `scripts/with-toolchain.sh`).
  Проверка анализирует собранный APK, а не только исходный manifest.

### Сценарии 1.1.0

- Проверьте обзор настроек и все пять экранов, поиск по десяти темам Help на RU/EN,
  возврат назад, крупный шрифт и узкое/широкое окно. Отдельно фиксируйте ручные
  проверки доступности; наличие Compose semantics не доказывает прохождение TalkBack.
- Проверьте приоритет active → explicit idle → preferred → fallback, отклонение
  архивного/отсутствующего default, quick Start с preference и без него. Убедитесь,
  что локальный default не попал в `UserPreferences`, backup или portable schema.
- Откройте прежние `OTMB1` schema v1/v2 и новые копии обоих видов. Проверьте
  предупреждения `.otmplain`, неизвестный magic, повреждение, лимиты, неверный
  пароль, отмену/ошибку записи и отсутствие автоматического перехода к plaintext.
  Structural preview и checksum открытого файла не называйте аутентификацией.
- Проверьте Merge/Replace, UUID-конфликты, блокировку при активном таймере,
  устойчивое согласование настроек и явно видимые незавершённые состояния.
- Проверьте per-app language и ручной tile fallback на API 31, системный tile-add
  запрос на поддерживаемом Android 13+, pin/fallback лаунчера и только чтение
  battery/background status. OEM-подсказки не заменяют проверку конкретной ROM.
- Проверьте монотонное время, reboot review, фиксированную длительность паузы,
  защиту устаревших команд и отсутствие повторной публикации одинаковых проекций.
  Эти проверки сами по себе не являются battery/performance benchmark.
- Сравните итоговые permissions/minSdk/targetSdk с 1.0: только обычное
  `POST_PROMOTED_NOTIFICATIONS` добавлено для необязательных Live Updates Android 16.
  Проверьте default-off, ограничение только активной сессией, сохранение скрытия,
  стандартное уведомление на API 31–35 и приватность. Не обещайте продвижение на
  каждой оболочке или закрытую интеграцию с Super Island/HyperIsland.

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
- Для 1.1.0 используйте тот же production-ключ, что и для 1.0.0. Сравните публичный
  SHA-256 fingerprint сертификата итогового APK с опубликованной 1.0.0 и явно
  запишите результат. На разрешённом эмуляторе с синтетическими данными проверьте
  обновление точной подписанной 1.0.0 до точной подписанной 1.1.0 **без удаления
  приложения, очистки данных или флага downgrade**; сравните историю, проекты и
  настройки до/после. Намерение использовать тот же ключ не доказывает этот результат.

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

Для 1.1.0 подготовьте следующие release assets вне Git:

| Файл | Содержимое |
| --- | --- |
| `Open-TimeManager-1.1.0.apk` | Окончательный APK, подписанный production-ключом владельца. |
| `SHA256SUMS` | SHA-256 окончательного подписанного APK и точное имя файла; при добавлении других артефактов вычислите их суммы отдельно. |
| `SOURCE_COMMIT.txt` | Полный исходный commit сборки; release tag должен указывать на тот же commit. |
| `SIGNING_CERT.txt` | Публичные сведения сертификата и его SHA-256 fingerprint из проверки окончательного APK; без ключей и паролей. |
| `VERIFICATION.md` | Фактически выполненные проверки, ссылки на CI, сравнение сертификатов с 1.0, проверка обновления и явно указанные непроверенные сценарии/OEM. |
| `Open-TimeManager-1.1.0-source.tar.gz` | Corresponding Source точного release commit, созданный через `git archive`, без локальных файлов и ключей. |

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
SDK, кеши, signing-материалы, секреты, частные `.otmbackup`/`.otmplain`, чувствительные CSV и внутренние рабочие
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
- До появления доступных финальных assets сохраняйте отметку **upcoming** в README,
  changelog и `docs/releases/1.1.0.md`. После публикации обновите статус по факту;
  исторические notes 1.0 не переписывайте. Проценты экономии батареи, проверку
  OEM/физического телефона, CI или подпись без результатов не заявляйте.

**English summary:** release from a clean, identified source commit; record real
verification results; sign outside the repository; verify the final signature;
recompute SHA-256 after signing; supply matching Corresponding Source and legal
notices to APK recipients. Debug and unsigned CI artifacts are not production
releases. Existing preview/debug installations may use a different key: create
and verify an encrypted backup first; never uninstall or clear data to force an
update. Public visibility requires explicit owner authorization, a privacy/history
review, and actual source access. Record
unperformed checks honestly; release documentation alone is not proof of signing
or publication. For 1.1.0 expect version code 3 and the same production certificate
as 1.0; compare fingerprints and verify an in-place update on an authorized emulator
without uninstalling or clearing data. Personal physical devices require separate
explicit authorization. Expected 1.1.0 assets are `Open-TimeManager-1.1.0.apk`,
`SHA256SUMS`, `SOURCE_COMMIT.txt`, and `SIGNING_CERT.txt`, generated from the
actual final build, plus actual `VERIFICATION.md` results and matching source.
Keep both `.otmbackup` and unencrypted `.otmplain` private. Never treat a plaintext
checksum as authentication, a failed encrypted read as permission to downgrade,
or event-driven update filtering as a measured battery benchmark.
