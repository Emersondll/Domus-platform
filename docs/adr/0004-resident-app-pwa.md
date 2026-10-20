# ADR 0004 — App do morador como PWA (React + TypeScript)

**Status:** aceita (decisão do agente, reversível). **Contexto:** o prompt pede avaliar Flutter ou React Native.
Nesta máquina não há toolchain mobile (Flutter, Android SDK, Xcode) e o produto precisa ser validado localmente.
**Decisão:** primeira versão como PWA instalável (manifest + service worker que não guarda respostas da API),
compartilhando o cliente HTTP com o painel. O QR Code é gerado no cliente a partir de um token sem dado pessoal.
**Limitações conhecidas:** sem push nativo (Web Push/FCM pendente), sem credencial NFC/Bluetooth, câmera e
armazenamento seguro limitados ao que o navegador oferece.
**Revisão:** migrar para React Native (reaproveita TypeScript e tipos) quando houver necessidade de push nativo,
credenciais digitais em carteira/NFC ou distribuição nas lojas.
