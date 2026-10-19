# Documentação do Sistema SIC/XE
**Módulos:** Máquina Virtual, Montador de Duas Passagens e Carregador

## Visão Geral da Arquitetura (Padrão MVC)
O sistema implementa o padrão MVC. A Máquina Virtual (Modelo) expõe o estado da memória, dos registradores e os métodos de controle de ciclo (como `step()`), sem possuir acoplamento com bibliotecas gráficas. O Controller orquestra a execução e interage com a Interface Gráfica (View) sem bloquear a Thread da interface. Esta estrutura arquitetural permite integrar o Montador e o Carregador e acrescentar futuramente outros componentes, como o Ligador.

---

## Sumário
* **Visão Geral da Arquitetura**
* **MÓDULO 1: MODEL**
  * **1. Pacote `types`: Tipos Fundamentais e Representação de Dados**
    * 1.1.1. Classe `Word24`
    * 1.1.2. Enum `Register`
    * 1.1.3. Enum `ConditionCode`
    * 1.1.4. Enum `Opcode`
    * 1.1.5. Enum `AddressingMode`
  * **2. Pacote `hardware`: Componentes de Hardware**
    * 1.2.1. Classe `Memory`
    * 1.2.2. Classe `RegisterBank`
  * **3. Pacote `decoder`: Decodificação de Instruções**
    * 1.3.1. Classe `DecodedInstruction`
    * 1.3.2. Classe `InstructionDecoder`
  * **4. Pacote `execution`: Execução e Endereçamento**
    * 1.4.1. Classe `TargetAddressCalculator`
    * 1.4.2. Classe `Executor`
  * **5. Pacote `machine`: Fachada e Controle da Máquina**
    * 1.5.1. Classe `SICXEMachine`
* **MÓDULO 2: Controller**
  * **1. Pacote `controller`: Orquestração e Eventos**
    * 2.1.1. Interface `MachineStateListener`
    * 2.1.2. Classe `MachineController`
* **MÓDULO 3: View**
  * **7. Pacote `view`: Interface Gráfica**
    * 7.1. Classe `SICXEView`
* **MÓDULO 4: Montador e Carregador**
  * **8. Montador e Carregador (Segunda Etapa)**
    * 8.1. Montagem em duas passagens
    * 8.2. Estruturas e responsabilidades
    * 8.3. Linguagem e programa objeto
    * 8.4. Uso e integração com a máquina virtual
    * 8.5. Exemplos, testes e limites

---

## MÓDULO 1: MODEL

---

## 1.1. Pacote `types`: Tipos Fundamentais

Este pacote resolve a incompatibilidade estrutural entre a arquitetura SIC/XE e os tipos primitivos da linguagem Java, além de padronizar a identificação de instruções e modos de operação.

### 1.1.1. Classe `Word24`
A arquitetura SIC/XE utiliza operandos de 24 bits (3 bytes), enquanto a linguagem Java utiliza inteiros de 32 bits com sinal. A classe `Word24` encapsula um inteiro Java e aplica máscaras bit a bit (`& 0xFFFFFF`) no construtor, garantindo o truncamento dos valores para o teto de 24 bits.

*   **Prevenção de Extensão de Sinal:** O Java propaga o sinal negativo de bytes quando o bit mais significativo é 1. A constante `UNSIGNED_BYTE_MASK` (0xFF) aplica uma operação bit a bit para anular essa extensão e forçar a leitura do byte como um inteiro sem sinal (0 a 255).
*   **Tratamento de Complemento de 2 (Endereçamento Relativo):** O método `fromSigned12Bit()` isola os 12 bits do campo de deslocamento (*displacement*) utilizado nas instruções de Formato 3. Se o bit de sinal indicar valor negativo (salto para trás), a máscara `| 0xFFFFF000` estende os bits "1" para o espaço superior, garantindo a corretude da adição aritmética com o registrador PC. O método `toIntSigned()` aplica a extensão correspondente para 24 bits, retornando o valor com sinal estendido para 32 bits.
*   **Aritmética e Manipulação de Bytes:** Fornece métodos unificados para operações aritméticas (`add()`, `subtract()`) e leitura posicional (`getHighByte()`, `getMiddleByte()`, `getLowByte()`). O método `withLowByte()` viabiliza a substituição isolada do byte menos significativo, mantendo os bytes superiores intactos, fornecendo a base operacional para a instrução `LDCH`.
*   **Operações Lógicas e de Deslocamento:** Disponibiliza os métodos `bitwiseAnd()` e `bitwiseOr()` para efetuar as operações lógicas equivalentes exigidas pelas instruções `AND` e `OR`. Para manipulação em nível de bit, implementa `shiftLeftCircular()`, que executa a rotação circular truncando e realocando os bits dentro do espaço estrito de 24 bits (`SHIFTL`). O método `shiftRightArithmetic()` utiliza o operador aritmético de deslocamento nativo do Java (`>>`) sobre a representação com sinal estendido, garantindo que as posições vacantes à esquerda sejam preenchidas com o bit de sinal original, atendendo ao comportamento da instrução `SHIFTR`.

