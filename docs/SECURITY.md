# Безопасность и приватность / Security and privacy

Модель 1.1.0 / 1.1.0 model. [Статус выпуска / Release status](releases/1.1.0.md).
Это описание границ защиты, не независимый аудит или отчёт об успешных проверках.

## Хранение и разрешения

Open TimeManager работает офлайн без `INTERNET`, аккаунта, рекламы, аналитики,
геолокации, AccessibilityService и широкого доступа к файлам. Room и DataStore
находятся во внутреннем credential-protected storage. Используются Android
sandbox и защита хранилища устройства; отдельное app-level шифрование Room не
заявляется. Разблокированное, скомпрометированное или root-устройство находится
за пределами этой защиты.

Автоматический Android backup/device transfer отключён. Явные экспорт и импорт
используют Storage Access Framework. Выбранный пользователем document provider
может быть облачным: отсутствие сетевого разрешения у приложения не делает
внешний провайдер локальным. Экспорт раскрывает данные получателю файла.

Поколение слота, идентификатор записи и revision защищают системные команды от
устаревших нажатий. PendingIntents остаются immutable и явно адресованными.
Компоненты закрыты, кроме launcher Activity и TileService под системным
`BIND_QUICK_SETTINGS_TILE`. Отображение длительности опирается на монотонное
время; после потери надёжной опоры неподтверждённая работа требует проверки.

## Два разных формата копий

### Зашифрованная `.otmbackup` — действие по умолчанию

- Прежний контейнер **`OTMB1`**: PBKDF2-HMAC-SHA256, 310 000 итераций, salt 16 байт,
  nonce 12 байт, AES-256-GCM и tag 128 бит; весь 42-байтовый заголовок входит в AAD.
- Неверный пароль и повреждённый ciphertext дают одинаковый результат без
  provider-specific подробностей. Ошибка не разрешает незаметно перейти к plaintext.
- При создании пароль содержит не менее 12 Unicode code points и не более 1024
  байт UTF-8; пробелы не обрезаются, Unicode не нормализуется. Это минимум длины,
  не гарантия энтропии: нужна уникальная длинная парольная фраза.
- Старые копии сохраняют совместимость исходных паролей, включая короткие.
  Восстановить забытый пароль нельзя. UI очищает собственные буферы, но JVM и
  криптографический провайдер могут создавать внутренние копии.
- GCM защищает от изменения без пароля, но не доказывает личность автора и не
  является публичной цифровой подписью. Любой, кто знает пароль, может создать другую копию.

### Открытая `.otmplain` — отдельный явный выбор

- Новый контейнер **`OTMP1`** не содержит шифрования, пароля, MAC или подписи.
  Проекты, заметки, история и переносимые настройки доступны любому получателю.
- SHA-256 обнаруживает случайное повреждение. Злоумышленник может изменить
  содержимое и пересчитать сумму; **checksum не обеспечивает подлинность**.
- Перед созданием требуется отдельное предупреждение. Перед просмотром импорта
  есть предупреждение о недоверенном файле; предпросмотр не называется проверкой
  его автора или безопасности содержимого.
- Выбор контейнера определяется поддерживаемым magic и строгой структурой,
  а не доверием к расширению файла. Повреждённый или неизвестный контейнер должен
  завершаться отказом, не автоматическим ослаблением защиты.

Оба формата записывают один payload **schema v2** с теми же проверками доменных
инвариантов, переполнения, структуры и ресурсов. Schema v1 старых зашифрованных
копий читается с безопасными значениями по умолчанию. Android принимает до **4 МиБ**
и **20 000 объектов каждого вида**; hard limits core — 50 МиБ и 200 000.
Большие core-пределы не являются лимитами Android UI.

Переносятся метаданные проектов, тема, dynamic color, privacy mode и дневная цель.
Язык и проект по умолчанию **локальны**: последнего нет в `UserPreferences`,
переносимом payload или schema. Восстановление переносимых настроек сохраняет
локальный выбор; отсутствующий/архивный проект не используется для запуска.

Копия хранит подтверждённые закрытые интервалы, не текущий открытый отрезок.
Импорт не запускает таймер автоматически. Шифрование относится только к
`.otmbackup`, не к `.otmplain`, CSV или локальной базе.

## Восстановление и согласованность

До применения файл проходит проверку контейнера, schema и лимитов, затем
предпросмотр конфликтов. Для plaintext это структурная проверка, не криптографическая
аутентификация. Восстановление блокируется при RUNNING, PAUSED и NEEDS_REVIEW.

**Replace разрушителен:** после подтверждения заменяет локальную историю и
переносимые настройки без Undo. Транзакция Room и точки отмены между ограниченными
пакетами предотвращают частичное применение при сбое/отмене, но не отменяют уже
успешное действие. Сначала создайте отдельную проверенную зашифрованную копию.
Merge сохраняет локальные данные и повторно проверяет UUID-конфликты в транзакции.

После Applied предпросмотр расходуется. После блокировки, конфликта, отмены или
отката его можно повторить. Hide оставляет его только в памяти процесса; Cancel
restore уничтожает payload. Пароль и предпросмотр не сохраняются в saved state
или на диск. После завершения процесса файл требуется открыть заново.

Общей транзакции Room/DataStore нет. Room сохраняет данные и устойчивое намерение
применить переносимые настройки; журнал доводит DataStore после коммита и повторяет
незавершённую работу после запуска. Ручные переносимые изменения используют ту
же последовательную границу: старое восстановление не должно затереть более
поздний выбор. Строгое чтение перед частичной записью и экспортом не заменяет
непрочитанные данные значениями по умолчанию. Если privacy mirror не согласовалось,
устойчивое намерение сохраняется для повтора; это не повод повторять Replace.

