# Участие в разработке

Спасибо за интерес к проекту. Ниже — как подготовить окружение, запустить проверки и оформить изменение.

## Окружение

- JDK 17
- Android SDK: платформа API 35, build-tools 35.0.0
- Путь к SDK задаётся через `ANDROID_HOME` или `local.properties` (`sdk.dir=...`), файл `local.properties` не коммитится

## Сборка

```bash
./gradlew assembleDebug      # debug APK
./gradlew installDebug       # установка на устройство/эмулятор
```

## Проверки перед pull request

Запускайте все три команды; изменение не принимается, если они не проходят:

```bash
./gradlew testDebugUnitTest  # модульные тесты
./gradlew lintDebug          # Android Lint
./gradlew ktlintCheck        # форматирование Kotlin
```

Автоформат:

```bash
./gradlew ktlintFormat
```

> Примечание: задача `ktlintCheck` сейчас настроена как неблокирующая (`ignoreFailures = true`), пока существующий код не отформатирован полностью. Тем не менее новый код пишите в стиле ktlint.

## Стиль кода

- Официальный стиль Kotlin (`kotlin.code.style=official`), форматирование — ktlint.
- Следуйте существующим соглашениям именования и структуре пакетов.
- Держите изменения точечными: без попутного рефакторинга и новых зависимостей без необходимости.

## Коммиты

Используется [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `perf:`, `test:`, `chore:`, `docs:`. Пример:

```
fix(chat): не терять промпт при перезагрузке снапшота
```

## Тесты

- На изменение логики добавляйте модульный тест в `app/src/test`.
- Сетевой слой тестируется через OkHttp MockWebServer — см. `data/remote/*Test.kt`.
- Инструментальные тесты — в `app/src/androidTest`.

## Pull request

1. Ветка от `main`, осмысленное имя (`fix/...`, `feat/...`).
2. В описании: что сделано, зачем, чем проверено (какие команды и результат).
3. Убедитесь, что проверки из раздела выше проходят.
