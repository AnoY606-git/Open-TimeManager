# Руководство пользователя / User guide

[Русский](#русский) · [English](#english)

## Русский

### Таймер

1. Откройте «Таймер» и выберите «Без проекта» либо создайте проект.
2. Нажмите «Начать». Можно добавить заметку, поставить учёт на паузу, продолжить или завершить.
3. Переключение проекта закрывает прежний отрезок и начинает новый без двух одновременных таймеров.

При большом числе активных проектов список загружается порциями; нажмите «Загрузить ещё проекты», чтобы показать следующую порцию. Мобильный лимит — 20 000 проектов.

После перезагрузки или потери надёжной опоры времени запись переходит в «Нужна проверка». Оставьте только подтверждённое время или укажите проверенное время окончания.

### История, отчёты и системные кнопки

- В «Истории» есть поиск, ручная запись, редактирование, необратимое удаление и постраничная загрузка старых записей. В форме ручной записи поиск проектов и кнопка «Загрузить ещё проекты» доступны независимо от списка «Таймера»; при редактировании текущий проект остаётся доступен, даже если он уже архивирован.
- «Отчёты» агрегируют подтверждённые отрезки за неделю, месяц или свой период длительностью не более 10 лет. CSV сохраняется через системный выбор файла и не подходит для восстановления.
- Разрешение уведомлений включает foreground-уведомление. Плитка Quick Settings и виджет отражают состояние. При отказе учёт внутри приложения остаётся доступен. Privacy mode скрывает имя проекта на системных поверхностях.

### Копия и восстановление

1. В «Настройках» задайте пароль и сохраните `.otmbackup`. Пароль не хранится и не восстанавливается.
2. Импорт сначала показывает проверенные счётчики и конфликты UUID. Предпросмотр остаётся доступен для повторной попытки, если восстановление заблокировано таймером или лимитом, обнаружило конфликт либо было отменено.
3. «Объединить» сохраняет локальные записи и добавляет новые, но останавливается при разном содержимом одного UUID или при превышении итогового лимита 20 000 проектов. Зарезервированный проект «Без проекта» считается одной canonical-сущностью на разных установках; его локальная дата первичного создания не вызывает конфликт.
4. «Заменить» без Undo заменяет локальную историю после успешного подтверждения. Сначала сделайте и проверьте отдельную копию. Сбой или отмена внутри транзакции откатывает все таблицы Room, но успешная замена не отменяется.

Приложение отклоняет backup больше 4 МиБ и восстановление при живом или требующем проверки таймере. БД не меняется до полной проверки файла и предпросмотра. Текущий формат сохраняет цвета, даты создания/архивации проектов, тему, dynamic color, privacy mode и дневную цель. Язык следует системной локали Android и не переносится. Настройки хранятся отдельно от Room, поэтому между ними нет ложного обещания общей атомарной транзакции: после подтверждённой Room-транзакции небольшой журнал автоматически доводит настройки до согласованного состояния, в том числе после перезапуска.

## English

### Timer

1. Open Timer and select “No project”, or create a project.
2. Tap Start. You can add a note, pause, resume, or stop the session.
3. Switching projects closes the previous segment and starts a new one without allowing two concurrent timers.

When many active projects exist, the selector loads them in bounded pages; tap Load more projects to show the next page. The mobile limit is 20,000 projects.

After a reboot or an untrustworthy monotonic-time reference, the entry changes to Needs review. Keep only confirmed time or provide an end time you can verify.

### History, reports, and system controls

- History supports search, manual entries, editing, deletion, and bounded loading of older records. The manual-entry form has its own searchable, paged project picker; an entry's current project remains available while editing even if it has since been archived. Deletion has no Undo.
- Reports aggregate confirmed segments for a week, month, or a custom range of at most 10 years. CSV is saved through the system document picker and is for analysis, not restore.
- Notification permission enables foreground controls. The Quick Settings tile and widget reflect timer state. If notifications are denied, in-app tracking remains available. Privacy mode hides project names on system surfaces.

### Backup and restore

1. In Settings, choose a password and save an `.otmbackup` file. The password is neither stored nor recoverable.
2. Restore first displays verified counts and UUID conflicts. Its preview remains available to retry if restore is timer- or limit-blocked, conflicts, is cancelled, or rolls back.
3. Merge preserves local records and adds new records, but stops on different content with the same UUID or if the resulting database would exceed 20,000 projects. The reserved No project row is one canonical entity across installations; its local initial-creation timestamp does not cause a conflict.
4. Replace permanently replaces local history after successful confirmation. Create and verify a separate backup first. Failure or cancellation within the transaction rolls back every Room table; a successful replacement has no Undo.

The Android app rejects backups larger than 4 MiB and blocks restore while a timer is live or awaiting review. The database is unchanged until cryptographic integrity, schema, resource limits, and preview are all validated. The current format preserves project colors and creation/archive timestamps plus theme, dynamic color, privacy mode, and the daily goal. Language continues to follow the Android locale and is not transferred. Settings live outside Room, so there is no false cross-store atomicity claim: after the committed Room transaction, a small durable journal automatically converges settings, including after process restart.