Перед созданием любого backup завершается ожидающий journal, затем берётся
последовательный снимок Room и DataStore. Ошибка чтения/согласования отменяет
подготовку; она не выбирает plaintext вместо шифрования. Ошибка очистки частичного
файла у внешнего провайдера может потребовать ручного удаления этого файла.

CSV — открытый текст с возможными именами проектов и заметками; формульные
префиксы обезвреживаются, но файл остаётся чувствительным и не подходит для restore.
Его согласованный снимок сначала создаётся в приватном временном файле: медленное
I/O внешнего провайдера не удерживает Room-транзакцию всё время сохранения.

## Android/OEM и энергопотребление

Добавление плитки/виджета и переход в стандартные настройки происходят по нажатию
пользователя. На Android 13+ доступен системный запрос добавления плитки, на Android
12 — ручное добавление; закрепление виджета зависит от лаунчера. Battery Saver и
фоновое ограничение только читаются. Приложение не запрашивает blanket battery
exemption или повышенной минимальной версии Android. Для необязательных Live Updates на Android 16 используется обычное, не runtime-разрешение `POST_PROMOTED_NOTIFICATIONS`.

Подсказки HyperOS/MIUI, One UI, ColorOS/OxygenOS/realme UI — справочные, не
подтверждение совместимости или проверки всех ROM. Нет частной интеграции
Super Island/HyperIsland. Live Updates выключены по умолчанию, запрашиваются стандартным API только для активного таймера и учитывают скрытие пользователем; Android и OEM могут отказать в продвижении. Системные обновления
событийные; уменьшение повторных публикаций не доказывает измеренную экономию батареи.

## Приватность исходников и сообщений об ошибках

В Git и source archives не должны попадать личные `.otmbackup` **или `.otmplain`**,
CSV с реальными записями, ключи, пароли, environment credentials, внутренние планы
или инструкции агентов. `.gitignore` и repository policy отвергают оба backup-расширения
без учёта регистра; это не замена ручной проверке истории и release assets.
Публичные fixtures должны быть синтетическими и не использовать личный backup.

Policy также сопоставляет string/plurals/string-array ресурсы всех tracked XML в
`values` и `values-ru`, проверяет повторные имена и аргументы форматирования.
Она не требует перевода `translatable="false"` или парного файла темы и не
доказывает качество перевода, доступность UI или отсутствие уязвимостей.

Независимый криптографический аудит, подтверждённые battery/performance benchmarks
и проверка всех OEM не заявляются. Конкретные результаты сборки, подписи и
обновления перечисляются только в metadata окончательного выпуска.
Об уязвимости сообщайте приватно по [SECURITY.md](../SECURITY.md), без реальных backup-файлов.

## English summary

The app requests no network, location, broad-storage, advertising, analytics, or
accessibility-service capability. Local Room/DataStore data relies on Android
sandbox and device-storage protection, not separate database encryption.
Automatic platform backup is disabled. A document provider you explicitly select
may still transfer files to the cloud. Immutable, explicit system actions carry
entry/revision/generation identity; elapsed work uses a durable monotonic anchor.

**Default encrypted `.otmbackup` remains OTMB1:** PBKDF2-HMAC-SHA256 (310,000
iterations), AES-256-GCM, and an authenticated header. New passwords require at
least 12 Unicode code points and at most 1024 UTF-8 bytes, without trimming or
normalization. Existing short-password backups remain readable. Forgotten
passwords cannot be recovered. GCM integrity is not public proof of the author's identity.

**Optional `.otmplain` is OTMP1 plaintext, not encrypted or authenticated.** Anyone
holding it can read and alter records and included settings. SHA-256 detects
accidental damage, not malicious editing: an attacker can recompute it. Separate
creation and untrusted-import warnings are required. Structural preview is not
authenticity verification. Bad passwords, malformed input, or export errors must
not silently downgrade to plaintext.

Both formats write the same strict schema v2 and enforce the same Android limits:
4 MiB and 20,000 projects, entries, and segments each. Core ceilings are larger,
not a promise of Android acceptance. Copies contain confirmed closed intervals;
restore never auto-starts a timer. Language and the default-project preference
remain local, outside portable preferences/schema. Privacy mode hides system
project labels; it does not encrypt files. CSV is plaintext and not restorable.

Replace permanently replaces local history and portable settings after confirmation.
Room rollback prevents partial failure, not regret after success. Restore is blocked
for running, paused, or review sessions. Retryable previews live only in process
memory; explicit cancellation or process death discards them. Portable settings
converge through a durable post-commit journal; there is no Room/DataStore atomicity
claim. Export first converges the journal and takes a strict serialized snapshot.

Native controls require explicit taps and platform/launcher support. Battery and
background-restriction status is read-only. OEM hints are advisory, with no private
Super Island/HyperIsland API or required battery exemption. Optional Android 16
Live Updates are off by default, use the normal non-runtime
`POST_PROMOTED_NOTIFICATIONS` permission, and respect per-session dismissal.
Android and the OEM decide promotion. The minimum SDK is unchanged; battery improvement is unmeasured.

Do not publish personal `.otmbackup`, `.otmplain`, CSV, credentials, private keys,
or internal plans in Git, issues, CI artifacts, or source archives. Repository
checks reject both backup suffixes and validate aggregated RU/EN text resources;
they are not a security audit. Final release metadata, not these docs, establishes
signing and actual tests. Report vulnerabilities privately through [SECURITY.md](../SECURITY.md).
