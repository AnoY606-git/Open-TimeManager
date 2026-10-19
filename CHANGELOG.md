# История версий / Changelog

Здесь — изменения, заметные при работе с приложением. Подробности отдельных
выпусков доступны на [странице релизов](https://github.com/AnoY606-git/Open-TimeManager/releases).

## В разработке / Unreleased

- Добавлены отдельные планы часов и минут для календарных месяцев, переходы стрелками и кнопки «Прошлый месяц» / «Текущий месяц». Баланс показывает разность подтверждённого времени и плана, недобор за законченный месяц или остаток до цели за текущий. Оценка по будням применяется только по выбору пользователя и не учитывает праздники. Планы остаются на устройстве, не входят в копии и не меняются при смене дневной цели.
- Уведомление показывает дневное время по всем проектам с текущим рабочим отрезком и прогрессом цели: системный сегментированный `ProgressStyle` на Android 16, обычная полоса на Android 12–15. Хронометр продолжает показывать текущую сессию; отчёты учитывают только закрытые подтверждённые интервалы. Live Updates остаются отдельной необязательной настройкой без гарантии выделенного отображения.
- На узких экранах с крупным шрифтом кнопки навигации располагаются в два ряда, длинные диалоги прокручиваются. Черновики форм и заметок сохраняются при пересоздании экрана и смене компоновки; отказ в необязательных уведомлениях больше не выдаётся за сбой таймера.
- В локальных и CI-сборках приложение называется OTM, а APK — `OTM.apk`. Идентификатор приложения и правила подписи не изменены; имена уже опубликованных APK сохранены.
- Переписана документация: короче README, понятнее руководство, отдельная страница сборки.
- Обновлены русская и английская галереи текущей ветки `main`, добавлены уведомление и ручной план месяца. Снимки dev-сборки сделаны 19 октября 2026 года; стабильный выпуск 1.1.0 остаётся отдельным.

**English:** per-month plans with signed balances, remaining time, calendar
navigation, and an opt-in Monday–Friday estimate without holidays. Plans are
local, excluded from backups, and unaffected by daily-goal changes. Notifications
show daily totals across all projects plus the running interval, using native
segmented progress on Android 16 and a standard bar on Android 12–15; the
chronometer remains session-based and reports count closed, confirmed intervals.
Live Updates remain optional and system-controlled. Two rows of navigation buttons,
scrollable dialogs, and restored form and note drafts improve small-window use;
blocked optional notifications no longer appear as timer failures. Current local
and CI builds use OTM and `OTM.apk`, with the application ID and signing rules
unchanged. Updated bilingual documentation and development screenshots dated
19 October 2026, including notifications and monthly plans. The stable release
remains 1.1.0.

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
