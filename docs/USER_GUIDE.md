# Руководство пользователя / User guide

[Русский](#русский) · [English](#english) · [Документация](README.md)

## Русский

Это руководство для Open TimeManager 1.1.0. Справка с поиском также доступна
без интернета в «Настройки → Как работать с приложением».

### Первая рабочая сессия

1. Откройте «Таймер» и выберите проект. Если группировать работу пока не нужно,
   оставьте «Без проекта».
2. Нажмите «Начать».
3. На время перерыва нажмите «Пауза», затем «Продолжить».
4. В конце работы нажмите «Завершить». Запись появится в «Истории».

К сессии можно добавить заметку — не забудьте сохранить её в редакторе.
Дневная цель помогает следить за прогрессом, но не останавливает таймер
и не включает будильник.

Одновременно работает один таймер. Если выбрать другой проект во время сессии,
приложение попросит подтвердить переключение: прежняя запись завершится,
а для нового проекта начнётся новая.

### Проекты

Создайте проект на экране таймера, задайте имя и цвет. Когда проект больше не
нужен, архивируйте его: он исчезнет из списка для новой работы, но его записи
останутся в истории. Сначала завершите таймер этого проекта или разберите
запись, которая требует проверки. «Без проекта» — постоянный вариант,
его архивировать нельзя.

**Проект по умолчанию** выбирается в «Настройки → Учёт и проекты».
Он будет предложен для новой работы, но не изменит текущую сессию и не отменит
ваш выбор на открытом таймере. Архивный или удалённый проект больше не используется.
Чтобы убрать предпочтение, выберите «Автоматический выбор».

Виджет и плитка могут сразу начать работу с проектом по умолчанию. Если он не
задан, быстрый запуск сработает только при одном доступном проекте; иначе
откроется приложение, чтобы вы выбрали нужный. Этот выбор хранится на устройстве
и не переносится в копии.

### История и ручные записи

В «Истории» можно найти запись по проекту или заметке, исправить её либо добавить
работу, которую вы не засекали. Для ручной записи укажите дату, время начала,
длительность и проект. Старые записи открываются кнопкой «Загрузить ещё»;
в выборе проекта есть отдельный поиск.

Изменение заметки или проекта не меняет точное время исходной записи. Если
изменить время, прежние интервалы заменятся введённой длительностью.
Архивный проект старой записи можно сохранить при редактировании.
Удаление записи после подтверждения отменить нельзя.

Если приложение сообщает, что запись уже изменилась, закройте форму и откройте
её заново — так вы не перезапишете более свежие данные.

### Отчёты и CSV

Выберите неделю, месяц или собственный период до 10 лет. Границы периода
определяются текущим часовым поясом. В отчёт попадает уже сохранённое время:
текущий отрезок добавится после паузы, завершения или смены проекта.
Поэтому работающий таймер может показывать больше, чем отчёт.

В режиме месяца стрелки переключают календарные месяцы, а «Прошлый месяц»
и «Текущий месяц» позволяют быстро вернуться к нужному периоду. В карточке
«Баланс месяца» задайте план часов и минут именно для выбранного месяца:
приложение покажет переработку или недобор, а для незавершённого месяца —
сколько осталось до плана. Пока план не задан, недобор не рассчитывается.
Нулевой план допустим, например для месяца без работы.

Оценка по будням использует понедельник–пятницу и текущую дневную цель,
но не знает праздников, отпусков и вашего графика. Она заполняет форму только
по нажатию; проверьте и сохраните её самостоятельно. Изменение дневной цели
не меняет уже сохранённые планы месяцев. Планы хранятся локально и не входят
в резервные копии; восстановление записей их не заменяет.

Кнопка экспорта сохраняет выбранный период в CSV через системное окно Android.
Его можно открыть в таблицах. **CSV не подходит для восстановления приложения**
и может содержать названия проектов и заметки — не публикуйте его случайно.

### Копии и восстановление

#### Сохранить копию

1. Поставьте таймер на паузу или завершите его: ещё не закрытый рабочий отрезок
   в копию не попадёт.
2. Откройте «Настройки → Копии данных».
3. Выберите зашифрованную копию, придумайте длинную уникальную парольную фразу
   и сохраните файл отдельно от устройства.
4. Для проверки откройте сохранённый файл через восстановление и убедитесь,
   что предпросмотр показывает ожидаемые данные. Применять восстановление не нужно.

| Формат | Когда использовать |
| --- | --- |
| **`.otmbackup` — с паролем** | Обычный и рекомендуемый вариант. Содержимое зашифровано. Забытый пароль восстановить нельзя. |
| **`.otmplain` — без пароля** | Только если вам нужен открытый файл и вы понимаете риск. Любой получатель сможет прочитать и изменить его. |

Для нового пароля нужны не менее 12 символов и не более 1024 байт UTF-8.
Пробелы учитываются; старые копии с более короткими паролями продолжают открываться.
Если зашифрованная копия не открылась, проверьте пароль и сам файл: приложение
не обходит пароль и не превращает такую копию в открытую.

Копии содержат проекты, заметки, сохранённые интервалы и основные настройки:
тему, цвета устройства, скрытие названий и дневную цель. Язык и проект по
умолчанию и планы месяцев остаются на устройстве. Старые зашифрованные копии поддерживаются.
Приложение принимает файл до 4 МиБ и до 20 000 проектов, записей и интервалов
каждого вида.

Перед созданием или открытием `.otmplain` появляется предупреждение.
Проверка файла обнаруживает случайное повреждение, но **не доказывает,
кто его создал и не изменил ли его кто-то намеренно**. Открывайте копии
только из источников, которым доверяете.

#### Восстановить данные

1. Завершите текущий таймер. Восстановление недоступно и на паузе, и пока запись
   требует проверки.
2. Выберите файл. Для зашифрованной копии введите пароль точно, включая пробелы.
3. Посмотрите количество проектов и записей в предпросмотре.
4. Выберите способ восстановления:
   - **«Объединить»** — оставить текущие данные и добавить новые. Если у одной
     записи в двух копиях разное содержимое, приложение остановит импорт,
     а не выберет вариант за вас.
   - **«Заменить»** — удалить текущие проекты и историю и загрузить данные из
     копии вместе с переносимыми настройками. **Сначала сохраните отдельную
     проверенную копию: успешную замену нельзя отменить.**

Если импорт прервался до сохранения базы, изменения откатятся. Если данные уже
восстановлены, а часть настроек ещё ожидает применения, приложение предупредит
об этом и повторит применение настроек. Не запускайте «Заменить» ещё раз только
ради такого предупреждения. Пока скрытие названий не применилось полностью,
проверьте, что показывают уведомление и виджет.

Предпросмотр можно временно скрыть и открыть через «Продолжить восстановление».
«Отменить восстановление» его закроет окончательно. Если Android завершит
процесс приложения, файл придётся выбрать заново. Сам по себе импорт не
запускает таймер.

Системное окно сохранения может предложить облачное хранилище. В таком случае
файл отправляет выбранный сервис. Если после ошибки остался недописанный файл,
удалите именно его — очищать данные приложения не нужно.

### Уведомление, виджет и плитка

Управление доступно в «Настройки → Учёт и проекты».

- **Уведомление** позволяет поставить таймер на паузу, продолжить или завершить
  сессию. Если уведомления запрещены, откройте их системные настройки; внутри
  приложения таймер по-прежнему доступен.
- **Виджет** можно добавить кнопкой в приложении, если это поддерживает ваш
  рабочий стол. Иначе найдите Open TimeManager в обычном списке виджетов.
- **Плитка быстрых настроек** добавляется через системный запрос на Android 13+.
  На Android 12 откройте редактор быстрых настроек и добавьте её вручную.
- **«Скрывать названия проектов»** убирает имена из этих системных элементов.
  Это настройка отображения, а не шифрование.

На паузе длительность не растёт. Если системная кнопка выглядит устаревшей,
откройте приложение и проверьте состояние таймера.

На Android 16 можно отдельно включить **Live Updates** для работающего таймера.
По умолчанию они выключены. Полная поддержка зависит от системного обновления
36.1 и решения Android или производителя: уведомление может остаться обычным.
После скрытия пользователем та же сессия не будет снова запрашивать выделенное
отображение.

### Если таймер требует проверки

Закрытие экрана само по себе не завершает сессию. Но после перезагрузки или
потери надёжных данных о времени приложение может не знать, сколько вы работали.

Откройте таймер и разберите запись с пометкой «Нужна проверка». Можно оставить
только подтверждённое время или указать окончание, которое вы можете проверить.
Приложение не добавляет неизвестные часы автоматически.

### Тема, язык и настройки

В «Оформлении» доступны системная, светлая и тёмная темы, цвета устройства и язык.
На Android 13+ язык также можно выбрать в системных настройках приложения;
на Android 12 используйте выбор внутри Open TimeManager.

Остальные настройки сгруппированы по задачам: учёт и проекты, копии данных,
справка и сведения о приложении. Размер окна и крупный шрифт могут менять
расположение элементов.

### Если не обновляются системные кнопки

Сначала откройте приложение, проверьте уведомления и его страницу в настройках
Android. В разделе «Учёт и проекты» видны признаки экономии батареи и фоновых
ограничений — приложение показывает их, но не меняет само.

- На **HyperOS/MIUI** проверьте уведомления, батарею и, если есть, автозапуск.
- На **One UI** посмотрите списки спящих и глубоко спящих приложений.
- На **ColorOS/OxygenOS/realme UI** проверьте ограничения фоновой активности.

Названия меню зависят от модели и версии прошивки. Меняйте только то, что
помогает решить конкретную проблему; заранее отключать всю защиту батареи
не нужно. Это общие подсказки, а не список протестированных устройств.
Измерений экономии батареи для приложения пока нет.

### Безопасное обновление

Сохраните и проверьте зашифрованную копию, затем установите APK нового релиза
поверх старого. Для обновления нужна совместимая подпись. В 1.1.0 сохранён
ключ версии 1.0.0; тестовая сборка может использовать другой ключ.
При ошибке подписи не удаляйте приложение и не очищайте данные ради установки.

[Скачать релиз](https://github.com/AnoY606-git/Open-TimeManager/releases/latest) ·
[Подробнее о защите данных](SECURITY.md)

## English

This guide covers Open TimeManager 1.1.0. Searchable help is also available
offline under Settings → How to use the app.

### Your first work session

1. Open Timer and choose a project, or leave No project selected.
2. Tap Start.
3. Use Pause for a break, then Resume.
4. Tap Stop when you finish. The entry will appear in History.

You can add a note to the current session; remember to save it in the editor.
The daily goal is a progress target, not an alarm or an automatic stop.
Only one timer runs at a time. Switching projects asks for confirmation,
finishes the old entry, and starts a new one.

### Projects

Create a project on Timer and give it a name and color. Archive a finished project
to hide it from new work without deleting its history. First finish or resolve
any active session that uses it. No project is always available and cannot be archived.

Choose a **default project** under Settings → Tracking and projects. It is
suggested for new work but never changes an active session or overrides a choice
you made on the open Timer screen. Missing or archived projects are ignored;
Automatic selection clears the preference.

The widget and tile can start the default project immediately. Without a default,
quick Start works only when exactly one project is available; otherwise the app
opens for you to choose. This preference stays on the device, outside backups.

### History and manual entries

Search History by project or note. Add entry lets you record work you did not
time: choose a local date, start time, duration, and project. Load more opens
older entries; the project picker has its own search.

Changing a note or project preserves the original timing. Changing the time
replaces the old intervals with the duration you enter. You can keep an existing
entry's archived project. Confirmed deletion cannot be undone.
If the app says an entry has changed, reopen it rather than overwriting newer data.

### Reports and CSV

Choose a week, month, or custom range of up to 10 years. Period boundaries follow
your current time zone. Reports include saved intervals; a running interval is
added after you pause, stop, or switch projects. That is why the live timer can
show more than the report.

In monthly mode, use the arrows to browse calendar months or jump to the last
or current month. Set planned hours and minutes for that specific month in
Monthly balance. The card shows time above or below plan; an unfinished month
shows how much remains. An unset plan is not treated as missing work, and a
zero-hour plan is allowed.

The weekday estimate uses Monday–Friday and your current daily goal, not public
holidays, leave, or individual schedules. It only fills the form when requested;
review and save it yourself. Changing the daily goal does not change saved
monthly plans. Plans stay on this device, outside backups; restoring time
records does not replace them.

Export saves the chosen range as CSV through Android's file picker.
**CSV is for spreadsheets, not restoring the app.** It can contain project names
and notes, so keep it private.

### Backups and restore

#### Save a copy

1. Pause or stop first. The current open work interval is not included.
2. Open Settings → Backups and restore.
3. Choose an encrypted backup, use a long, unique passphrase, and save it somewhere
   separate from your device.
4. To check the file, open it through restore and review the preview. You do not
   need to apply the restore.

| Format | When to use it |
| --- | --- |
| **`.otmbackup` — password-protected** | The recommended default. Contents are encrypted. A forgotten password cannot be recovered. |
| **`.otmplain` — no password** | Only when you need a readable file and accept the risk. Anyone with it can read and alter the contents. |

New passwords need at least 12 Unicode code points and at most 1024 UTF-8 bytes.
Spaces matter; older copies with shorter passwords still work. If an encrypted
file does not open, check the password and file. The app does not bypass encryption.

Copies include projects, notes, saved intervals, theme, device colors, hidden
project labels, and the daily goal. Language, monthly plans, and the default-project choice stay
on the device. Older encrypted copies remain supported. The app accepts up to
4 MiB and 20,000 projects, entries, and intervals of each kind.

Unprotected copies have separate warnings before creation and inspection.
Their checksum can detect accidental damage, **not who created the file or
whether someone deliberately changed it**. Only open copies from trusted sources.

#### Restore a copy

1. Finish the timer. Restore is also blocked while paused or awaiting review.
2. Choose the file. For an encrypted copy, enter its exact password, including spaces.
3. Check the project and entry counts in the preview.
4. Choose a restore mode:
   - **Merge** keeps current data and adds new records. If the same record has
     different contents in the two copies, import stops rather than guessing.
   - **Replace** removes current projects and history, then loads the copy and its
     portable settings. **Save and check a separate backup first: a successful
     replacement cannot be undone.**

If import fails before the database is committed, changes roll back. If records
are already restored but some settings still need applying, the app warns and
retries those settings. Do not repeat Replace just for that warning. Until hidden
project labels are fully applied, check what the notification and widget display.

Hide for now keeps the preview available through Resume restore preview.
Cancel restore discards it. If Android ends the app process, choose the file
again. Import never starts a timer automatically.

Choosing a cloud location in the file picker lets that service upload the file.
If an error leaves an incomplete file behind, delete that file, not the app's data.

### Notification, widget, and Quick Settings

Find system controls under Settings → Tracking and projects.

- **Notification:** pause, resume, or stop the session. If notifications are blocked,
  open their system settings. You can still use the timer inside the app.
- **Widget:** use the in-app add button if your launcher supports it, or find
  Open TimeManager in the launcher's widget list.
- **Quick Settings tile:** Android 13+ can show a system add dialog. On Android 12,
  add the tile manually through the Quick Settings editor.
- **Hide project names:** removes labels from these controls; it does not encrypt data.

Paused duration stays fixed. If a system control looks out of date, reopen the
app and check the timer's state.

On Android 16, optional **Live Updates** can request a more prominent notification
for a running timer. They are off by default. Full support depends on system
update 36.1 and Android or manufacturer decisions; the notification may remain
ordinary. A dismissed session will not request prominent presentation again.

### When a timer needs review

Closing the screen does not itself stop a session. After a reboot or loss of
reliable timing data, however, the app may not know how long you worked.

Open the entry marked Review required. Keep only confirmed time or provide an
end time you can verify. The app does not add unknown hours automatically.

### Appearance and language

Appearance offers system, light, and dark themes, device colors, and language.
On Android 13+, language is also available in Android's app settings. On Android 12,
use the in-app selector. Other settings are grouped into tracking, backups, help,
and app information. Layout adapts to the window size and larger fonts.

### If system controls stop updating

First open the app, check notification access, and review its Android settings.
Tracking and projects shows Battery Saver and background-restriction status;
the app reads these settings but does not change them.

- **HyperOS/MIUI:** check notifications, battery settings, and autostart if available.
- **One UI:** check Sleeping and Deep sleeping apps.
- **ColorOS/OxygenOS/realme UI:** check background activity restrictions.

Menu names vary. Change only what helps with the specific problem; you do not
need to disable every battery protection. These are general suggestions, not a
list of tested devices. Battery savings have not been measured.

### Update safely

Save and check an encrypted backup, then install the new release over the old
one. Updates need a compatible signature. Version 1.1.0 keeps 1.0.0's signing key;
test builds may use another. Do not uninstall or clear data to force an update
after a signature mismatch.

[Download a release](https://github.com/AnoY606-git/Open-TimeManager/releases/latest) ·
[More about data protection](SECURITY.md#english)
