# История версий / Changelog

Здесь — изменения, заметные при работе с приложением. Подробности отдельных
выпусков доступны на [странице релизов](https://github.com/AnoY606-git/Open-TimeManager/releases).

## В разработке / Unreleased

- Формы и подтверждения теперь прокручиваются целиком, включая заголовок и действия: крупный шрифт и короткое окно с клавиатурой не вытесняют поля ввода. Компактный виджет получил отдельные кнопки-иконки 48 dp с полными русскими/английскими названиями для средств доступности; длинное имя проекта больше не выталкивает таймер и управление за границы.
- Добавлен необязательный мастер первого запуска на русском и английском: Android и семейство устройства, проверка уведомлений и фоновых ограничений, явные действия и возможность пропустить. Пропуск и завершение запоминаются локально без повторных показов; мастер доступен вручную в «Android и устройство». Разрешения и Live Updates не включаются автоматически, точная прошивка не угадывается по бренду.
- Нижняя навигация временно скрывается при экранной клавиатуре, освобождая место для ввода. Поиск настроек удерживает строку поиска, а не большой заголовок, в видимой области; это особенно важно на узких экранах с крупным шрифтом.
- Обзор настроек стал компактнее; добавлены поиск разделов по реальным названиям настроек, действий и ключевым словам, очистка запроса и сообщение об отсутствии совпадений. Запрос и прокрутка обзора сохраняются после возврата и восстановления экрана. Новый раздел «Android и устройство» после «Копий данных» объединяет уведомления, виджет, плитку, ограничения фона и «Live Updates и HyperIsland». Сведения о совместимости и прошивках изначально свёрнуты; основные кнопки и действующие предупреждения остаются видны. Проект по умолчанию, дневная цель и скрытие названий остаются в «Учёт и проекты»; поиск и переходы сами не меняют данные или значения настроек.
- Добавлены отдельные планы часов и минут для календарных месяцев, переходы стрелками и кнопки «Прошлый месяц» / «Текущий месяц». Баланс показывает разность подтверждённого времени и плана, недобор за законченный месяц или остаток до цели за текущий. Оценка по будням применяется только по выбору пользователя и не учитывает праздники. Планы остаются на устройстве, не входят в копии и не меняются при смене дневной цели.
- Уведомление показывает дневное время по всем проектам с текущим рабочим отрезком и прогрессом цели: системный сегментированный `ProgressStyle` на Android 16, обычная полоса на Android 12–15. Хронометр продолжает показывать текущую сессию; отчёты учитывают только закрытые подтверждённые интервалы.
- Необязательный локальный выбор теперь называется «Live Updates и HyperIsland» и остаётся выключенным по умолчанию. Android 16 запрашивает продвижение только для работающего таймера; при обнаружении протокола HyperOS 3, в том числе на Android 15, экспериментальный клиент описывает работу и паузу. Подготовлены широкая капсула со значком и временем, миниатюрный слот со значком и раскрытый шаблон со временем, дневным текстом и двумя кнопками без отдельной полосы. Нативный отсчёт HyperOS и вид на устройстве не проверены; регистрация сервиса Xiaomi, app ID, привязка сертификатов и допуск сценария ещё не получены. [Подробнее](docs/USER_GUIDE.md#live-updates-и-hyperisland).
- В JSON острова не передаются проекты, заметки или идентификаторы записей; при блокировке или выключении экрана OEM-данные снимаются. Для AOD подготовлен только статус. Срок HyperIsland ограничен 12 часами от UTC-начала записи, включая паузы; продолжение и повторная публикация его не продлевают. При неизвестном или будущем начале и в последние неполные 60 секунд клиент оставляет обычное уведомление. Оценка срока и поведение OEM физически не проверены. Новые SDK, сетевой доступ, разрешения и изменения схемы базы не добавлены. Совместимость и приватность OEM-поверхностей не сертифицированы.
- На узких экранах с крупным шрифтом кнопки навигации располагаются в два ряда, длинные диалоги прокручиваются. Черновики форм и заметок сохраняются при пересоздании экрана и смене компоновки; отказ в необязательных уведомлениях больше не выдаётся за сбой таймера.
- В локальных и CI-сборках приложение называется OTM, а APK — `OTM.apk`. Идентификатор приложения и правила подписи не изменены; имена уже опубликованных APK сохранены.
- Переписана документация: короче README, понятнее руководство, отдельная страница сборки.
- Обновлены русская и английская галереи текущей ветки `main`, добавлены уведомление и ручной план месяца. Снимки dev-сборки сделаны 19 октября 2026 года; стабильный выпуск 1.1.0 остаётся отдельным.

**English:** optional first-run setup in Russian and English shows Android and
device-family information, notification and background-restriction status,
explicit actions and Skip. Dismissal/completion is local and prevents repeated
prompts; setup can be reopened under Android and device. Permissions and Live
Updates are never enabled automatically, and the brand is not treated as proof
of the exact firmware.

Forms and confirmations now scroll as a whole, including their headings and
actions, so large text and short keyboard windows do not displace input fields.
The compact widget uses separate 48 dp icon controls with full localized
accessibility labels; long project names no longer push the timer or controls
outside the widget.

Per-month plans with signed balances, remaining time, calendar
navigation, and an opt-in Monday–Friday estimate without holidays. Plans are
local, excluded from backups, and unaffected by daily-goal changes. Notifications
show daily totals across all projects plus the running interval, using native
segmented progress on Android 16 and a standard bar on Android 12–15; the
chronometer remains session-based and reports count closed, confirmed intervals.
Two rows of navigation buttons,
scrollable dialogs, and restored form and note drafts improve small-window use;
blocked optional notifications no longer appear as timer failures. Current local
and CI builds use OTM and `OTM.apk`, with the application ID and signing rules
unchanged. Updated bilingual documentation and development screenshots dated
19 October 2026, including notifications and monthly plans. The stable release
remains 1.1.0.

Settings now has compact section rows, a search by real setting/action labels and keywords, query
clearing, and a no-results state. The query and overview scroll position survive
return and screen restoration. Android and device follows Backups and restore and
groups notifications, the widget, the tile, background restrictions, and Live
Updates and HyperIsland. Compatibility advice starts collapsed; primary buttons
and active warnings remain visible. Tracking and projects keeps the default
project, daily goal, and hidden-label preference. Searching and navigating do not
change records or preference values.

Bottom navigation temporarily hides while the software keyboard is open. Settings
search keeps the search field rather than the large heading in view, improving
narrow-window use with large text.

The local preference is now **Live Updates and HyperIsland**, still off by default.
Android 16 requests promotion only while running. When the HyperOS 3 protocol is
detected, including on Android 15, an experimental client describes running and
paused timers: icon and time in the wide pill, icon only in the mini slot, and
time, daily-total text, and two buttons in the expanded template without a separate
bar. Xiaomi registration, an app ID, signing certificates, and scenario approval
have not been obtained; native timing and physical HyperOS rendering are unverified.
Island JSON excludes projects, notes, and record identifiers; locking or screen-off
removes OEM data, and prepared AOD is status only. HyperIsland lifetime is limited
to 12 hours from the entry's UTC start, including pauses; resume and repost do not
extend it. An unknown or future start or a final incomplete 60 seconds keeps the
ordinary notification. Lifetime estimates and OEM timeout behavior are not
physically verified. No new SDK, network access, permissions, or database
schema changes were added. OEM compatibility and privacy are not certified.
[Details](docs/USER_GUIDE.md#live-updates-and-hyperisland).

## 1.1.0 — 18 октября 2026

### Новое

- Настройки разделены на пять страниц; добавлено офлайн-руководство с поиском на русском и английском.
- Можно выбрать проект по умолчанию для таймера, виджета и плитки.
- Добавлены копии без пароля `.otmplain` с явным предупреждением. Зашифрованные `.otmbackup` остаются основным вариантом.
- Добавлять плитку и виджет стало проще; появились переходы в настройки Android и подсказки для разных прошивок.
- На Android 16 можно включить запрос Live Updates для работающего таймера. Функция выключена по умолчанию, вид уведомления определяет система.
- Обновлены все скриншоты и добавлены примеры новых экранов.

### Исправления

- Старые диалоги истории больше не могут изменить данные, загруженные при замене из копии.
- Редактор корректно обрабатывает смену часового пояса; отчёты не показывают старые итоги под новым периодом.
- Настройки продолжают обновляться после временной ошибки чтения. Активный проект нельзя архивировать во время сессии.
- После импорта видно, если часть настроек ещё ожидает применения.
- На паузе время уведомления остаётся фиксированным. Убраны лишние повторные обновления системных элементов; экономия батареи не измерялась.

**English:** five focused Settings pages, searchable offline help, a local default
project, optional unprotected backups, easier system controls, and opt-in Android
16 Live Updates. Fixed stale history dialogs, time-zone editing, report loading,
settings recovery, active-project archiving, and paused notification timing.
Reduced repeated system updates; battery savings remain unmeasured.

[Подробнее / Release notes](docs/releases/1.1.0.md)

## 1.0.0 — 17 октября 2026

Первый стабильный выпуск.

- Обновлены таймер, история, отчёты и настройки: светлая и тёмная темы, новые иконки и адаптивная навигация.
- Улучшены отклик системных кнопок, восстановление из копий и экспорт CSV.
- Исправлено отображение выбранного языка в уведомлении, виджете и плитке на Android 12.
- Для новых зашифрованных копий установлен минимум в 12 символов пароля; старые копии остаются совместимыми.
- Добавлены инструкции для Windows, проверки APK и документы, необходимые для распространения.

**English:** the first stable release refreshed all four screens and themes,
improved system controls, restore, and CSV export, fixed Android 12 system-label
localization, and strengthened new backup password requirements. Existing backups
remain readable. Added Windows setup and release verification.

[Подробнее / Release notes](docs/releases/1.0.0.md)

## 0.1.0-preview — 15 октября 2026

Первая тестовая версия: локальный таймер, проекты и заметки, ручные записи,
история, отчёты и CSV. Добавлены зашифрованные копии, уведомление, виджет,
плитка, русский и английский интерфейсы. После перезагрузки неизвестное время
требует проверки. Данные и переносимые настройки восстанавливаются с обработкой
прерываний. Поддержка Android 12+, работа без аккаунта и сетевого разрешения.

**English:** initial preview with local tracking, projects, notes, manual entries,
history, reports, CSV, encrypted backups, and system controls. Russian and English
interfaces, Android 12+, reboot review, interruption-safe restore, no account or
network permission. Preview builds are no longer supported.
