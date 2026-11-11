# MercadoNegro

Subasta por apuestas programada (viernes 18:00 UTC-6) con ítems de MythicMobs y economía Vault.
Paper 1.21.7 · Java 21 · MythicMobs 5.x · Vault + EssentialsX.

## Compilar
- Local: `mvn clean package` → `target/MercadoNegro-1.0.0.jar`
- GitHub: sube el repo; en la pestaña **Actions** descarga el artefacto `MercadoNegro`.

## Comandos
- `/mercadonegro <idItem> <precioBase>` (admin) · `start` · `cancel` · `info` · `reload`
- `/mercadonegro preview [segundos]` simula el evento completo (diagnóstico + subasta falsa con bots, sin dinero real).
- `/mercadonegro mini <idItem> <precioBase> [minutos]` anuncia un mini mercado independiente del evento del viernes.
- `/subasta` abre el menú.
