# История версий / Changelog

Здесь — изменения, заметные при работе с приложением. Подробности отдельных
выпусков доступны на [странице релизов](https://github.com/AnoY606-git/Open-TimeManager/releases).

## В разработке / Unreleased

- Переписана документация: короче README, понятнее руководство, отдельная страница сборки.
- Rewritten documentation with shorter READMEs, clearer help, and a dedicated build guide.

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
