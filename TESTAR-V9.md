# Referência V9 — teste Android 10+
1. Ative Shizuku e o serviço Referência em Acessibilidade.
2. Abra o Free Fire pela aba Jogar.
3. Confirme se a logo flutuante continua visível durante carregamento e partida.
4. Abra Editar HUD, adicione Tocar e Atirar, toque na engrenagem de cada bolinha.
5. Configure a tecla (via teclado externo) e o delay de 0–300 ms.
6. Para Atirar, escolha uma tecla e intervalo dos disparos. Mantenha pressionada para testar toques repetidos.
7. Confira o perfil Sensi + Suspender com opções X/Y, aceleração e suavização.

Limitações V9:
- Os toques por teclado são gestos discretos via AccessibilityService.
- Mapeamento contínuo WASD, mira com mouse e multitouch simultâneo ainda não disponíveis.
- O mouse físico pode não entregar cliques ao serviço como KeyEvent.
- Não há garantia de que o Android não encerre o serviço de acessibilidade.
- Não foram executados testes no aparelho.