### 1.1.2. Enum `Register`
Mapeia os registradores definidos na especificação (A, X, L, B, S, T, F, PC e SW) para os seus respectivos códigos numéricos de hardware (0 a 9).
*   **Controle de Identificadores:** A substituição de variáveis inteiras por um Enum provê segurança de tipagem (type-safety). Opcodes lidos de instruções do Formato 2 são convertidos instanciando objetos tipados através de mapeamento (ex: `Register.fromCode()`).
*   **Registrador F:** O registrador de Ponto Flutuante (código 6, 48 bits) está mapeado para refletir a especificação original da arquitetura. No entanto, não é utilizado no processamento.

### 1.1.3. Enum `ConditionCode`
Enumera os três estados lógicos assumidos pela Palavra de Status (SW) após instruções de comparação (`COMP`, `COMPR`).
*   **Estados Definidos:** `LESS_THAN` (<), `EQUAL` (=) e `GREATER_THAN` (>).
*   **Abstração:** Substitui a manipulação de bits na Palavra de Status (SW) e expõe o resultado das comparações em alto nível, direcionando a lógica de controle de fluxo para as instruções de salto condicional (`JEQ`, `JLT`, `JGT`).

### 1.1.4. Enum `Opcode`
Mapeia as instruções suportadas pelo simulador para seus respectivos códigos hexadecimais e formatos originais (2 ou 3/4).
*   **Identificação com Máscara:** O método estático `fromMachineByte()` recebe o primeiro byte da instrução lida na memória. Para instruções dos Formatos 3 e 4, os dois bits menos significativos desse byte contêm as flags `n` e `i`. O método aplica a constante `FORMAT_3_4_OPCODE_MASK` (`0xFC`) para zerar esses bits, permitindo o casamento exato do valor lido com o opcode base. Se o byte lido não pertencer a nenhuma instrução mapeada, lança `IllegalArgumentException`.

### 1.1.5. Enum `AddressingMode`
Representa os modos de endereçamento da arquitetura SIC/XE aplicáveis aos Formatos 3 e 4.
*   **Mapeamento Semântico:** Traduz a combinação das flags `n` e `i` para os estados lógicos correspondentes: `IMMEDIATE` (n=0, i=1), `INDIRECT` (n=1, i=0), `SIMPLE` (n=1, i=1) e `SIC_STANDARD` (n=0, i=0).

---

## 1.2. Pacote `hardware`: Componentes de Hardware

O pacote estrutura os recursos físicos e a persistência de estado da máquina virtual.

### 1.2.1. Classe `Memory`
Representa a memória principal como um vetor unidimensional de bytes (`byte[] data`).

*   **Capacidade Máxima:** A estrutura aloca estritamente 1 MB (1.048.576 bytes). Este tamanho engloba o limite máximo de endereçamento da arquitetura definido pelas instruções de Formato 4, que suportam um endereço de destino de até 20 bits.
*   **Acesso Híbrido:** A classe disponibiliza métodos de acesso granular em dois níveis:
    *   **Nível de Byte:** `readByte()` e `writeByte()`, utilizados pelas instruções `LDCH` e `STCH`.
    *   **Nível de Palavra:** `readWord()` e `writeWord()`, que interagem nativamente instanciando uma `Word24`. A leitura resolve internamente a indexação de 3 bytes consecutivos.
