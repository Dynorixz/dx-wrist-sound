# Фон предупреждения 1.2.2

Сохранённый файл: `wear/src/main/res/drawable-nodpi/phone_left_background.png`.

Создан встроенным инструментом imagegen по изображению-примеру, предоставленному пользователем 9 октября 2026 года. Пример использован как ориентир сцены и композиции. Фотография не содержит текста и интерфейса: заголовок, предупреждающий знак, рамку и кнопку рисует приложение. PNG — 1 807 737 байт; перед показом часы декодируют его с inSampleSize для размера своего дисплея. Исходный результат инструмента сохранён отдельно в generated_images пользователя.

## Финальный промпт

```text
Use case: product-mockup. Asset type: full-bleed photographic background for a circular Wear OS phone-left-behind alert. Input image 1 is a style and scene reference, not the final UI. Generate a clean photorealistic square 1024x1024 background inspired by the reference: one modern unbranded black smartphone lying diagonally on a warm wooden desk, polished metal edge and dark blue glass reflecting a softly lit window, gentle blurred blue interior and green plant in the background. Composition: smartphone lies prominently across the lower-middle half, mostly inside the central 75% circular safe area; upper half is blurred and dark navy with usable negative space for large app text; bottom 12% is dark enough for a native dismiss button. Professional polished product photography, detailed materials, cinematic blue/warm lighting, strong contrast suited for a tiny round watch screen. Fill the entire square with the photo. Constraints: absolutely no text, letters, logos, watermark, warning sign, arrows, red border, circle or white outer margin; the app will draw those separately. Keep one complete recognizable phone, no extra objects beyond the referenced room, desk and softly blurred plant. Do not produce a watch device or a finished UI mockup.
```

Макет для просмотра композиции: `artifacts/qa-1.2.2/preview.html`, снимок макета: `artifacts/qa-1.2.2/popup-preview.png`. Это browser preview, не снимок Android: Android использует собственный condensed шрифт, SP-масштаб и минимальную высоту кнопки 48 dp.
