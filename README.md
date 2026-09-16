# AmiiboSlot

Android-приложение для подготовки 540-байтных Amiibo BIN и записи их в слоты Chameleon Ultra.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Chameleon Ultra](https://img.shields.io/badge/Chameleon-Ultra-7A5AF8)
![Offline](https://img.shields.io/badge/работает-офлайн-2ea44f)

## Возможности

- импорт обычных NTAG215 BIN и Decrypted BIN размером ровно 540 байт;
- определение Amiibo по внутреннему ID: имя, серия и версия;
- исправление UID, BCC, PWD и PACK (`8080`);
- шифрование Decrypted BIN с проверкой криптоподписи;
- экспорт подготовленного BIN в `Загрузки/AmiiboSlot`;
- поиск Chameleon Ultra по Bluetooth LE;
- подключение через USB-мост ПК для MuMu Player;
- запись в слоты 1–8 с резервной копией, проверкой всех 540 байт и GET_VERSION;
- чтение имён слотов, заданных в Chameleon GUI;
- локальные ZIP-резервные копии перед каждой записью.

## Установка

Скачай готовый `AmiiboSlot.apk`, передай его на Android или установи в MuMu Player. Разреши установку приложений из этого источника, если Android спросит.

## Как пользоваться

1. Нажми **Открыть BIN** и выбери Amiibo-файл.
2. Обычный BIN подготовится сразу. Для Decrypted BIN приложение использует `key_retail.bin`.
3. При необходимости нажми **Экспорт исправленного BIN**.
4. Подключи Chameleon по Bluetooth или через ПК / MuMu.
5. На кнопках слотов появятся имена из Chameleon GUI.
6. Выбери слот и нажми **Записать в слот**.

Перед изменением слота приложение сохраняет резервную копию. После записи оно читает память обратно и проверяет UID и GET_VERSION.

## MuMu Player и USB-мост

Bluetooth внутри эмулятора может не видеть физический Chameleon. Для этого есть мост через ПК:

1. Подключи Chameleon Ultra к компьютеру по USB.
2. Укажи его COM-порт в `start-mumu-bridge.ps1` или запусти `pc_bridge.py COM6`.
3. Запусти скрипт и не закрывай его.
4. В приложении нажми **Подключить через ПК / MuMu**.

Мост работает только локально: он не отправляет BIN в интернет.

## Сборка из исходников

Нужны Android SDK и JDK 17.

```powershell
copy local.properties.example local.properties
# укажи sdk.dir в local.properties
.\gradlew.bat :app:assembleDebug
```

Готовый APK появится в:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Проверка логики без Android-эмулятора:

```powershell
.\test-core.ps1
```

## Ключи Amiibo

Для подготовки Decrypted BIN нужен личный `key_retail.bin` размером 160 байт. Публичная версия исходников не должна содержать этот файл: импортируй его в приложение вручную через кнопку **Импорт ключей для Decrypted BIN**. Не публикуй APK, в который ключ встроен: его можно извлечь из приложения.

## Лицензии и источники

- офлайн-каталог Amiibo: [AmiiboAPI](https://www.amiiboapi.com/), MIT;
- алгоритмы криптографии: [amiitool](https://github.com/socram8888/amiitool), MIT.

Nintendo и Amiibo являются товарными знаками Nintendo. Проект не связан с Nintendo.