*   **Mecanismo de Proteção:** Todo acesso físico de leitura ou escrita executa previamente o método `validateAddress()`. O cálculo de um Endereço Efetivo (TA) fora do espaço de 1 MB ou negativo acarreta o lançamento imediato de `IllegalArgumentException`.

### 1.2.2. Classe `RegisterBank`
O Banco de Registradores unifica o estado dos registradores primários da CPU em uma estrutura baseada em `EnumMap<Register, Word24>`.

*   **Bloqueio de Operações Restritas:** O registrador de Ponto Flutuante (`F`) é inicializado como nulo e isolado pelo método `validateRegister()`. A tentativa de leitura ou escrita no registrador `F` lança uma `UnsupportedOperationException`, abortando a execução para sinalizar operação inválida.
*   **Manipulação do PC:** Para otimizar as operações contínuas no ciclo de busca (*fetch*), a classe fornece os atalhos `getPC()` e `setPC()`. Estes métodos retornam e recebem inteiros sem sinal nativos, dispensando a conversão para `Word24` a cada incremento.
*   **Integração do Código de Condição (CC):** Utiliza as abstrações definidas no Enum `ConditionCode` por meio dos métodos `getConditionCode()` e `setConditionCode()`, armazenando o estado condicional sem alterar fisicamente bits operacionais no registrador SW.

---

## 1.3. Pacote `decoder`: Decodificação de Instruções

Isola a lógica responsável por interpretar os bytes lidos da memória, transformar bits isolados em estruturas tipadas e alimentar a unidade de execução.

### 1.3.1. Classe `DecodedInstruction`
Atua como um contêiner de dados (DTO) que centraliza as propriedades decodificadas de uma instrução lida. Inclui contagem numérica para shifts e informações de execução para visualização.

*   **Separação de Formatos:** Possui construtores distintos para acomodar a discrepância estrutural entre instruções. O construtor de Formato 2 inicializa exclusivamente os parâmetros de registradores (`r1` e `r2`). O construtor de Formatos 3 e 4 inicializa o modo de endereçamento, as flags de cálculo de endereço (`x`, `b`, `p`, `e`) e o valor de deslocamento/endereço, definindo os parâmetros do Formato 2 como nulos.
*   **Facilitador de Álgebra Relativa:** O método `getSignedDisplacement()` acessa o valor cru de deslocamento extraído (12 bits) e o retorna encapsulado através de `Word24.fromSigned12Bit()`. Isso entrega para a unidade de controle o valor pronto com o sinal estendido, essencial para a adição aritmética correta no endereçamento relativo ao PC.

### 1.3.2. Classe `InstructionDecoder`
Responsável pela leitura crua da memória e extração estruturada das flags lógicas e operandos, delegando o resultado para a instanciação de um objeto `DecodedInstruction`.

*   **Decodificação de Formato 2:** Extrai os dois nibbles de quatro bits do segundo byte. Em operações comuns, são códigos de registradores; para `SHIFTL/SHIFTR`, o segundo nibble representa `n-1` e gera uma contagem numérica, inclusive para valores que não correspondem a registradores.
*   **Extração de Flags (Formatos 3 e 4):** A identificação das diretrizes de endereçamento é feita via máscaras bit a bit (`&`). A classe extrai as flags `n` e `i` a partir dos dois bits menos significativos do primeiro byte (`FLAG_N_MASK`, `FLAG_I_MASK`), e as flags `x`, `b`, `p` e `e` a partir dos quatro bits mais significativos do segundo byte lido.
*   **Concatenação de Endereços/Deslocamentos:**
    *   **Formato 4:** Ativado quando a flag `e` é verdadeira. O decodificador consome 4 bytes da memória e concatena os 4 bits inferiores do segundo byte com os 16 bits dos bytes três e quatro. Este processo utiliza deslocamentos lógicos (`<<`) e operadores OR (`|`), resultando no endereço absoluto de 20 bits.
    *   **Formato 3:** Ativado quando a flag `e` é falsa. O decodificador consome 3 bytes e forma o deslocamento relativo de 12 bits combinando o nibble inferior do byte dois e a totalidade do byte três.

