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

В копии входят тема, цвета устройства, скрытие названий проектов и дневная
цель. Язык, проект по умолчанию, планы месяцев и выбор «Live Updates и HyperIsland»
хранятся только на устройстве и в копии не переносятся. Восстановление записей времени
не заменяет планы месяцев.

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
Оно убирает только название проекта: дневная сумма и цель остаются видны
в обычном уведомлении. Для показа без личных подробностей приложение передаёт
Android публичную версию только со статусом таймера; окончательное отображение
на экране блокировки определяют настройки Android. Эта настройка не шифрует
данные. Системные команды привязаны к записи и её состоянию, чтобы старое
нажатие не изменило новую сессию.

Добавление виджета и плитки требует действия пользователя и поддержки системы.
Приложение только читает признаки экономии батареи и ограничений фона; оно не
требует отключать оптимизацию. Подсказки для разных прошивок не означают,
что каждая модель была протестирована.

«Live Updates и HyperIsland» — один локальный выбор, выключенный по умолчанию
и исключённый из копий. На Android 16 продвижение запрашивается только во время
работы; новый API зависит от обновления 36.1. Используется уже объявленное обычное,
не запрашиваемое отдельным диалогом разрешение `POST_PROMOTED_NOTIFICATIONS`.
Android и производитель определяют допуск и отображение.
[Контракт Android](https://developer.android.com/develop/ui/views/notifications/live-update)

Экспериментальный клиент HyperOS 3 использует опубликованный локальный протокол
Xiaomi и описывает работающий таймер и паузу. Он не добавляет SDK, доступ к сети,
новые разрешения или изменения схемы базы. Доступность переключателя по протоколу,
в том числе на Android 15, не доказывает допуск приложения. Регистрация сервиса
Xiaomi, app ID, привязка сертификатов и согласование сценария ещё не получены;
физического тестирования HyperOS не было.
[Клиентский протокол](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131) ·
[Условия допуска Xiaomi](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)

JSON острова содержит только статус, время сессии, текст дневного итога и описание
кнопок; названия проектов, заметки и идентификаторы сессии или записи туда не
передаются независимо от настройки скрытия названий. Для AOD подготовлен только
статус, без времени и дневного итога. На заблокированном или выключенном экране
клиент не добавляет OEM-данные, а процессный наблюдатель пересобирает уведомление при событиях
экрана, чтобы снять уже добавленные данные. Если наблюдение за экраном недоступно,
построитель не публикует их. Наблюдатель живёт в процессе независимо от службы,
не опрашивает устройство и не читает Room. Снятие уже переданных данных выполняется
по возможности, пока работает процесс OTM: после его остановки или при задержке
системного события мгновенное удаление гарантировать нельзя.
[Ограничения доставки событий Android](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

Это граница OTM, а не сертификация
поведения SystemUI: неизвестно, как все версии HyperOS очищают свои поверхности
и применяют `publicVersion` к OEM-данным. Обычная публичная версия Android
не считается доказательством скрытия JSON Xiaomi.

Скрытие уведомления подавляет оба запроса для той же записи, включая паузу и
продолжение; явное выключение и включение опции сбрасывает скрытие. Срок острова
оценивается по сохранённому UTC-началу записи, включая паузы, без продления при
перепубликации; в последнюю неполную минуту до 12 часов остаётся обычный вид.
Неизвестное или будущее начало закрывает OEM-путь. Системные часы влияют на оценку;
фактический timeout прошивки не подтверждён. Обычное уведомление и учёт продолжаются.
Нативный отсчёт HyperOS, его возобновление,
lockscreen и AOD пока не проверены на устройстве. Экономия батареи не измерялась,
независимый криптографический аудит не проводился.

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

Backups include the theme, device colors, hidden project labels, and daily goal.
Language, the default-project choice, monthly plans, and the Live Updates and
HyperIsland preference stay on the device and are excluded from backup files.
Restoring time records does not replace monthly plans.

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

Hiding project names removes only project labels from the notification, widget,
and tile. Daily totals and the goal remain visible in the normal notification.
The app provides Android with a public version containing only the timer status;
Android settings decide what is shown on the lock screen. This preference does
not encrypt data.
Old system actions carry session identity so they cannot affect a newer session.
Widget and tile placement need your action and system support. Battery and
background-restriction indicators are read-only; disabling all optimization is
not required. Manufacturer tips are not evidence of testing every device.

Live Updates and HyperIsland share one local preference, off by default and
excluded from backups. Android 16 promotion is requested only while running;
the newer API depends on system update 36.1. It uses the already-declared normal,
non-runtime `POST_PROMOTED_NOTIFICATIONS` permission. Android and the manufacturer
decide eligibility and presentation.
[Android contract](https://developer.android.com/develop/ui/views/notifications/live-update)

The experimental HyperOS 3 client uses Xiaomi's published local protocol and
describes running and paused timers. It adds no SDK, network access, permissions,
or database schema changes. Enabling the switch after protocol detection, including
on Android 15, does not establish app approval. Xiaomi service registration, an app
ID, signing-certificate registration, and scenario approval have not been obtained;
there has been no physical HyperOS testing.
[Client protocol](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131) ·
[Xiaomi approval requirements](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132)

The island JSON contains only status, session time, daily-total text, and button
definitions. Project names, notes, and session or record identifiers never enter it,
regardless of the hidden-label setting. Prepared AOD content is status only,
without elapsed time or daily totals. The client omits OEM data while locked or
screen-off; a process-lifetime observer rebuilds the notification on screen events
to remove data already supplied, independently of foreground-service restarts.
It does not poll or read Room. If screen observation is unavailable, the builder
omits OEM data. Removal of already supplied data is best-effort while the OTM
process is running: process termination or delayed system events prevent a
guarantee of immediate removal.
[Android broadcast-delivery limits](https://developer.android.com/develop/background-work/background-tasks/broadcasts)

This is an OTM boundary, not certification of SystemUI: how every HyperOS version
clears its surfaces or applies `publicVersion` to OEM data is unknown. Android's
ordinary public version is not evidence of redaction for Xiaomi JSON.

Dismissing the notification suppresses both requests for that entry, including
pause and resume; explicitly turning the option off and on resets dismissal.
Island lifetime is estimated from the saved UTC entry start, including pauses,
without extension on repost. The final incomplete minute before 12 hours, an
unknown start or a future start keep only the ordinary notification. System-clock
changes affect the estimate; actual OEM timeout behavior is unverified. Ordinary
tracking continues. Native HyperOS timing, resume behavior,
the lock screen, and AOD remain unverified on a physical device. Battery savings
are unmeasured; no independent cryptographic audit has been performed.

Use made-up records in public reports. Never attach personal backups, CSV,
passwords, or keys. Report vulnerabilities through the
[private security channel](../SECURITY.md#english).
