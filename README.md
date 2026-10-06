# Wally Watch

App Android que reenvía las notificaciones del teléfono al smartwatch
LY735/P800 por Bluetooth LE. Reemplazo de SuperBand para notificaciones.

## Qué hace

- **Lee** las notificaciones del sistema (`NotificationListenerService`).
- **Filtra** por app con interruptores que funcionan de verdad
  (el bug de SuperBand donde el toggle de Telegram no surtía efecto no existe aquí).
- **Envía** al reloj por BLE usando el protocolo documentado
  (Nordic UART, comando `(18,18)`).
- **Reconecta** automáticamente si se pierde la conexión.

## Compilación

GitHub Actions compila el APK debug en cada push a `main`
y lo publica como Release (pre-release).

## Estado

v1 — notificaciones. Esferas personalizadas: pendiente.