## 1.4. Pacote `execution`: Execução e Endereçamento

Este pacote é responsável pela resolução final de ponteiros em memória e pela aplicação das mutações de estado na CPU (registradores e memória principal) de acordo com a semântica de cada instrução.

### 1.4.1. Classe `TargetAddressCalculator`
Isola a lógica de cálculo do Endereço Efetivo (Target Address - TA) exclusivo para as instruções dos Formatos 3 e 4. 

*   **Resolução de Deslocamento:** Avalia as flags de base e contador de programa. Se `p=1` (PC-relativo), soma o valor de deslocamento com sinal estendido ao registrador PC. Se `b=1` (Base-relativo), soma o deslocamento sem sinal ao registrador B. Se ambas forem nulas, assume o valor lido como endereço absoluto de 20 bits. A ativação simultânea das flags `p` e `b` lança uma `IllegalArgumentException`.
*   **Indexação:** Caso a flag `x` esteja ativa, adiciona o valor contido no registrador `X` ao endereço computado parcial.
*   **Truncamento Físico:** Aplica a máscara `& 0xFFFFFF` ao final do cálculo do TA para garantir que eventuais retrocessos relativos ao PC não gerem endereços negativos que vazem para a base de 32 bits do inteiro Java, confinando o ponteiro aos limites arquiteturais.
*   **Indireção:** Se o modo for indireto (`n=1, i=0`), o endereço computado é tratado como ponteiro primário. A classe acessa a memória, lê a palavra de 24 bits contida neste ponteiro e a define como o Endereço Efetivo final.

### 1.4.2. Classe `Executor`
Representa a Unidade Lógica, Aritmética e de Execução (ALU). Processa o DTO `DecodedInstruction` e executa as rotinas correspondentes, sem manipular operações de baixo nível de mascaramento de bits.

*   **Busca de Operandos Dinâmica:** O método interno `fetchOperand()` interroga o modo de endereçamento. Para o modo Imediato (`n=0, i=1`), encapsula diretamente o TA computado em uma `Word24`. Para os demais casos, efetua a leitura da palavra correspondente na memória principal.
*   **Movimentação e Manipulação de Bytes:** Executa transferências diretas de Load e Store em nível de palavra (`LDA`, `STA`). Nas instruções específicas de caractere (`LDCH`, `STCH`), utiliza os métodos de precisão isolada (`withLowByte()`, `getLowByte()`) para interagir restritamente com o último byte, preservando o restante da estrutura.
*   **Aritmética e Tratamento de Complemento de 2:** Delega as operações matemáticas para o controle interno da classe `Word24`. A conversão nativa para `toIntSigned()` blinda a aritmética de 24 bits, assegurando o comportamento correto perante valores negativos pela JVM. Operações de `DIV` ou `DIVR` por zero acionam uma `ArithmeticException`.
*   **Deslocamento em Registradores (Shift):** Para `SHIFTL` e `SHIFTR`, o decoder interpreta o segundo nibble como `n-1` e entrega uma contagem numérica de 1 a 16 em `DecodedInstruction.getShiftCount()`. O executor usa essa contagem, sem convertê-la para um registrador. A interface também apresenta a contagem.
*   **Controle de Fluxo e Subrotinas:** A classe altera o fluxo de execução manipulando diretamente o registrador PC. Comparações (`COMP`, `COMPR`) fixam o novo estado no `ConditionCode`. Saltos condicionais (`JEQ`, `JLT`, `JGT`) avaliam este código para deferir ou ignorar o salto. Subrotinas (`JSUB`) registram o estado atual do PC no registrador de ligação (`L`) antes de executar o salto, permitindo a retomada exata na instrução de retorno (`RSUB`). 

## 1.5. Pacote `machine`: Fachada e Controle da Máquina

Este pacote atua como o ponto central de integração do simulador, estruturando o Modelo (Model) no padrão arquitetural MVC e isolando os componentes internos das interações de interface.

### 1.5.1. Classe `SICXEMachine`
Encapsula as instâncias físicas e lógicas da arquitetura SIC/XE, fornecendo uma API de controle unificada para a coordenação do ciclo de instrução.

