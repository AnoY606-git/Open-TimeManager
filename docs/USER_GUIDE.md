# Руководство пользователя / User guide

[Русский](#русский) · [English](#english)

Руководство для 1.1.0 / Guide for 1.1.0. [Статус выпуска / Release status](releases/1.1.0.md).
В приложении: «Настройки → Как работать с приложением» / In the app: Settings → How to use the app.
Встроенный поиск по десяти темам работает офлайн на русском и английском.

## Русский

### 1. Первая рабочая сессия и настройки

1. Откройте «Таймер». Выберите «Без проекта» или создайте проект с именем и цветом.
2. Нажмите «Начать». Пауза закрывает текущий рабочий отрезок; «Продолжить» открывает следующий. «Завершить» переносит запись в историю.
3. Заметку активной записи сохраните перед выходом из редактора. Цель на день — визуальный ориентир, не будильник и не автоматическая остановка.

Нижняя навигация открывает Таймер, Историю, Отчёты и Настройки; на широком экране
она меняет расположение. Настройки — обзор с пятью отдельными экранами:

| Экран | Назначение |
| --- | --- |
| Оформление | Системная, светлая или тёмная тема, цвета устройства, язык. |
| Учёт и проекты | Проект по умолчанию, дневная цель, приватность и системные кнопки. |
| Копии данных | Зашифрованная или открытая копия, проверка файла и восстановление. |
| Как работать с приложением | Офлайн-помощь с поиском по заголовкам и тексту. |
| О приложении | Версия, приватность, лицензия и сторонние notices. |

Цвета устройства (Material You) доступны на Android 12+, с учётом возможностей
системы. На Android 13+ язык можно выбрать также в системных настройках приложения;
на Android 12/12L используйте выбор языка внутри приложения. Крупный шрифт,
альбомная ориентация и размер окна могут менять компоновку.

### 2. Проекты и проект по умолчанию

Проект выбирается на Таймере. Переключение во время запущенной или приостановленной
сессии завершает прежнюю запись и начинает новую; два таймера одновременно не
работают. Списки и поиск проектов загружаются порциями. Архивирование сохраняет
историю, но исключает проект из новых сессий. Сначала завершите или проверьте
активную запись этого проекта; зарезервированный «Без проекта» не архивируется.

В «Настройки → Учёт и проекты» можно задать проект по умолчанию. Приоритет выбора:

1. Проект активной сессии.
2. Явный выбор пользователя на свободном таймере.
3. Существующий неархивный проект по умолчанию.
4. Безопасный автоматический выбор доступного проекта.

Предпочтение не переключает текущую сессию. Оно хранится **только на этом устройстве**,
не входит в переносимые настройки и не экспортируется ни в один backup. Восстановление
переносимых настроек не заменяет его. Если проект исчез или архивирован, предпочтение
игнорируется; выберите другой либо «Автоматический выбор».

Для быстрого запуска из виджета или плитки используется действующий проект по
умолчанию. Без него старт возможен только при **ровно одном** подходящем проекте;
иначе открывается приложение для явного выбора. Это правило отличается от
предварительного выбора на экране Таймера.

### 3. Уведомление, виджет и плитка

- Уведомление даёт команды паузы, продолжения и завершения активной сессии. Если
  уведомления запрещены, учёт внутри приложения остаётся доступен. Откройте их
  настройки с Таймера или из раздела «Учёт и проекты».
- На Android 13+ кнопка добавления плитки вызывает системный запрос. Пользователь
  может отказаться; приложение не добавляет плитку скрыто. На Android 12/12L или
  при недоступном диалоге откройте редактор быстрых настроек и добавьте Open TimeManager вручную.
- Кнопка добавления виджета отправляет запрос поддерживающему лаунчеру. Подтвердите
  размещение; при отсутствии поддержки откройте обычный список виджетов лаунчера.
  Предпросмотр, размеры в ячейках и изменение размера зависят от лаунчера, а не
  гарантируют конкретное расположение на каждом устройстве.
- «Скрывать названия проектов» меняет только системное отображение, не шифрует
  базу или файлы. На паузе показывается фиксированное подтверждённое время;
  запущенный счётчик рисует сама система, без фоновой записи каждой секунды.

Если кнопка устарела, откройте приложение и проверьте текущее состояние. Старые
команды не должны менять новую сессию. Доступность системных поверхностей зависит
от Android, разрешений и ограничений производителя.

### 4. История и ручные записи

История содержит завершённые записи. Поиск работает по проекту или заметке;
«Загрузить ещё» открывает старые страницы. «Добавить запись» позволяет указать
локальную дату, время начала, длительность и проект для работы без таймера.
Выбор проекта в редакторе имеет собственные поиск и страницы.

Изменение только заметки или проекта сохраняет точные исходные интервалы.
Изменение времени заменяет их введённой длительностью. Архивный проект существующей
записи остаётся доступен при редактировании. Удаление после подтверждения необратимо.
Если данные изменились после открытия формы, не пытайтесь принудительно сохранить
старый вариант: откройте актуальную запись заново.

### 5. Отчёты и CSV

Отчёты показывают подтверждённые интервалы за неделю, месяц или свой период до
10 лет. Границы периода учитывают текущий часовой пояс. Открытый отрезок становится
подтверждённым при паузе, завершении или смене проекта, поэтому живой счётчик Таймера
может опережать итог отчёта. Цветные доли показывают фактическое время, не прогноз.

CSV выбранного периода сохраняется через системный выбор файла. Это читаемый
текст для таблиц; он может содержать имена проектов и заметки. **CSV не восстанавливается
в приложение и не заменяет backup.** Храните чувствительные экспорты отдельно от публичных файлов.

### 6. Создание и защита копии

Сначала поставьте таймер на паузу или завершите: копия сохраняет подтверждённые
закрытые интервалы, а не текущий открытый отрезок. В «Копии данных» есть
два разных действия:

| Формат | Что защищает | Что важно |
| --- | --- | --- |
| `.otmbackup` — основное действие | `OTMB1`, AES-256-GCM с паролем; прежний формат не меняется. | Новый пароль: от 12 Unicode code points до 1024 байт UTF-8, без обрезки пробелов и нормализации. |
| `.otmplain` — отдельный выбор с предупреждением | `OTMP1`, **без шифрования и защиты подлинности**. | Любой получатель может читать и менять проекты, заметки, историю и включённые настройки. |

Используйте уникальную длинную парольную фразу и храните её отдельно: восстановить
пароль нельзя. Старые зашифрованные копии с короткими паролями по-прежнему читаются.
Ошибка пароля или повреждение не переключают файл в открытый формат.

Оба формата записывают payload schema v2 и проходят строгую проверку структуры и
лимитов: в Android до **4 МиБ**, до **20 000 проектов, записей и сегментов каждого вида**.
В них входят метаданные проектов, тема, цвета устройства, приватность и дневная
цель; язык и локальный проект по умолчанию не переносятся. Schema v1 старых
зашифрованных копий читается с безопасными значениями по умолчанию.

Проверяйте отдельную сохранённую копию через просмотр восстановления. SHA-256 в
`.otmplain` обнаруживает случайное повреждение, но его можно пересчитать после
подделки: это не подпись. Выбранный системный файловый провайдер может сохранять
в облако. При ошибке удаления частичного файла следуйте предупреждению и удалите
его вручную; очищать данные приложения для этого не нужно.

### 7. Проверка, объединение и замена

1. Откройте файл через системный выбор. Для `.otmbackup` нужен точный пароль,
   включая пробелы. Для `.otmplain` пароля нет: подтвердите предупреждение и
   используйте только доверенный источник.
2. Предпросмотр показывает проверенную структуру, счётчики и конфликты UUID, но
   **не доказывает происхождение открытого файла**. До применения импортируемые
   записи не записываются в вашу историю.
3. «Объединить» сохраняет локальные записи и добавляет новые. Разное содержимое
   одного UUID блокирует импорт; конфликты проверяются заново при повторе.
   Зарезервированный «Без проекта» считается одной canonical-сущностью.
4. «Заменить» после дополнительного подтверждения безвозвратно заменяет локальную
   историю и переносимые настройки. Сначала создайте отдельную проверенную копию.
   Сбой или отмена транзакции Room откатывает импорт, но успешная замена не имеет Undo.

Восстановление запрещено при запущенном, приостановленном или требующем проверки
таймере. «Скрыть» оставляет предпросмотр в памяти процесса: завершите или проверьте
таймер и вернитесь через «Продолжить восстановление». «Отменить восстановление»
удаляет предпросмотр; после завершения процесса Android файл тоже нужно открыть заново.
Импорт не запускает активный таймер автоматически.

Room и DataStore — разные хранилища, без общей атомарной транзакции. Устойчивый
журнал доводит переносимые настройки после Room-коммита и повторяет незавершённую
запись после запуска. Более поздний ручной выбор не должен затираться старым
восстановлением. Не повторяйте разрушительную замену только ради обновления
настроек; проверьте предупреждение и текущее состояние.

### 8. Прерванный таймер и перезагрузка

Длительность рассчитывается по сохранённой монотонной опоре, а не по числу
секундных пробуждений. Закрытие экрана или пересоздание процесса само по себе не
означает остановку записи. После перезагрузки или потери доверенной опоры
неподтверждённое время может быть неизвестно.

В состоянии «Нужна проверка» откройте восстановление таймера. Оставьте только
подтверждённое время либо задайте окончание, которое можете проверить. Приложение
не придумывает пропущенные рабочие часы. После разрешения ситуации можно начинать новую сессию.

### 9. Батарея и ограничения устройства

Для подсчёта секунд не нужны wake lock, точный alarm или постоянная фоновая запись.
Системные проекции обновляются по событиям; повторные одинаковые публикации
отфильтровываются, изменение размера статичного виджета не перечитывает Room.
**Выигрыш батареи не измерен** и не выражается в процентах или гарантированных часах.

В разделе системных кнопок приложение только читает признаки Battery Saver и
ограничения фоновой работы; оно не меняет их. Если управление перестало обновляться,
откройте приложение и его обычную системную страницу настроек. Не отключайте все
защиты батареи заранее: обязательное исключение из оптимизации не требуется.

- **Xiaomi / Redmi / POCO, HyperOS / MIUI:** проверьте уведомления и батарею;
  в отдельных версиях есть автозапуск. Меню зависят от модели, региона и версии.
- **Samsung / One UI:** проверьте настройки батареи и списки спящих/глубоко спящих приложений.
- **OPPO / OnePlus / realme, ColorOS / OxygenOS / realme UI:** возможны собственные
  ограничения фоновой активности и автозапуска.

Это подсказки, не подтверждение тестирования конкретной прошивки. Частные API
Super Island/HyperIsland не используются. На Android 16 можно отдельно включить
Live Updates в «Учёт и проекты»: приложение запросит заметный системный статус
запущенного таймера. По умолчанию функция выключена; Android и оболочка могут
отказать в продвижении. После скрытия пользователем сессия повторно не продвигается.
Не меняйте больше настроек, чем нужно для конкретной проблемы.

### 10. Приватность и безопасное обновление

Нет `INTERNET`, аккаунта, рекламы или аналитики. Данные — во внутреннем хранилище
Android; автоматический platform backup отключён. Локальная база не имеет
отдельного шифрования приложения: важны блокировка и защита хранилища устройства.
Режим приватности системных поверхностей не защищает содержимое файлов.

Перед обновлением создайте и проверьте отдельную **зашифрованную** копию.
Совместимо подписанный APK с большим `versionCode` обновляется без очистки данных.
Для 1.1 ожидается production-ключ 1.0, но результат сравнения сертификатов и
проверки обновления должен быть в итоговых release assets. При несовпадении
подписи остановитесь: **не удаляйте приложение и не очищайте данные** ради установки.
Preview/debug может иметь другой ключ. [Статус и metadata выпуска](releases/1.1.0.md).

## English

### 1. Your first session and Settings

1. Open Timer. Choose No project, or create a project with a name and color.
2. Tap Start. Pause closes the current interval; Resume opens another. Stop finishes the entry and sends it to History.
3. Save an active note before leaving its editor. The daily goal is a visual target, not an alarm or an automatic stop.

The main destinations are Timer, History, Reports, and Settings; navigation adapts
to the window. Settings is an overview opening five focused screens:

| Screen | Purpose |
| --- | --- |
| Appearance | System/light/dark theme, device colors, and language. |
| Tracking and projects | Local default project, daily goal, privacy, and system controls. |
| Backups and restore | Encrypted or plaintext copies, file inspection, and restore. |
| Help — How to use the app | Offline search across ten topic titles and bodies. |
| About | Version, privacy, license, and third-party notices. |

Material You device colors are available on Android 12+, subject to system support.
Android 13+ also offers app language in system settings; on Android 12/12L use the
in-app language selector. Large fonts, landscape, and window size can change the layout.

### 2. Projects and the default project

Choose a project on Timer. Switching during a running or paused session finishes
the previous entry and starts a new one; two timers cannot run together. Project
lists and search use bounded pages. Archiving retains history but excludes the
project from new sessions. First finish or resolve its active entry; the reserved
No project row cannot be archived.

Settings → Tracking and projects can set a default project. Selection priority is:

1. The active session's project.
2. Your explicit selection while Timer is idle.
3. A valid, non-archived preferred project.
4. A safe automatic fallback from available projects.

The preference never switches an active session. It stays **local to this device**,
outside portable preferences and both backup formats; restoring portable settings
preserves it. Missing or archived projects are ignored. Choose another project or
Automatic selection to clear the override.

Widget/tile quick Start uses a valid preferred project. Without one, quick Start
requires **exactly one** eligible project; otherwise the app opens for selection.
This is different from the in-app Timer's preliminary fallback selection.

### 3. Notification, widget, and Quick Settings

- The notification offers Pause, Resume, and Stop for an active session. If
  notifications are blocked, in-app tracking remains available. Open their
  settings from Timer or Tracking and projects.
- On Android 13+, Add Quick Settings tile requests the system dialog. You may
  decline; the app does not silently add a tile. On Android 12/12L, or if the
  dialog is unavailable, use the Quick Settings editor to add Open TimeManager manually.
- Add home-screen widget requests pinning from a supported launcher. Confirm
  placement; otherwise use the launcher's widget picker. Preview, target cell
  dimensions, and resizing depend on the launcher, not a universal placement guarantee.
- Hide project names affects system presentation, not database or file encryption.
  Paused controls show fixed confirmed time. Running counters use host chronometers,
  not an app database write every second.

If a control looks stale, reopen the app and check the current state. Old actions
must not change a newer session. Availability depends on Android, permissions, and
manufacturer restrictions.

### 4. History and manual entries

History contains completed entries. Search by project or note; Load more reaches
older pages. Add entry accepts a local date, start time, duration, and project for
work you did not time. Its project picker has independent search and pagination.

Editing only a note or project preserves precise original intervals. Changing
timing replaces them with the supplied duration. An existing entry's archived
project remains selectable. Confirmed deletion is permanent. If data changed
after opening a form, reopen the current entry instead of forcing stale changes.

### 5. Reports and CSV

Reports show confirmed intervals for a week, month, or custom range of at most
10 years, using the current time zone at period boundaries. A running interval
becomes confirmed when paused, stopped, or switched, so Timer's live counter may
lead the report. Project shares represent actual work, not forecasts.

Export the selected period through Android's document picker. CSV is readable
spreadsheet text and can contain project names and notes. **CSV cannot be restored
into the app and is not a complete backup.** Keep sensitive exports private.

### 6. Create and protect a copy

Pause or stop first: copies save confirmed, closed intervals, not the current open
interval. Backups and restore has two separate actions:

| Format | Protection | Important |
| --- | --- | --- |
| `.otmbackup` — the default | Unchanged `OTMB1`, password-based AES-256-GCM. | New passwords: at least 12 Unicode code points, at most 1024 UTF-8 bytes; no trimming or normalization. |
| `.otmplain` — explicit opt-in with a warning | `OTMP1`, **no encryption or authenticity protection**. | Anyone holding it can read and change projects, notes, history, and included settings. |

Use a long, unique passphrase and store it separately: it cannot be recovered.
Older encrypted copies retain short-password compatibility. A wrong password or
damaged encrypted file does not switch to a plaintext format.

Both write schema v2 payloads with strict structure and resource checks: Android
accepts at most **4 MiB** and **20,000 projects, entries, and segments each**. Copies
include project metadata, theme, device colors, privacy, and daily goal. Language
and the local default-project preference are not transferred. Old encrypted schema
v1 backups remain readable with safe defaults.

Verify a separately saved copy through inspection. The `.otmplain` SHA-256 checksum
detects accidental damage, but an editor can recompute it: it is not a signature.
Your chosen document provider may save to the cloud. If deleting an incomplete
file fails, follow the warning and remove that file manually; do not clear app data.

### 7. Inspect, Merge, and Replace

1. Choose the file through Android's picker. `.otmbackup` needs its exact password,
   including spaces. `.otmplain` has none: acknowledge the warning and use only a trusted source.
2. Preview shows checked structure, counts, and UUID conflicts, **not proof of who
   created a plaintext file**. Imported records do not enter History during inspection.
3. Merge preserves local records and adds new ones. Different contents under one
   UUID block import; conflicts are checked again on retry. The reserved No project
   row is one canonical entity across installations.
4. Replace permanently replaces local history and portable settings after an extra
   confirmation. First create a separate verified backup. Room rolls back a failed
   or cancelled transaction, but successful Replace has no Undo.

Restore is blocked while a timer is running, paused, or awaiting review. Hide for
now retains the preview in process memory; finish or resolve the timer, then use
Resume restore preview. Cancel restore discards it; if Android ends the process,
open the file again. Import never automatically starts an active timer.

Room and DataStore are separate, without one cross-store atomic transaction.
A durable journal converges portable settings after the Room commit and retries
unfinished work after startup. Later manual choices must not be overwritten by an
older restore. Do not repeat destructive Replace merely to refresh settings;
check the warning and current state first.

### 8. Interrupted timers and reboot

Duration comes from a saved monotonic anchor, not a count of second-by-second
wakeups. Closing the screen or recreating the process does not itself stop the
saved session. A reboot or untrustworthy reference can make unconfirmed time unknowable.

For Review required, open timer recovery. Keep only confirmed time, or provide
an end time you can verify. The app does not invent missing work hours. Once
resolved, you can start another session.

### 9. Battery and device restrictions

Counting seconds needs no wake lock, exact alarm, or continual background write.
System projections are event-driven; equal publications are filtered, and static
widget resizing does not reread Room. **Battery savings are unmeasured**: no percentage
or extra-hours claim is made.

The system-controls section only reads Battery Saver/background-restriction
status; it does not change either. If controls stop updating, reopen the app and
its ordinary system settings page. Keep normal protections unless troubleshooting
a specific problem: the app does not require a blanket optimization exemption.

- **Xiaomi / Redmi / POCO, HyperOS / MIUI:** review notifications and battery;
  some versions expose background autostart. Menus vary by model, region, and version.
- **Samsung / One UI:** review battery settings and Sleeping/Deep sleeping apps.
- **OPPO / OnePlus / realme, ColorOS / OxygenOS / realme UI:** background activity
  and autolaunch can have additional restrictions.

These are advisory hints, not evidence of testing a particular ROM. No private
Super Island/HyperIsland API is used. Android 16 Live Updates can be enabled
separately under Tracking and projects to request a prominent status for a running
timer. They are off by default; Android and the OEM decide promotion. A dismissed
session is not promoted again. Change only what is necessary for the specific problem.

### 10. Privacy and safe updates

No `INTERNET`, account, advertising, or analytics. Data uses Android internal
storage; automatic platform backup is disabled. The local database is not
independently encrypted by the app, so device lock/storage protection matters.
System-surface privacy does not protect file contents.

Create and verify a separate **encrypted** backup before updating. A compatibly
signed APK with a greater version code updates without clearing data. Version 1.1
is intended to use 1.0's production key; final release assets must record certificate
comparison and the actual update check. If signatures differ, stop: **do not
uninstall or clear app data** to force installation. Preview/debug may have another
key. See [release status and metadata](releases/1.1.0.md).

## Официальные справочные материалы / Official references

These explain Android APIs and design guidance, not Open TimeManager test results.
No third-party code or images are reproduced here.

- [Material You / dynamic color](https://developer.android.com/develop/ui/views/theming/dynamic-colors)
- [Per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages)
- [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles)
- [App widgets](https://developer.android.com/develop/ui/views/appwidgets/overview) · [widget enhancements and pinning](https://developer.android.com/develop/ui/views/appwidgets/enhance)
- [Application-details system settings](https://developer.android.com/reference/android/provider/Settings#ACTION_APPLICATION_DETAILS_SETTINGS)
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

[Security / Безопасность](SECURITY.md) · [Release checklist / Выпуск](RELEASING.md)
