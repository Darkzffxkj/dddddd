# Referência V13 — escolha manual de largura, altura e density

Configurações no jogo > Configurações > Ferramenta de ajuste de tamanho de tela.

Exemplo suportado: largura 1656, altura 1080 e Density 500 DPI.

Os três campos são editáveis e independentes.
A resolução não precisa corresponder à proporção original do aparelho.
Os valores aceitos são largura e altura de 320 a 4096 px, no máximo 9 milhões de pixels, DPI de 120 a 1000.

Requer serviço de acessibilidade da Referência para abrir o menu e Shizuku com permissão de shell.
Usa comandos do Android: wm size e wm density (display principal).
Teste por 45 segundos com confirmação; se não confirmar, o serviço tenta reverter.
Se o processo do Shizuku for encerrado, o rollback temporizado pode falhar.
Resgate: wm size reset; wm density reset via ADB.
A tela inteira do Android será afetada, não somente Free Fire.