*   **Inicialização Integrada:** O construtor centraliza a instanciação da memória principal (`Memory`), do banco de registradores (`RegisterBank`), do decodificador (`InstructionDecoder`), do calculador de endereços (`TargetAddressCalculator`) e da unidade aritmética/lógica (`Executor`).
*   **Ciclo de Execução (`step()`):** Implementa o fluxo clássico de pipeline em um método sequencial:
    *   **Busca e Decodificação:** Lê o endereço armazenado no registrador PC e aciona o decodificador para extrair e tipar a instrução corrente.
    *   **Avanço Antecipado do PC:** O registrador PC é incrementado imediatamente após a decodificação, somando-se o formato da instrução (tamanho em bytes) ao valor atual. Esta atualização pré-execução é um requisito da arquitetura para viabilizar o cálculo do endereçamento PC-relativo (que usa o PC da próxima instrução como base) e para garantir o armazenamento correto do endereço de retorno em chamadas de subrotinas (`JSUB`).
    *   **Endereçamento e Execução:** Invoca o cálculo do Endereço Efetivo (TA) com as flags extraídas e despacha o pacote de dados para a unidade de execução aplicar a alteração de estado.
*   **Restabelecimento de Estado (`reset()`):** Zera toda a alocação da memória principal e redefine os registradores operacionais para zero, limpando também o Código de Condição (CC).
*   **Exposição de Hardware:** Os métodos `getMemory()` e `getRegisters()` fornecem acesso direto aos componentes. Essa abertura é necessária para que Controllers externos inspecionem o estado atual da máquina e atualizem a Interface Gráfica, mantendo a responsabilidade de mutação restrita à própria classe `SICXEMachine`.

---

## MÓDULO 2: Controller

---

## 6. Pacote `controller`: Orquestração e Eventos

Este pacote implementa a camada de controle do padrão MVC, atuando como intermediário entre a Máquina Virtual (Model) e a Interface Gráfica (View). O design garante que o processamento contínuo da CPU não bloqueie a Thread responsável pela interface gráfica.

### 6.1. Interface `MachineStateListener`
Contrato baseado no padrão *Observer* para comunicação assíncrona e desacoplada.

*   **`onMachineStateChanged`:** Invocado ao término de cada ciclo de instrução (passo a passo ou contínuo). Sinaliza à View o momento exato e seguro para consumir os dados atualizados da memória e dos registradores.
*   **`onMachineError`:** Disparado quando a execução é interrompida por uma exceção de hardware (ex: falha de endereçamento, opcode não suportado). Transfere o evento de falha para que a View o processe visualmente.

### 6.2. Classe `MachineController`
Encapsula as interações com a `SICXEMachine` e gerencia o controle de concorrência da simulação.

*   **Execução Contínua (`run` e `pause`):** `run()` cria uma thread daemon dedicada. Um lock protege os ciclos, a carga e o reset. Cada ciclo verifica se sua thread ainda é a execução ativa, impedindo que uma execução antiga continue depois de uma pausa e nova carga. `pause()` interrompe a thread, e o intervalo de execução usa uma variável `volatile`.
*   **Avanço Único (`step`):** Executa o ciclo lógico da máquina protegido por um bloco `try-catch`. Em caso de exceção de hardware, o controlador executa um `pause()` preventivo e propaga o erro formatado — incluindo o estado atual do registrador PC — através do *listener*.
*   **Reset Lógico (`reset`):** Usa `SICXEMachine.resetRegisters(entryPoint)` para limpar registradores, código de condição e última instrução, preservando a memória. O PC volta ao ponto de entrada do último programa carregado, inclusive após relocação. Os dados modificados pela execução também são preservados; recarregar o objeto restaura sua imagem inicial.
*   **Carga (`loadProgram`, `loadObjectCode`, `loadAssembly`):** O controller usa `ObjectLoader` para validar e relocar os registros H/T/M/E em uma imagem temporária, carregar memória e configurar a entrada. Entradas inválidas preservam a memória. `loadHexCode` permanece como utilitário de testes a partir de zero, mas aceita somente dígitos hexadecimais completos e espaços; não remove outros caracteres silenciosamente.

