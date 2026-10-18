# Документация / Documentation

- [Руководство пользователя · User guide](USER_GUIDE.md)
- [Скриншоты приложения · App screenshots](screenshots/README.md)
- [Безопасность и приватность · Security and privacy](SECURITY.md)
- [Формат backup и CSV](BACKUP_FORMAT.md)
- [Подготовка APK к распространению · Release checklist](RELEASING.md)
- [1.1.0 — готовящийся стабильный выпуск · Upcoming stable release](releases/1.1.0.md)
- [1.0.0 — первый стабильный выпуск · Historical stable release](releases/1.0.0.md)
- [История изменений · Changelog](../CHANGELOG.md)
- [Лицензия · License](../LICENSE)
- [Сторонние компоненты · Third-party notices](../THIRD_PARTY_NOTICES.md)

Здесь хранится только публичная продуктовая, пользовательская и security-документация. Private planning notes, agent instructions, research logs, and internal development specifications are intentionally excluded.

Руководство 1.1 описывает пять экранов настроек, десять офлайн-тем RU/EN,
локальный проект по умолчанию, системные кнопки и два разных формата backup.
`.otmbackup` остаётся зашифрованным действием по умолчанию; `.otmplain` — явно
выбранный открытый файл без защиты подлинности. [Описание OTMB1 и CSV](BACKUP_FORMAT.md)
не означает, что plaintext зашифрован. Результаты проверок конкретной поставки
смотрите в её release assets, а не выводите из наличия этих документов.

The 1.1 guide covers five Settings screens, ten searchable offline topics, local
default-project selection, native Android controls, and encrypted versus plaintext
backup. Release notes remain upcoming until final assets and verification are
published; documentation alone is not evidence of signing, CI success, or OEM testing.
