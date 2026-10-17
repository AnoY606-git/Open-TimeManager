# Скриншоты / Screenshots

Это реальные кадры Open TimeManager `0.1.0-preview`, снятые через ADB с
**Android Emulator**, а не с личного телефона и не из макетов интерфейса.

## Условия съёмки

- Android 16, API 36, AOSP x86_64, профиль Pixel 5.
- Экран 1080 × 2340; PNG уменьшены до 720 × 1560 без дорисовки интерфейса.
- Светлая тема, dynamic color отключён, стандартный масштаб текста.
- Отдельные наборы `ru-*.png` и `en-*.png` для выбранного языка приложения.
- System UI demo mode фиксирует часы строки состояния на 12:34 и скрывает
  уведомления; он не подменяет часы или результаты таймера внутри приложения.
- Все проекты, заметки и записи созданы специально для иллюстраций. Личные
  записи, имена, backup-файлы и идентификаторы физических устройств не используются.

## Демонстрационные данные

Дата записей — 17 октября 2026 года. Сначала созданы проекты `Design Lab` и
`Learning`, затем через обычную форму истории добавлены записи:

| Проект | Начало | Минуты | Заметка |
| --- | --- | --- | --- |
| Design Lab | 08:30 | 90 | Landing page concept |
| Learning | 10:15 | 60 | Compose study |
| Design Lab | 11:30 | 30 | Icon sketches |

Три ручные записи дают 3 часа: 2 часа `Design Lab` и 1 час `Learning`.
Кадр таймера дополнительно показывает настоящую короткую сессию на паузе.
Снимки сделаны последовательно: короткие демонстрационные таймерные сессии
могут немного увеличить итог в более поздних кадрах.

Для обновления снимков используйте отдельный эмулятор и синтетические данные.
Сначала выполните инструментальные тесты на чистой тестовой установке, затем
создайте пример через UI, переключите язык в настройках и снимите нужные экраны.
Не очищайте ради съёмки данные личного телефона. При другой дате явно выберите
период отчёта, содержащий демонстрационные записи.

## English

These are real ADB captures from the Android 16 / API 36 emulator using a Pixel 5
profile, not a physical phone or interface mockups. Images were resized from
1080 × 2340 to 720 × 1560. The app uses the light theme with dynamic colors off.
Russian and English sets use the app's actual language selection.

Only synthetic projects and records listed above are used. System UI demo mode
stabilizes the status-bar clock and hides notifications without changing the
app's clock. Run instrumentation tests on a clean emulator installation before
creating screenshot records. Never clear a personal phone to produce screenshots.