## MÓDULO 3: View

---

## 7. Pacote `view`: Interface Gráfica

Este pacote implementa a camada visual do padrão MVC utilizando Java Swing, isolando os componentes gráficos da lógica de simulação.

### 7.1. Classe `SICXEView`
Atua como a interface principal do simulador, assinando os eventos do Controller (via `MachineStateListener`) para renderizar o estado do Model de forma reativa e segura.

*   **Thread Safety (`invokeLater`):** Como o Controller executa a simulação em uma Thread separada (background), todas as atualizações visuais disparadas pelos eventos de estado e de erro são repassadas ao `SwingUtilities.invokeLater`. Isso garante que a renderização ocorra estritamente na *Event Dispatch Thread* (EDT) do Swing, prevenindo anomalias e travamentos na interface.
*   **Rastreamento do PC:** Durante a atualização de estado (`onMachineStateChanged`), a View extrai o valor atual do Program Counter (PC) e força a barra de rolagem da tabela de memória a focar e selecionar a linha correspondente, permitindo o acompanhamento visual da execução.
*   **Delegação de Controles:** Os botões (Step, Run, Pause, Reset, Load) delegam a ação imediatamente aos métodos correspondentes do Controller. A interface não possui lógicas de negócio, atuando apenas como acionadora.

---

## MÓDULO 4: Montador e Carregador

## 8. Montador e Carregador (Segunda Etapa)

A segunda etapa acrescenta a tradução de programas assembly para o código de máquina executado pelo simulador da primeira etapa. Foram mantidos a arquitetura MVC, o conjunto de 37 instruções, os tipos de registradores e a memória existentes. Os novos serviços ficam nos pacotes `assembler` e `loader`, e o painel de montagem permanece no pacote `view`.

### 8.1. Montagem em duas passagens

* **Primeira passagem:** `TwoPassAssembler` interpreta as linhas, calcula os tamanhos de instruções e dados, atualiza o contador de localização (LOCCTR) em bytes e constrói as tabelas de símbolos e literais. Também identifica rótulos repetidos e mantém linhas intermediárias com seus endereços.
* **Segunda passagem:** resolve referências aos símbolos, codifica instruções e constantes e produz objeto, listagem e diagnósticos por linha. Símbolos indefinidos e operandos inválidos impedem a emissão de um objeto executável. Cada montagem usa uma sessão independente.

### 8.2. Estruturas e responsabilidades

| Componente | Responsabilidade |
| --- | --- |
| `SourceParser` / `SourceLine` | Separar rótulo, operação e operando, preservando fonte e número de linha. |
| `TwoPassAssembler` | Coordenar as duas passagens, endereços, símbolos, reservas e literais. |
| `Symbol` / `ExpressionEvaluator` / `Constants` | Representar símbolos e avaliar expressões e constantes, distinguindo valores absolutos e relativos. |
| `InstructionEncoder` | Reutilizar `Opcode` e `Register` para gerar formatos, flags e deslocamentos. |
| `ObjectProgram` | Validar e ler/escrever os registros objeto H/T/M/E. |
| `AssemblyResult` / `ListingLine` / `Diagnostic` | Reunir objeto, listagem, símbolos e erros da montagem. |
| `ObjectLoader` | Validar a carga e a relocação antes de alterar a máquina. |
| `AssemblerPanel` / `AssemblerCli` | Disponibilizar a montagem pela interface Swing e pelo terminal. |

### 8.3. Linguagem e programa objeto

A sintaxe usa formato livre: `[ROTULO[:]] OPERACAO [OPERANDO] [; comentário]`. Rótulos e operações não diferenciam maiúsculas de minúsculas; constantes de caracteres preservam o conteúdo. Linhas vazias e comentários iniciados por `.` são ignorados. Números são decimais ou hexadecimais com `0x`; a exceção é `START`, sempre hexadecimal.

São aceitos os formatos 2, 3 e 4, com `+` explícito para o formato 4. O endereçamento pode ser simples, imediato (`#`), indireto (`@`) ou indexado (`,X`). Para símbolos relativos em formato 3, tenta-se PC-relativo (-2048 a 2047) e depois base-relativo (0 a 4095); fora dessas faixas, é necessário usar `+` ou uma base adequada. `BASE` informa um valor ao montador, mas o programa precisa carregar o registrador B.

