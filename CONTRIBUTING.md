# Участие в проекте / Contributing

[Русский](#русский) · [English](#english)

## Русский

Спасибо, что помогаете Open TimeManager стать лучше. Полезны не только изменения
кода: понятное описание ошибки, улучшение перевода или исправление инструкции
тоже помогают.

### Сообщить об ошибке или предложить идею

Проверьте [существующие обсуждения](https://github.com/AnoY606-git/Open-TimeManager/issues),
затем создайте issue. Писать можно на русском или английском.

- Для ошибки опишите, что вы сделали, чего ожидали и что получилось. Укажите
  версию приложения и Android.
- Для идеи расскажите, какую задачу она решает и почему текущего поведения
  недостаточно. Детали реализации можно обсудить позже.
- В примерах используйте вымышленные записи. Не прикладывайте личные копии,
  CSV, пароли, ключи или идентификаторы устройств.

Уязвимости отправляйте [приватно](SECURITY.md), не через обычный issue.

### Подготовить изменение

1. Настройте проект по [инструкции сборки](docs/BUILDING.md).
2. Для большой функции сначала обсудите идею. Небольшое исправление можно сразу
   прислать отдельным pull request.
3. Сохраняйте стиль соседнего кода. Для исправления поведения добавьте тест,
   который воспроизводит ошибку.
4. Запустите [проверки](docs/BUILDING.md#проверки-перед-отправкой-изменений).
5. В pull request кратко объясните результат, риски и выполненные проверки.
   Если что-то не проверяли, напишите об этом прямо.

### Что важно сохранить

- Работа без аккаунта и сетевого разрешения — основа приложения. Новые данные
  и разрешения нужно заранее обсуждать.
- Тексты интерфейса должны быть и на русском, и на английском.
- Изменения схемы Room сопровождаются экспортированной схемой и миграцией
  без потери данных.
- Версии зависимостей закреплены. При обновлении инструментов согласуйте
  `gradle/toolchain.json`, CI и тесты настройки окружения.
- Не добавляйте в Git ключи подписи, локальные инструменты, сборки и личные файлы.
  Инструкции разработчикам храните в публичной документации, рабочие заметки — вне неё.

Изменения принимаются на условиях [GPL-3.0-only](LICENSE).
Скриншоты для документации делайте на эмуляторе с вымышленными записями;
[требования к изображениям](docs/screenshots/README.md#обновление-снимков).

## English

Thank you for helping improve Open TimeManager. Clear bug reports, translations,
and documentation fixes are just as welcome as code.

### Bugs and ideas

Check [existing issues](https://github.com/AnoY606-git/Open-TimeManager/issues)
before opening a new one. Russian and English are both welcome.

- For a bug, include what you did, what you expected, what happened, and your app
  and Android versions.
- For an idea, explain the problem it solves and why current behavior is not enough.
  Implementation can be discussed later.
- Use made-up records. Do not attach personal backups, CSV, passwords, signing
  keys, or device identifiers.

Report vulnerabilities [privately](SECURITY.md#english), not in a normal issue.

### Send a change

1. Follow the [build instructions](docs/BUILDING.md#english).
2. Discuss substantial features first; a small fix can go straight to a focused pull request.
3. Follow the surrounding code style and add a regression test for behavior changes.
4. Run the [checks](docs/BUILDING.md#check-your-changes).
5. Describe the result, risks, and actual checks in your pull request. Say what
   you did not test; compilation alone is not an emulator run.

Keep the app usable without an account or network permission. Discuss new data
access first, maintain both interface languages, and include a non-destructive
migration with every Room schema change. Pin dependency versions; tool upgrades
must keep `gradle/toolchain.json`, CI, and toolchain tests in sync.

Never commit signing keys, local tools, generated builds, or personal data.
Keep public instructions in the documentation and private working notes out of it.
Documentation screenshots must use an emulator and made-up records;
see the [capture notes](docs/screenshots/README.md#english).
Contributions are accepted under [GPL-3.0-only](LICENSE).
