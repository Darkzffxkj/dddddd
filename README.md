# KeyMapper Pro V2 — Interface roxa (Android 10+)

Projeto Android Studio em Kotlin, atualizado a partir do código-fonte V1. `minSdk=29` (Android 10), `targetSdk=35`.

## Incluído nesta entrega

- Tema original roxo/preto, inspirado na organização das capturas fornecidas: cabeçalho com gradiente violeta, cartões de status, abas **Jogar** e **Ativação**, lista de jogos em ícones circulares e menu de ferramentas.
- Reconhecimento de mouse, teclado, controles, áudio e periféricos USB externos. Nome de hardware é obtido do Android quando disponível; microfone e alto-falante **integrados** não contam como periféricos externos conectados.
- Descoberta de jogos por `CATEGORY_GAME`, alguns nomes de pacotes conhecidos e inclusão manual de aplicativos iniciáveis; a consulta é executada em uma thread dedicada.
- Ao tocar em **qualquer jogo**, abre um diálogo verificando o estado **real** de três opções:
  - **Widgets flutuantes**: `Settings.canDrawOverlays(...)` e tela `ACTION_MANAGE_OVERLAY_PERMISSION` (necessita permissão `SYSTEM_ALERT_WINDOW` declarada no manifest).
  - **Desativar a otimização da bateria**: `PowerManager.isIgnoringBatteryOptimizations(...)` e `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (sem solicitação direta de exceção). **Opcional**; o botão Pular não concede permissão.
  - **Ativação**: resultado efetivo da autorização do Shizuku; é exigida antes de lançar pelo painel.
- No Android 10, a tela de permissão de sobreposição pode abrir diretamente este app; no Android 11+, abre a lista de permissões do sistema.
- Após retornar das Configurações, o modal atualiza as verificações reais em `onResume`.
- Botão **Precisamos de ativação → Ativar**, e bloqueio de abertura pelo painel quando Shizuku não está autorizado.
- Com autorização, **não finge** que existe mapeamento: antes de iniciar o jogo, avisa expressamente que será aberto **sem mapeamento**.
- Métodos de inicialização Shizuku documentados no app: Android 10+ via PC/ADB USB ou outro aparelho com ADB host; Android 11+ também com depuração sem fio.
- Todas as telas são montadas nativamente em Kotlin com `android.widget`, sem WebView, publicidade, analytics, rastreadores ou rede própria.

## Não incluído

- **Ainda não funciona como mapeador**: captura exclusiva do mouse, conversão do cursor em câmera, WASD, injeção de toques, overlay funcional, perfis, macro, motor privilegiado e serviço permanente ainda não foram construídos.
- Não há ativador direto por outro celular nesta versão, só os passos de ativação Shizuku.
- A autorização pelo Shizuku **não** previne banimentos nem substitui o motor de mapeamento.
- Recursos mostrados em "Ferramentas" que não estão prontos apresentam uma explicação, em vez de simular uma ação.

## Compilar no Android Studio

1. Abra a pasta `KeyMapper-V2` como projeto.
2. Instale SDK Platform 35, JDK 17 e Gradle 8.9; o projeto utiliza AGP 8.7.3 e Kotlin 2.0.21.
3. Caso a IDE peça Gradle Wrapper JAR, execute `gradle wrapper --gradle-version 8.9` em máquina com Gradle; o `.jar` não está incluído.
4. Execute o módulo `app` no Android 10 ou mais recente.
5. Compile um APK debug via Android Studio > Build > Build APK(s). O resultado ficará em `app/build/outputs/apk/debug/app-debug.apk`.

## Plano de teste manual

- Android 10: confirmar que o método sem fio não aparece e que PC/outro celular aparecem; abrir permissão de overlay; testar abertura pelo painel bloqueada sem Shizuku.
- Android 11+: confirmar rota para tela de sobreposição no nível superior, depuração Wi-Fi e concessão de autorização Shizuku.
- Android 14/15/16: verificar restrições dos fabricantes, estado real da bateria e persistência da autorização.
- Conectar e desconectar mouse, teclado, headset e controle; conferir que a lista muda por eventos e mostra nomes expostos pelo SO.
- Instalar e desinstalar um jogo e usar atualizar jogos.
- Abrir um jogo sem ativação: modal → Ativar → aba Ativação; com ativação: confirmar ausência de motor e somente então permitir abrir sem mapeamento.

**Validação desta entrega:** estrutura do projeto e consistência dos arquivos conferidas; não houve compilação nem execução em dispositivo porque o ambiente de criação não possui Android SDK/Gradle. O ZIP é código-fonte, não APK funcional.


## Compilar com instalação assistida (pacote de compilação)

**Windows 10/11**: extraia este ZIP e execute `COMPILAR-APK-WINDOWS.cmd` (duplo clique), ou abra o PowerShell dentro da pasta.
Rode `powershell -ExecutionPolicy Bypass -File .\compilar-apk-windows.ps1`.
O script usa JDK 17+ (ou instala Temurin 17 pelo winget), instala Gradle 8.9 e
as ferramentas do Android SDK 35 a partir dos servidores oficiais, solicita
aceitação das licenças e compila o APK debug. Precisa de internet e espaço em disco.
Saída: `KeyMapper-Pro-V2-debug.apk` na raiz da pasta extraída.

**Linux x86_64**: rode `bash compilar-apk-linux.sh` (Java 17+, curl, unzip,
sha256sum necessários). Faz a instalação do SDK/Gradle no cache do usuário.

**GitHub Actions**: envie o conteúdo desta pasta para um repositório GitHub e
acesse Actions > Compilar APK Android > Run workflow. Após uma execução bem-sucedida,
abra a execução e baixe `KeyMapper-Pro-V2-debug-APK` em Artifacts. Também compila
nos commits enviados ao branch main/master.

Essas formas são alternativas: as ferramentas e dependências precisam ser baixadas
no equipamento que executar o build. O ZIP não contém os binários do SDK/Gradle.
O APK final é *debug*, instalável para testes. Não é uma versão assinada para Play Store
nem inclui o motor de mapeamento de teclado/mouse.

### Limitação do ambiente de criação

O ambiente usado para preparar este ZIP contém Java 21, mas não tem SDK Android
nem Gradle, e bloqueia acesso de rede externa. Portanto os scripts e a automação
foram verificados estaticamente; a compilação real só poderá ser validada onde
houver acesso aos repositórios oficiais.