| Diretivas | Função |
| --- | --- |
| `START` / `END` | Definir a origem e encerrar o fonte, indicando opcionalmente a entrada. Sem `START`, a origem é zero; `END` é obrigatório. |
| `BYTE` / `WORD` | Emitir caracteres ASCII ou bytes hexadecimais (`C'...'` / `X'...'`) e palavras de 24 bits. |
| `RESB` / `RESW` | Reservar bytes ou palavras de três bytes, sem emitir dados. |
| `BASE` / `NOBASE` | Habilitar ou remover a base assumida pelo montador. |
| `EQU` / `ORG` | Definir símbolos por expressão e alterar/restaurar o contador de localização, sem sobrepor áreas alocadas. |
| `LTORG` | Alocar literais pendentes, como `=C'EOF'` e `=X'05'`; `END` também aloca os restantes. |

Expressões aceitam `+`, `-`, `*`, `/`, parênteses e `*` como localização corrente. `EQU` admite referências futuras; expressões que afetam reservas ou `ORG` precisam estar resolvidas quando encontradas.

O objeto contém cabeçalho **H**, trechos de bytes **T** (até 30 bytes), modificações para relocação **M** e entrada **E**. Reservas e lacunas são preservadas. A posição de M é relativa ao início do programa; o carregador ajusta os campos indicados pela diferença entre origem de carga e origem de montagem, preservando as flags do formato 4. Uma carga inválida mantém o conteúdo anterior da memória.

### 8.4. Uso e integração com a máquina virtual

Na aba **Montador**, abra ou edite um `.asm`, clique em **Montar** e consulte **Listagem e símbolos**, **Objeto** e **Erros**. Uma montagem válida permite salvar `.obj` e `.lst` ou usar **Carregar na VM**, informando a origem em hexadecimal. Editar o fonte invalida o resultado anterior. **Abrir objeto** permite carregar um `.obj` existente.

Pelo terminal, após `mvn compile`, execute `java -cp target/classes assembler.AssemblerCli test-programs/loop.asm [prefixo-de-saida]`. São gerados objeto e listagem; em caso de erro, apenas a listagem é atualizada. Um `.obj` antigo não é apagado: use o objeto somente se a execução atual terminar com sucesso. Para gerar e executar o JAR, use `mvn package` e `java -jar target/sicxe-simulator-1.0-SNAPSHOT.jar`. No Windows, `executar.cmd` abre a aplicação e `maven.cmd` localiza o Maven instalado.

As adaptações da etapa 1 estão em `MachineController`, `SICXEView`, `DecodedInstruction`, `InstructionDecoder`, `Executor` e `SICXEMachine`. Elas integram montagem e carga, preservam a entrada no reset, representam corretamente a contagem dos shifts e corrigem `LDCH` imediato. **Reset** limpa registradores e restaura a entrada, preservando a memória; recarregar o objeto restaura os dados iniciais. `END` encerra a montagem e não representa uma instrução de parada da CPU.

### 8.5. Exemplos, testes e limites

Os fontes de exemplo ficam em `test-programs`: `loop.asm` calcula 10 após 19 instruções; `addressing.asm` exercita endereçamento, formato 4, base e shift, produzindo 256 após oito instruções; `literals.asm` demonstra literais, `LTORG`, `EQU` e `ORG`.

A suíte possui 44 testes, incluindo o teste original da máquina e testes de codificação, símbolos, erros, limites PC/base, expressões, literais, objeto, relocação, carga, reset, terminal e painel gráfico. Execute `mvn test` ou `.\maven.cmd test` no Windows.

O escopo utiliza as instruções da VM da primeira etapa. Não inclui modo SIC clássico (`n=i=0`), macros, blocos `USE`, múltiplas seções `CSECT` ou ligação externa `EXTDEF`/`EXTREF`. O fonte deve conter pelo menos uma instrução executável. Como não há `HALT` nesse subconjunto, os exemplos usam um laço final e a execução pode ser interrompida com **Pause**.
