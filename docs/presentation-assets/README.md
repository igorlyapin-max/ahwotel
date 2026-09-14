# Иллюстрации и сборка презентации

Содержание и источники: [PRESENTATION-AHWOTel.md](../PRESENTATION-AHWOTel.md). Генератор: [build-presentation.py](../../scripts/build-presentation.py).

`phone.png` и `shield.png` сгенерированы через OpenAI image generation для этой презентации 14.09.2026. Это концептуальные иллюстрации, не фотографии тестового Samsung и не скриншоты APK. Изображения встроены в PPTX; внешние ссылки для их отображения не нужны. Текст, схемы, карточки и диаграммы остаются редактируемыми.

Для сборки нужны Python 3, `python-pptx==1.0.2` и шрифт Noto Sans. Команды из корня проекта:

```bash
python3 -m venv .tools/presentation-venv
.tools/presentation-venv/bin/pip install python-pptx==1.0.2
.tools/presentation-venv/bin/python scripts/build-presentation.py
libreoffice -env:UserInstallation=file:///tmp/ahwotel-pptx-preview --headless --convert-to pdf --outdir docs docs/AHWOTel-Project-Overview.pptx
```

Генератор переносит заметки докладчика и ссылки из Markdown в заметки каждого слайда. После изменений нужно пересобрать и PPTX, и PDF; PDF служит проверенным визуальным экспортом. Числа на последнем слайде зафиксированы в [снимке затрат](../PRESENTATION-USAGE-2026-09-14.json); последующая генерация PPTX и иллюстраций в него не входит.
