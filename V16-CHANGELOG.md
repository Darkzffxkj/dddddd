# Referência V16 — HUD + Mouse / Visão

Esta source vem da V15 enviada pelo usuário.

Editor HUD em grade com ícones: Toque, Deslizar, Rolar com o mouse, Macro,
Joystick, Visão FPS, Olhar Livre, Seleção do Mouse, Suspender + Sensi e Atirar.
As funções sem motor de entrada mostram EM BREVE e NÃO executam comandos.
Toque e Atirar disparam gestos discretos via Android AccessibilityService;
Joystick registra o perfil, mas ainda não possui WASD contínuo.

Botão flutuante: Mouse / Suspender + Sensi, Resolução / DPI, Digitar
e Mostrar/Ocultar cursor virtual.
A tecla configurada em SUSPEND alterna suspensão e centraliza a posição do
cursor virtual; tecla extra configurável apenas centraliza o cursor.
O cursor renderiza o .cur importado por jogo ou uma seta roxa padrão,
em overlay que não captura toques.

Android 14+: leitura de eventos do mouse via AccessibilityService.onMotionEvent
(setMotionEventSources) com toque básico por botão primário. O evento do mouse
é consumido pelo serviço; use Suspender para devolver controle ao sistema.
A implementação é experimental e depende do Android/OEM.
Android 10–13: o cursor virtual não segue o mouse físico automaticamente.
Não há método público confiável para ocultar globalmente a seta nativa
enquanto outro app está em foco. Pode haver duas setas visíveis.
Os parâmetros X/Y, aceleração e suavização são perfil; mira contínua
e simulação de emulador NÃO estão implementadas.

Permissões: acessibilidade habilitada, Shizuku conforme recursos específicos.
Não modifica o APK do jogo.
