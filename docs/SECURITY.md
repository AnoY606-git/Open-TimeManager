# Безопасность и приватность / Security and privacy

[Русский](#русский) · [English](#english) · [Сообщить об уязвимости](../SECURITY.md)

## Русский

### Где хранятся данные

Проекты, заметки, история и настройки находятся во внутреннем хранилище Android.
Приложение не запрашивает доступ к интернету, геолокации или всем файлам устройства.
Аккаунтов, рекламы и аналитики нет.

Локальную базу защищают изоляция приложения и средства защиты самого Android.
Отдельного шифрования базы паролем в Open TimeManager нет. Блокировка экрана
и обновления системы важны; приложение не защитит записи на скомпрометированном
или разблокированном устройстве, к которому получил доступ посторонний.

Автоматическое копирование и перенос данных средствами Android отключены.
Экспорт и импорт начинаются только по вашему действию через системное окно
выбора файла. Если выбрать облачное хранилище, данные передаст его провайдер,
даже при отсутствии доступа к сети у Open TimeManager.

### Что защищает копию

| Формат | Защита | Ограничение |
| --- | --- | --- |
| `.otmbackup` | Шифрование паролем и проверка изменения содержимого | Забытый пароль восстановить нельзя. Слабый или известный другим пароль снижает защиту. |
| `.otmplain` | Только проверка случайного повреждения | Любой получатель может читать и менять файл, в том числе пересчитать контрольную сумму. |
| CSV | Нет шифрования | Названия проектов и заметки читаются как обычный текст. Файл не подходит для восстановления. |

Зашифрованная копия — вариант по умолчанию. Она использует AES-256-GCM и
PBKDF2-HMAC-SHA256 с 310 000 итераций. Для каждого файла создаются случайные
соль и nonce; заголовок тоже защищён от изменения.

Новый пароль должен содержать от 12 Unicode-символов до 1024 байт UTF-8.
Используйте длинную уникальную фразу, а не только минимальную длину. Пробелы
не удаляются, пароль не преобразуется. Старые короткие пароли остаются совместимыми.
Неверный пароль и повреждённая зашифрованная копия дают одинаковую ошибку.
Приложение не пытается обойти её открытым форматом.

Шифрование не подтверждает личность автора: знающий пароль может создать
другую корректную копию. Проверка открытого файла подтверждает только структуру,
не его происхождение. Подробности контейнеров — в [описании формата](BACKUP_FORMAT.md).

### Что происходит при восстановлении

Файл проверяется до показа предпросмотра: формат, размер, структура и ссылки
между записями. Для Android действуют пределы 4 МиБ и 20 000 проектов, записей
и интервалов каждого вида. Оба формата сохраняют уже закрытые интервалы,
а не текущий открытый отрезок. Импорт не запускает таймер автоматически.

**«Объединить»** сохраняет текущие данные и отклоняет неоднозначные конфликты.
**«Заменить»** удаляет текущие проекты и историю после подтверждения.
Сбой до завершения транзакции базы откатывает изменения; успешную замену
отменить нельзя. Сначала сохраните и проверьте отдельную зашифрованную копию.
Восстановление блокируется, пока таймер работает, на паузе или требует проверки.

База и настройки сохраняются разными механизмами. Если база уже восстановлена,
а настройки применились не полностью, приложение сообщает об этом и повторяет
незавершённую работу после запуска. Повторять замену базы не нужно.
Пока скрытие названий не применилось, они могут оставаться видны в системных
элементах. Более поздний ручной выбор настроек не должен затираться старым импортом.

Пароль и предпросмотр не записываются на диск или в состояние восстановления
экрана. Предпросмотр временно остаётся в памяти после «Скрыть», но удаляется
при отмене или завершении процесса. Приложение очищает собственные временные
буферы; полное уничтожение всех внутренних копий памяти JVM не гарантируется.

### Уведомления и фоновые функции

Скрытие названий проектов относится только к уведомлению, виджету и плитке.
Оно не шифрует данные. Системные команды привязаны к записи и её состоянию,
чтобы старое нажатие не изменило новую сессию.

Добавление виджета и плитки требует действия пользователя и поддержки системы.
Приложение только читает признаки экономии батареи и ограничений фона; оно не
требует отключать оптимизацию. Подсказки для разных прошивок не означают,
что каждая модель была протестирована.

Live Updates на Android 16 выключены по умолчанию. Для них используется обычное,
не запрашиваемое отдельным диалогом разрешение `POST_PROMOTED_NOTIFICATIONS`.
Полный API зависит от обновления 36.1; Android и производитель решают, выделять
ли уведомление. Закрытых интеграций Super Island/HyperIsland нет.
Экономия батареи не измерялась, независимый криптографический аудит не проводился.

### Сообщения об ошибках и публичные файлы

Не прикладывайте личные копии, CSV, пароли или ключи к публичным обсуждениям.
Для примера создайте вымышленные проекты и заметки. Проверки репозитория
отсеивают известные приватные файлы, но не заменяют внимательный просмотр перед
публикацией. Уязвимости отправляйте через [приватный канал](../SECURITY.md).

## English

### Storage and network access

Projects, notes, history, and settings use Android's internal app storage.
There is no internet, location, or broad file-access permission, and no account,
advertising, or analytics.

The database relies on Android's app isolation and device protection, not a
separate app password. Keep your screen locked and your system updated. The app
cannot protect records from someone with access to an unlocked or compromised device.

Android's automatic backup and device transfer are disabled. Imports and exports
use the system file picker after your action. A cloud provider you choose can
still upload the file; it has its own network access.

### Backup protection

- **`.otmbackup`** is encrypted and detects changes made without the password.
  It uses AES-256-GCM and PBKDF2-HMAC-SHA256 with 310,000 iterations, fresh salt
  and nonce, and a protected header. A forgotten password cannot be recovered.
- **`.otmplain`** is neither encrypted nor authenticated. Its checksum detects
  accidental damage, but anyone can edit the file and recalculate it.
- **CSV** is readable text for spreadsheets, not a restorable backup.

New passwords require at least 12 Unicode code points and at most 1024 UTF-8 bytes.
Use a long, unique passphrase; minimum length alone is not enough. Spaces are
preserved and text is not normalized. Older short-password files still open.
Wrong passwords and damaged encrypted files produce the same error, without a
plaintext fallback. Encryption does not prove authorship: anyone knowing the
password can create another valid copy.

### Restore behavior

Files are checked for format, size, structure, and valid record references before
preview. Android accepts up to 4 MiB and 20,000 projects, entries, and intervals
of each kind. Copies contain closed intervals, not the current open interval;
restore never starts a timer automatically.

Merge keeps local data and rejects conflicting versions of the same record.
**Replace removes current projects and history.** Save and check a separate
encrypted backup first. Database changes roll back if the transaction fails,
but a successful replacement cannot be undone. Restore is blocked for running,
paused, or review sessions.

The database and settings are separate stores. If records are restored but settings
are still pending, the app warns and retries the remaining work after startup.
Do not repeat Replace. Project labels may remain visible until their privacy
setting finishes applying. Later manual choices must not be overwritten by an older import.

Passwords and previews are not saved to disk or screen-restoration state. Hide
keeps a preview only in memory; cancellation or process death discards it.
The app clears its temporary buffers but cannot guarantee erasure of every
internal copy made by the JVM or cryptographic provider.

### System controls and reporting

Hiding project names changes the notification, widget, and tile, not encryption.
Old system actions carry session identity so they cannot affect a newer session.
Widget and tile placement need your action and system support. Battery and
background-restriction indicators are read-only; disabling all optimization is
not required. Manufacturer tips are not evidence of testing every device.

Android 16 Live Updates are off by default and use the normal, non-runtime
`POST_PROMOTED_NOTIFICATIONS` permission. The full API needs system update 36.1;
Android and the manufacturer decide presentation. No private Super Island or
HyperIsland API is used. Battery savings are unmeasured; no independent
cryptographic audit has been performed.

Use made-up records in public reports. Never attach personal backups, CSV,
passwords, or keys. Report vulnerabilities through the
[private security channel](../SECURITY.md#english).
