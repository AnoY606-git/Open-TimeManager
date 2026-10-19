# Выпуск релиза / Releasing

[Русский](#русский) · [English](#english) · [Сборка и тесты](BUILDING.md)

## Русский

Этот список нужен для публикации APK, который можно связать с конкретными
исходниками и безопасно установить как обновление. CI собирает только debug APK
для тестов. Стабильную release-сборку владелец готовит самостоятельно;
ключ официальной подписи хранится отдельно от Git и CI.

### 1. Подготовить исходники

- Установите `versionName` и увеличьте `versionCode` в `app/build.gradle.kts`.
- Обновите changelog, описание выпуска и скриншоты, если интерфейс изменился.
- Сохраните изменения в Git и отправьте их в репозиторий. Собирайте из чистой
  рабочей копии; запишите полный `git rev-parse HEAD`.
- Запустите все [проверки сборки](BUILDING.md#проверки-перед-отправкой-изменений)
  и дождитесь успешного CI именно для этого commit.

### 2. Проверить на Android

Используйте отдельные эмуляторы API 31 и 36 с вымышленными данными.
Личное устройство допустимо только с отдельного разрешения владельца.

- Пройдите запуск, паузу, продолжение, завершение, смену проекта и проверку
  прерванной сессии. Проверьте устаревшие системные кнопки.
- Проверьте историю, ручные записи, поиск, отчёты и реальное сохранение CSV.
- Откройте настройки и справку на RU/EN, проверьте возврат назад, крупный шрифт
  и узкое/широкое окно.
- Проверьте проект по умолчанию: он не меняет активную сессию и не переносится
  в копиях; архивный или отсутствующий проект не используется.
- Создайте и откройте оба формата копий. Проверьте старые зашифрованные копии,
  неверный пароль, повреждённый файл, лимиты, отказ доступа и отмену записи.
- Проверьте предупреждения открытой копии, предпросмотр, конфликты при объединении,
  замену данных и применение настроек после прерывания. Ошибка пароля не должна
  открывать путь к чтению без пароля.
- Проверьте уведомление, плитку, виджет и скрытие названий. На API 31 нужен
  ручной путь добавления плитки; на новых системах — системный запрос.
- Для Live Updates проверьте выключенное по умолчанию состояние, работу только
  при запущенном таймере и учёт скрытия пользователем. Успешный запрос API
  не означает, что система действительно выделила уведомление.

Записывайте реальные результаты и непроверенные сценарии. Компиляция не заменяет
запуск тестов, наличие элементов доступности — проверку TalkBack, а сокращение
обновлений — измерение расхода батареи.

### 3. Подписать APK

Соберите release APK локально из проверенного commit:

```bash
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Для Linux-bootstrap используйте `scripts/with-toolchain.sh`, на Windows —
`.\gradlew.bat`. Неподписанный APK появится в
`app/build/outputs/apk/release/OTM.apk`. Короткое имя не означает наличие подписи:
это по-прежнему неподписанная сборка.

Используйте `zipalign` **до** подписи и `apksigner` из Android SDK. Не меняйте файл
после подписи. Ключи, пароли и их резервные копии не должны попадать в Git,
исходный архив или публичные артефакты.

Проверьте итоговый файл:

```bash
apksigner verify --verbose --print-certs path/to/final-signed.apk
python3 scripts/check_release_apk.py path/to/final-signed.apk
```

Проверка содержимого использует настроенный SDK и не заменяет проверку подписи.
Для Linux-bootstrap запускайте Python через `scripts/with-toolchain.sh`;
на Windows используйте `python`.

### 4. Проверить обновление

Сравните SHA-256 сертификата с предыдущим стабильным выпуском. Установите
подписанный APK поверх прежней стабильной версии на эмуляторе и сравните проекты,
историю и настройки до и после. Не удаляйте приложение, не очищайте данные и не
используйте downgrade в этой проверке.

Для обновления нужна совместимая подпись и увеличенный `versionCode`.
При несовместимости с debug/preview остановитесь и отдельно спланируйте перенос
через проверенную зашифрованную копию. Не советуйте удаление как обход ошибки.

### 5. Подготовить файлы выпуска

| Файл | Что положить |
| --- | --- |
| `OTM.apk` | Окончательный подписанный APK. |
| `SHA256SUMS` | Суммы всех публикуемых файлов, рассчитанные после подписи. |
| `SIGNING_CERT.txt` | Публичные данные сертификата и результат сравнения с прошлой версией. |
| `SOURCE_COMMIT.txt` | Полный commit исходников этой сборки. |
| `Open-TimeManager-<version>-source.tar.gz` | Исходники того же commit с материалами сборки и лицензиями. |
| `VERIFICATION.md` | Выполненные проверки, ссылка на CI, проверка обновления и известные пробелы. |

Контрольная сумма после подписи отличается от суммы локального неподписанного APK.
Вычислите её заново через `sha256sum` или `Get-FileHash -Algorithm SHA256`.
В отчёт не включайте личные данные, а непроверенную совместимость с прошивками,
расход батареи или продвижение уведомления не записывайте как успех.

### 6. Предоставить исходники и лицензии

Получателю APK должен быть доступен соответствующий исходный код по условиям
GPL-3.0-only. Создавайте архив через `git archive` из того же commit, на который
указывает tag; ссылка на недоступный приватный репозиторий не подходит.

Сохраните `LICENSE`, `NOTICE`, `AUTHORS.md`, `ATTRIBUTION.md`,
`THIRD_PARTY_NOTICES.md` и их офлайн-копии в приложении. При изменении зависимостей
сверяйте сторонние лицензии с `releaseRuntimeClasspath`.
Не включайте SDK, кеши, ключи, личные копии или CSV. Перед первой публичной
публикацией проверьте также Git-историю и доступные артефакты на секреты.
Видимость репозитория меняйте только с разрешения владельца.

### 7. Опубликовать и перепроверить

1. Убедитесь, что tag, `SOURCE_COMMIT.txt`, архив и встроенные сведения APK
   указывают на одни исходники.
2. Загрузите файлы в GitHub Release. Стабильный выпуск не помечайте prerelease.
3. Скачайте опубликованные файлы обратно, сверьте суммы и сертификат APK.
4. Проверьте доступность загрузок и исходников получателям, затем сообщайте о выпуске.

После публикации не перемещайте tag и не подменяйте APK ради правок документации.
Такие правки идут в основную ветку и следующий выпуск. GitHub Release не означает
публикацию в Google Play.

## English

CI produces only test-signed debug APKs. The owner builds stable releases
separately. Keep the production key outside Git and CI.

1. **Prepare the source.** Set the version and increase the version code. Update
   the changelog, notes, and affected screenshots. Commit and push, build from a
   clean checkout, record the full commit, and require green CI for that exact source.
2. **Test on Android.** Use dedicated API 31 and 36 emulators with made-up data.
   Cover timer transitions/recovery, stale actions, projects, history, reports,
   real CSV export, both backup formats, old-file compatibility, failure handling,
   Merge/Replace, settings recovery, languages, layouts, and system controls.
   Check Live Updates as a request contract, not a guarantee of actual promotion.
3. **Build and sign separately.** From the verified commit, run
   `./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`
   locally (use `scripts/with-toolchain.sh` for Linux bootstrap or
   `.\gradlew.bat` on Windows). This produces
   `app/build/outputs/apk/release/OTM.apk` (still unsigned despite the short name).
   Run `zipalign` before signing, then `apksigner`. Verify
   the final signature and run `scripts/check_release_apk.py` against that file.
   Keep keys and passwords out of every public artifact.
4. **Test an update.** Compare the certificate with the previous stable release
   and install over it without uninstalling, clearing data, or downgrading.
   Compare records and settings. For a signature mismatch, stop and plan a
   separate migration using a checked encrypted backup.
5. **Prepare downloads.** Include the signed `OTM.apk`, `SHA256SUMS`, `SIGNING_CERT.txt`,
   `SOURCE_COMMIT.txt`, matching source archive, and `VERIFICATION.md`. Calculate
   checksums after signing. Record real results, CI links, and untested scenarios.
6. **Provide source and notices.** Recipients need matching Corresponding Source
   under GPL-3.0-only. Use `git archive` for the exact tag commit, retain legal
   documents and packaged notices, and exclude private files and local tools.
7. **Publish and verify.** Link the tag, source, and exact APK. Download published
   files again to verify checksums and signature. Confirm recipient access before
   announcing the release. Do not move an existing tag or replace an APK just to
   edit documentation; use the default branch and the next release.

Build commands are in [Building from source](BUILDING.md#english).
Only use a personal device with its owner's permission. Do not claim unperformed
device, accessibility, battery, or security testing. GitHub distribution is not a
Google Play release.
